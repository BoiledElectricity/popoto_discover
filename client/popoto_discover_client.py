 #!/usr/bin/env python3
import socket
import json
import time
import subprocess
import sys
import os
import shutil
import logging
from typing import Optional, Tuple
import uuid

# Add parent directory to path to import common module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

# Add popoto module to path (on target hardware)
sys.path.insert(0, '/opt/popoto')

from common import protocol

# Popoto API will be imported on-demand to handle cases where it's not available
popoto_api = None

# Configure logging based on platform
import platform

def get_log_file_path():
    """
    Get appropriate log file path based on platform.

    Returns:
        Path to log file
    """
    if platform.system() == 'Darwin':  # macOS
        # Use local directory on Mac
        log_dir = os.path.join(os.path.dirname(__file__), 'logs')
        os.makedirs(log_dir, exist_ok=True)
        return os.path.join(log_dir, 'popoto_discover_client.log')
    else:
        # Use system log directory on Linux/other
        return '/var/log/popoto_discover_client.log'

log_file = get_log_file_path()

# Try to create log file handlers with fallback
log_handlers = [logging.StreamHandler()]

try:
    log_handlers.append(logging.FileHandler(log_file))
    print(f"Logging to: {log_file}")
except (PermissionError, IOError) as e:
    print(f"Warning: Could not create log file {log_file}: {e}")
    print("Logging to console only")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=log_handlers
)
logger = logging.getLogger('popoto_client')


def get_network_interface() -> Optional[str]:
    """
    Find the primary network interface (first non-loopback with IPv4).

    Returns:
        Interface name or None if not found
    """
    import netifaces
    try:
        for iface in netifaces.interfaces():
            if iface == "lo":
                continue
            addrs = netifaces.ifaddresses(iface)
            if netifaces.AF_INET in addrs:
                logger.debug(f"Found primary network interface: {iface}")
                return iface
    except Exception as e:
        logger.error(f"Error finding network interface: {e}")
    return None


def get_ip_address(interface: Optional[str] = None) -> str:
    """
    Get IP address of the specified interface or first non-loopback interface.

    Args:
        interface: Network interface name (optional)

    Returns:
        IP address string or "0.0.0.0" if not found
    """
    import netifaces
    try:
        interfaces = [interface] if interface else netifaces.interfaces()
        for iface in interfaces:
            if iface == "lo":
                continue
            addrs = netifaces.ifaddresses(iface)
            if netifaces.AF_INET in addrs:
                ip = addrs[netifaces.AF_INET][0]["addr"]
                logger.debug(f"Interface {iface} has IP: {ip}")
                return ip
    except Exception as e:
        logger.error(f"Error getting IP address: {e}")
    return "0.0.0.0"


def get_mac_address(interface: Optional[str] = None) -> Optional[str]:
    """
    Get MAC address of the specified interface or first non-loopback interface.

    Args:
        interface: Network interface name (optional)

    Returns:
        MAC address string (e.g., "00:11:22:33:44:55") or None if not found
    """
    import netifaces
    try:
        interfaces = [interface] if interface else netifaces.interfaces()
        for iface in interfaces:
            if iface == "lo":
                continue
            addrs = netifaces.ifaddresses(iface)
            if netifaces.AF_LINK in addrs:
                mac = addrs[netifaces.AF_LINK][0]["addr"]
                logger.debug(f"Interface {iface} has MAC: {mac}")
                return mac
    except Exception as e:
        logger.error(f"Error getting MAC address: {e}")
    return None

def get_battery_voltage() -> float:
    """
    Get battery voltage from Popoto API.

    Returns:
        Battery voltage in volts, or 0.0 if unavailable
    """
    try:
        api = get_popoto_api()
        if api is None:
            logger.warning("Popoto API not available for battery voltage")
            return 0.0

        api.drainReplyQquiet()
        api.get('BatteryVoltage')

        reply = api.waitForSpecificReply("BatteryVoltage", None, 2)

        if "Timeout" in reply:
            logger.warning("Timeout getting battery voltage")
            return 0.0

        battery_v = reply.get("BatteryVoltage", 0.0)
        return float(battery_v)

    except Exception as e:
        logger.error(f"Error getting battery voltage: {e}")
        return 0.0


def get_sample_rate() -> int:
    """
    Get sample rate from Popoto API.

    Returns:
        Sample rate in Hz, or 0 if unavailable
    """
    try:
        api = get_popoto_api()
        if api is None:
            logger.warning("Popoto API not available for sample rate")
            return 0

        # The sample rate is stored in the API instance
        sample_rate = getattr(api, 'SampFreq', 0)
        return int(sample_rate)

    except Exception as e:
        logger.error(f"Error getting sample rate: {e}")
        return 0


def get_recording_state() -> str:
    """
    Get recording state from Popoto API.

    Returns:
        Recording state: "UNIMPLEMENTED" for now
    """
    return "UNIMPLEMENTED"


def get_status():
    """
    Get device status and telemetry.

    Returns:
        Dictionary with status information
    """
    # Get storage info using shutil (standard library, no dependencies)
    try:
        disk = shutil.disk_usage('/')
        storage_free_gb = disk.free / (1024**3)
        storage_total_gb = disk.total / (1024**3)
    except Exception as e:
        logger.error(f"Error getting storage info: {e}")
        storage_free_gb = 0.0
        storage_total_gb = 0.0

    # Get real telemetry from Popoto API
    battery_v = get_battery_voltage()
    sample_rate_hz = get_sample_rate()
    recording_state = get_recording_state()

    return {
        "battery_v": round(battery_v, 2),
        "sample_rate_hz": sample_rate_hz,
        "recording_state": recording_state,
        "storage_free_gb": round(storage_free_gb, 2),
        "storage_total_gb": round(storage_total_gb, 2),
    }


def netmask_to_cidr(netmask: str) -> int:
    """
    Convert netmask to CIDR notation.

    Args:
        netmask: Netmask string (e.g., "255.255.255.0")

    Returns:
        CIDR prefix length (e.g., 24)
    """
    octets = [int(x) for x in netmask.split('.')]
    binary = ''.join(format(octet, '08b') for octet in octets)
    return binary.count('1')


def apply_ip_config(interface: str, new_ip: str, netmask: str, gateway: str) -> Tuple[bool, str]:
    """
    Apply IP configuration to network interface.

    Args:
        interface: Network interface name
        new_ip: New IP address
        netmask: Network mask
        gateway: Gateway address

    Returns:
        Tuple of (success, error_message)
    """
    try:
        # Validate all parameters
        if not protocol.validate_ip_address(new_ip):
            return False, f"Invalid IP address: {new_ip}"
        if not protocol.validate_netmask(netmask):
            return False, f"Invalid netmask: {netmask}"
        if not protocol.validate_ip_address(gateway):
            return False, f"Invalid gateway: {gateway}"

        logger.info(f"Applying IP config to {interface}: {new_ip}/{netmask} gw={gateway}")

        # Convert netmask to CIDR
        cidr = netmask_to_cidr(netmask)

        # Flush existing addresses (requires root)
        result = subprocess.run(
            ["ip", "addr", "flush", "dev", interface],
            capture_output=True,
            text=True,
            timeout=5
        )
        if result.returncode != 0:
            logger.error(f"Failed to flush addresses: {result.stderr}")
            return False, f"Failed to flush addresses: {result.stderr}"

        # Add new IP address
        result = subprocess.run(
            ["ip", "addr", "add", f"{new_ip}/{cidr}", "dev", interface],
            capture_output=True,
            text=True,
            timeout=5
        )
        if result.returncode != 0:
            logger.error(f"Failed to add IP address: {result.stderr}")
            return False, f"Failed to add IP address: {result.stderr}"

        # Bring interface up
        result = subprocess.run(
            ["ip", "link", "set", interface, "up"],
            capture_output=True,
            text=True,
            timeout=5
        )
        if result.returncode != 0:
            logger.error(f"Failed to bring interface up: {result.stderr}")
            return False, f"Failed to bring interface up: {result.stderr}"

        # Add default route
        # First, delete existing default route (ignore errors)
        subprocess.run(
            ["ip", "route", "del", "default"],
            capture_output=True,
            text=True,
            timeout=5
        )

        # Add new default route
        result = subprocess.run(
            ["ip", "route", "add", "default", "via", gateway],
            capture_output=True,
            text=True,
            timeout=5
        )
        if result.returncode != 0:
            logger.error(f"Failed to add default route: {result.stderr}")
            return False, f"Failed to add default route: {result.stderr}"

        logger.info(f"Successfully configured {interface} with {new_ip}/{cidr}")
        return True, ""

    except subprocess.TimeoutExpired:
        error_msg = "Timeout executing network configuration command"
        logger.error(error_msg)
        return False, error_msg
    except Exception as e:
        error_msg = f"Error applying IP config: {e}"
        logger.error(error_msg)
        return False, error_msg


def get_popoto_api():
    """
    Get or create popoto API instance.

    Returns:
        popoto instance or None if unavailable
    """
    global popoto_api

    if popoto_api is not None:
        return popoto_api

    try:
        import popoto as popoto_module
        popoto_api = popoto_module.popoto(ip='localhost', basePort=17000, logname='popoto_discover')
        logger.info("Connected to popoto API on localhost:17000")
        return popoto_api
    except ImportError:
        logger.warning("popoto module not available - RTC and parameter commands will not work")
        return None
    except Exception as e:
        logger.error(f"Failed to connect to popoto API: {e}")
        return None


def set_rtc_time(rtc_str: str) -> Tuple[bool, str]:
    """
    Set the real-time clock via popoto API.

    Args:
        rtc_str: RTC string in format YYYY.MM.DD-HH:MM:SS

    Returns:
        Tuple of (success, error_message)
    """
    try:
        api = get_popoto_api()
        if api is None:
            return False, "Popoto API not available"

        # Validate RTC format
        if not protocol.validate_rtc_format(rtc_str):
            return False, f"Invalid RTC format: {rtc_str} (expected YYYY.MM.DD-HH:MM:SS)"

        logger.info(f"Setting RTC to: {rtc_str}")
        api.setRtc(rtc_str)

        # Wait for response
        time.sleep(0.5)

        logger.info(f"Successfully set RTC to {rtc_str}")
        return True, ""

    except Exception as e:
        error_msg = f"Error setting RTC: {e}"
        logger.error(error_msg)
        return False, error_msg


def get_rtc_time() -> Tuple[bool, str, str]:
    """
    Get the real-time clock via popoto API.

    Returns:
        Tuple of (success, rtc_string, error_message)
    """
    try:
        api = get_popoto_api()
        if api is None:
            return False, "", "Popoto API not available"

        logger.info("Getting RTC")
        api.drainReplyQquiet()
        api.getRtc()

        # Wait for and parse response - RTC comes back as "Time" field
        reply = api.waitForSpecificReply("Time", None, 5)

        if "Timeout" in reply:
            return False, "", "Timeout waiting for RTC response"

        if "Time" in reply:
            rtc_value = reply["Time"]
            logger.info(f"Got RTC: {rtc_value}")
            return True, rtc_value, ""
        else:
            return False, "", "No Time value in response"

    except Exception as e:
        error_msg = f"Error getting RTC: {e}"
        logger.error(error_msg)
        return False, "", error_msg


def set_popoto_param(param_name: str, param_value: any) -> Tuple[bool, str]:
    """
    Set a popoto parameter via popoto API.

    Args:
        param_name: Name of the parameter
        param_value: Value to set (int or float)

    Returns:
        Tuple of (success, error_message)
    """
    try:
        api = get_popoto_api()
        if api is None:
            return False, "Popoto API not available"

        logger.info(f"Setting parameter {param_name} = {param_value}")

        # Use the appropriate setter based on type
        if isinstance(param_value, int):
            api.setValueI(param_name, param_value)
        elif isinstance(param_value, float):
            api.setValueF(param_name, param_value)
        else:
            api.set(param_name, param_value)

        # Wait for response
        time.sleep(0.5)

        logger.info(f"Successfully set {param_name} = {param_value}")
        return True, ""

    except Exception as e:
        error_msg = f"Error setting parameter {param_name}: {e}"
        logger.error(error_msg)
        return False, error_msg


def get_version() -> Tuple[bool, str, str, str]:
    """
    Get the version and serial number via popoto API.

    Returns:
        Tuple of (success, version_string, serial_number, error_message)
    """
    try:
        api = get_popoto_api()
        if api is None:
            return False, "", "", "Popoto API not available"

        logger.info("Getting version")
        api.drainReplyQquiet()
        api.getVersion()

        # Wait for and parse response - version comes back in "Info" message
        # Format: {"Info":"Popoto Modem Version 4.6.0+860.7ee41.a9fe3bfed"}
        reply = api.waitForSpecificReply("Info", "Popoto Modem Version", 5)

        if "Timeout" in reply:
            return False, "", "", "Timeout waiting for version response"

        info_str = reply.get("Info", "")

        # Parse version from "Popoto Modem Version X.Y.Z+..." format
        version_str = ""
        if "Popoto Modem Version" in info_str:
            version_str = info_str.replace("Popoto Modem Version", "").strip()
        else:
            version_str = info_str

        # Try to read serial number from file
        serial_number = ""
        try:
            if os.path.exists("/etc/PopotoSerialNumber.txt"):
                with open("/etc/PopotoSerialNumber.txt", 'r') as f:
                    serial_number = f.read().strip()
        except Exception as e:
            logger.warning(f"Could not read serial number file: {e}")

        logger.info(f"Got version: {version_str}, serial: {serial_number}")
        return True, version_str, serial_number, ""

    except Exception as e:
        error_msg = f"Error getting version: {e}"
        logger.error(error_msg)
        return False, "", "", error_msg


def get_hostname() -> str:
    """
    Get the hostname from /etc/hostname.

    Returns:
        Hostname string, or "unknown" if not found
    """
    try:
        if os.path.exists("/etc/hostname"):
            with open("/etc/hostname", 'r') as f:
                hostname = f.read().strip()
                logger.debug(f"Read hostname from /etc/hostname: {hostname}")
                return hostname
        else:
            logger.warning("/etc/hostname not found, using socket.gethostname()")
            import socket
            return socket.gethostname()
    except Exception as e:
        logger.error(f"Error reading hostname: {e}")
        return "unknown"


def get_network_config(interface: Optional[str] = None) -> Tuple[str, str]:
    """
    Get current netmask and gateway for the network interface.

    Args:
        interface: Network interface name (optional)

    Returns:
        Tuple of (netmask, gateway)
    """
    import netifaces

    netmask = "255.255.255.0"  # Default
    gateway = ""

    try:
        # Get netmask
        if not interface:
            interface = get_network_interface()

        if interface:
            addrs = netifaces.ifaddresses(interface)
            if netifaces.AF_INET in addrs:
                netmask = addrs[netifaces.AF_INET][0].get('netmask', '255.255.255.0')

        # Get default gateway
        gws = netifaces.gateways()
        if 'default' in gws and netifaces.AF_INET in gws['default']:
            gateway = gws['default'][netifaces.AF_INET][0]

        logger.debug(f"Network config: netmask={netmask}, gateway={gateway}")

    except Exception as e:
        logger.error(f"Error getting network config: {e}")

    return netmask, gateway


def main():
    """Main event loop - listen for discovery and configuration requests."""

    # Load shared secret for authentication
    secret = None
    if protocol.AUTH_ENABLED:
        try:
            secret = protocol.load_shared_secret()
            logger.info("Loaded shared secret for authentication")
        except FileNotFoundError as e:
            logger.error(f"Authentication is enabled but secret file not found: {e}")
            logger.error("Create a secret file or disable AUTH_ENABLED in common/protocol.py")
            sys.exit(1)
        except ValueError as e:
            logger.error(f"Invalid secret file: {e}")
            sys.exit(1)
    else:
        logger.warning("Authentication is DISABLED - this is insecure!")

    # Get network interface and MAC address
    interface = get_network_interface()
    if not interface:
        logger.warning("No network interface found! Using placeholder values.")
        interface = "unknown"
        mac = "00:00:00:00:00:00"
    else:
        mac = get_mac_address(interface)
        if not mac:
            logger.warning(f"Could not get MAC address for interface {interface}")
            logger.warning("Using placeholder MAC address - device will respond to discovery but not MAC-targeted commands")
            mac = "00:00:00:00:00:00"

    logger.info(f"Using network interface: {interface}, MAC: {mac}")

    # Device metadata - get from Popoto device
    # Model comes from hostname
    model = get_hostname()

    # Get version and serial number from device
    success, fw, serial, error_msg = get_version()
    if not success:
        logger.warning(f"Could not get version from device: {error_msg}")
        fw = "unknown"
        serial = "unknown"

    # If serial is empty, use a fallback
    if not serial:
        serial = "UNKNOWN-" + uuid.uuid4().hex[:6].upper()
        logger.warning(f"No serial number found, using generated: {serial}")

    name = f"{model}-{serial}"

    logger.info(f"Device: {name}, Model: {model}, Firmware: {fw}, Serial: {serial}")

    # Create and bind socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", protocol.DISCOVERY_PORT))
        logger.info(f"Listening on UDP port {protocol.DISCOVERY_PORT}")
    except Exception as e:
        logger.error(f"Failed to create socket: {e}")
        sys.exit(1)

    # Main event loop
    while True:
        try:
            data, addr = sock.recvfrom(4096)

            # Parse JSON message
            try:
                msg = json.loads(data.decode("utf-8"))
            except json.JSONDecodeError as e:
                logger.warning(f"Received invalid JSON from {addr}: {e}")
                continue

            cmd = msg.get("cmd", "")
            nonce = msg.get("nonce", "")

            logger.debug(f"Received command '{cmd}' from {addr} with nonce {nonce}")

            # Verify authentication
            if protocol.AUTH_ENABLED:
                try:
                    if not protocol.verify_auth(msg, secret):
                        logger.warning(f"Authentication failed for message from {addr}")
                        continue
                except protocol.AuthenticationError as e:
                    logger.warning(f"Authentication error from {addr}: {e}")
                    continue

            # Handle discovery request
            if cmd == protocol.MSG_DISCOVER:
                try:
                    protocol.validate_discover_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid discovery request from {addr}: {e}")
                    continue

                ip = get_ip_address(interface)
                st = get_status()
                hostname = get_hostname()
                netmask, gateway = get_network_config(interface)

                reply = {
                    "cmd": protocol.MSG_DISCOVER_REPLY,
                    "nonce": nonce,
                    "model": model,
                    "serial": serial,
                    "ip": ip,
                    "mac": mac,
                    "fw": fw,
                    "http_port": 80,
                    "name": name,
                    "hostname": hostname,
                    "netmask": netmask,
                    "gateway": gateway,
                    **st
                }

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent discovery reply to {addr}")
                except Exception as e:
                    logger.error(f"Failed to send discovery reply to {addr}: {e}")

            # Handle set IP request
            elif cmd == protocol.MSG_SET_IP:
                try:
                    protocol.validate_set_ip_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid set_ip request from {addr}: {e}")
                    continue

                # Only respond if target MAC matches
                target_mac = msg.get("target_mac", "")
                if target_mac.lower() != mac.lower():
                    logger.debug(f"Ignoring set_ip for different MAC: {target_mac}")
                    continue

                logger.warning(f"Received IP configuration request from {addr}")

                # Apply IP configuration
                new_ip = msg.get("new_ip")
                netmask = msg.get("netmask")
                gateway = msg.get("gateway")

                success, error_msg = apply_ip_config(interface, new_ip, netmask, gateway)

                reply = {
                    "cmd": protocol.MSG_SET_IP_REPLY,
                    "nonce": nonce,
                    "status": "ok" if success else "error",
                    "ip": new_ip,
                }

                if not success:
                    reply["error"] = error_msg

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent set_ip reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send set_ip reply to {addr}: {e}")

            # Handle set RTC request
            elif cmd == protocol.MSG_SET_RTC:
                try:
                    protocol.validate_set_rtc_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid set_rtc request from {addr}: {e}")
                    continue

                # Only respond if target MAC matches
                target_mac = msg.get("target_mac", "")
                if target_mac.lower() != mac.lower():
                    logger.debug(f"Ignoring set_rtc for different MAC: {target_mac}")
                    continue

                logger.warning(f"Received RTC set request from {addr}")

                # Set RTC
                rtc_str = msg.get("rtc")
                success, error_msg = set_rtc_time(rtc_str)

                reply = {
                    "cmd": protocol.MSG_SET_RTC_REPLY,
                    "nonce": nonce,
                    "status": "ok" if success else "error",
                }

                if not success:
                    reply["error"] = error_msg

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent set_rtc reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send set_rtc reply to {addr}: {e}")

            # Handle get RTC request
            elif cmd == protocol.MSG_GET_RTC:
                try:
                    protocol.validate_get_rtc_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid get_rtc request from {addr}: {e}")
                    continue

                # Only respond if target MAC matches
                target_mac = msg.get("target_mac", "")
                if target_mac.lower() != mac.lower():
                    logger.debug(f"Ignoring get_rtc for different MAC: {target_mac}")
                    continue

                logger.info(f"Received RTC get request from {addr}")

                # Get RTC
                success, rtc_value, error_msg = get_rtc_time()

                reply = {
                    "cmd": protocol.MSG_GET_RTC_REPLY,
                    "nonce": nonce,
                    "status": "ok" if success else "error",
                }

                if success:
                    reply["rtc"] = rtc_value
                else:
                    reply["error"] = error_msg

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent get_rtc reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send get_rtc reply to {addr}: {e}")

            # Handle set parameter request
            elif cmd == protocol.MSG_SET_PARAM:
                try:
                    protocol.validate_set_param_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid set_param request from {addr}: {e}")
                    continue

                # Only respond if target MAC matches
                target_mac = msg.get("target_mac", "")
                if target_mac.lower() != mac.lower():
                    logger.debug(f"Ignoring set_param for different MAC: {target_mac}")
                    continue

                logger.warning(f"Received parameter set request from {addr}")

                # Set parameter
                param_name = msg.get("param_name")
                param_value = msg.get("param_value")
                success, error_msg = set_popoto_param(param_name, param_value)

                reply = {
                    "cmd": protocol.MSG_SET_PARAM_REPLY,
                    "nonce": nonce,
                    "status": "ok" if success else "error",
                }

                if not success:
                    reply["error"] = error_msg

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent set_param reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send set_param reply to {addr}: {e}")

            # Handle get version request
            elif cmd == protocol.MSG_GET_VERSION:
                try:
                    protocol.validate_get_version_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid get_version request from {addr}: {e}")
                    continue

                # Only respond if target MAC matches
                target_mac = msg.get("target_mac", "")
                if target_mac.lower() != mac.lower():
                    logger.debug(f"Ignoring get_version for different MAC: {target_mac}")
                    continue

                logger.info(f"Received version get request from {addr}")

                # Get version
                success, version_str, serial_number, error_msg = get_version()

                reply = {
                    "cmd": protocol.MSG_GET_VERSION_REPLY,
                    "nonce": nonce,
                    "status": "ok" if success else "error",
                }

                if success:
                    reply["version"] = version_str
                    reply["serial"] = serial_number
                else:
                    reply["error"] = error_msg

                # Add authentication to reply
                if secret:
                    reply = protocol.add_auth(reply, secret)

                try:
                    sock.sendto(json.dumps(reply).encode("utf-8"), addr)
                    logger.info(f"Sent get_version reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send get_version reply to {addr}: {e}")

            else:
                logger.warning(f"Unknown command '{cmd}' from {addr}")

        except KeyboardInterrupt:
            logger.info("Received shutdown signal")
            break
        except Exception as e:
            logger.error(f"Error in main loop: {e}", exc_info=True)
            # Continue running despite errors
            time.sleep(0.1)

    # Clean shutdown
    sock.close()
    logger.info("Client shutdown complete")


if __name__ == "__main__":
    main()
