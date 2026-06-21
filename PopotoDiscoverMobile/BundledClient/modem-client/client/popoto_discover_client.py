 #!/usr/bin/env python3
import socket
import json
import time
import random
import re
import subprocess
import sys
import os
import shutil
import logging
from typing import Optional, Tuple
import uuid
import threading

# Add parent directory to path to import common module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

# Add popoto module to path (on target hardware)
sys.path.insert(0, '/opt/popoto')

from common import protocol
from common import l2_transport

# Popoto API will be imported on-demand to handle cases where it's not available
popoto_api = None
device_id_cache = ""
fw_cache = ""
serial_cache = ""
device_id_last_probe = 0.0
serial_last_probe = 0.0
version_refreshing = False
version_info_lock = threading.Lock()
popoto_api_lock = threading.Lock()
battery_voltage_cache = 0.0
battery_voltage_last_probe = 0.0
battery_voltage_lock = threading.Lock()
mdns_identity_cache = ""
MAX_SHELL_OUTPUT_CHARS = 400
IMX_OCOTP_NVMEM = "/sys/bus/nvmem/devices/imx-ocotp0/nvmem"
IMX_CPU_UID_OFFSET = 4
IMX_CPU_UID_SIZE = 8
BATTERY_VOLTAGE_REFRESH_SECONDS = 30.0
DISCOVER_CLIENT_VERSION_FILE = "/opt/popoto/popoto_discover/VERSION"

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
                if mac and mac != "00:00:00:00:00:00":
                    logger.debug(f"Interface {iface} has MAC: {mac}")
                    return mac
    except Exception as e:
        logger.error(f"Error getting MAC address: {e}")

    if interface:
        try:
            with open(f"/sys/class/net/{interface}/address", "r") as f:
                mac = f.read().strip()
            if mac and mac != "00:00:00:00:00:00":
                logger.debug(f"Interface {interface} has sysfs MAC: {mac}")
                return mac
        except Exception as e:
            logger.debug(f"Could not read sysfs MAC for {interface}: {e}")

    return None

def get_battery_voltage() -> float:
    """
    Get battery voltage from pShell with daemon-side rate limiting.

    Returns:
        Battery voltage in volts, or 0.0 if unavailable
    """
    global battery_voltage_cache, battery_voltage_last_probe

    now = time.time()
    if battery_voltage_last_probe and now - battery_voltage_last_probe < BATTERY_VOLTAGE_REFRESH_SECONDS:
        return battery_voltage_cache

    with battery_voltage_lock:
        now = time.time()
        if battery_voltage_last_probe and now - battery_voltage_last_probe < BATTERY_VOLTAGE_REFRESH_SECONDS:
            return battery_voltage_cache

        try:
            api = get_popoto_api()
            if api is None:
                return battery_voltage_cache

            with popoto_api_lock:
                api.drainReplyQquiet()
                api.get('BatteryVoltage')

                reply = api.waitForSpecificReply("BatteryVoltage", None, 2)
            battery_voltage_last_probe = time.time()

            if "Timeout" in reply:
                logger.warning("Timeout getting battery voltage")
                return battery_voltage_cache

            battery_v = reply.get("BatteryVoltage", battery_voltage_cache)
            battery_voltage_cache = float(battery_v)
            return battery_voltage_cache

        except Exception as e:
            battery_voltage_last_probe = time.time()
            logger.error(f"Error getting battery voltage: {e}")
            return battery_voltage_cache


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


def set_uboot_env(name: str, value: str) -> Tuple[bool, str]:
    """
    Set a supported U-Boot environment variable through fw_setenv.
    """
    if name != "pmm_eth_console":
        return False, f"Unsupported U-Boot environment variable: {name}"

    if str(value) not in ("0", "1"):
        return False, "pmm_eth_console must be 0 or 1"

    fw_setenv = shutil.which("fw_setenv")
    if not fw_setenv:
        for candidate in ("/usr/sbin/fw_setenv", "/sbin/fw_setenv"):
            if os.path.exists(candidate):
                fw_setenv = candidate
                break
    if not fw_setenv:
        return False, "fw_setenv not found"

    try:
        result = subprocess.run(
            [fw_setenv, name, str(value)],
            capture_output=True,
            text=True,
            timeout=8,
        )
        if result.returncode != 0:
            error = (result.stderr or result.stdout or "fw_setenv failed").strip()
            logger.error(f"Failed to set U-Boot env {name}: {error}")
            return False, error
        logger.warning(f"Set U-Boot env {name}={value}")
        return True, ""
    except subprocess.TimeoutExpired:
        return False, "fw_setenv timed out"
    except Exception as e:
        return False, f"Error running fw_setenv: {e}"


def schedule_reboot() -> Tuple[bool, str]:
    """
    Reboot after the reply has had a chance to leave the socket.
    """
    try:
        subprocess.Popen(
            ["/bin/sh", "-c", "sleep 0.5; sync; /sbin/reboot || reboot"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        logger.warning("Scheduled reboot for Popoto Discover flash workflow")
        return True, ""
    except Exception as e:
        return False, f"Error scheduling reboot: {e}"


def _limited_text(value, limit: int = MAX_SHELL_OUTPUT_CHARS) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="replace")
    text = str(value)
    if len(text) <= limit:
        return text
    return text[:limit] + "\n...[truncated]"


def run_shell_command(command: str, timeout_seconds: float) -> Tuple[bool, str, int, str, str]:
    """
    Run a root shell command for authenticated host-side management.
    """
    timeout = max(1.0, min(float(timeout_seconds), 60.0))
    try:
        result = subprocess.run(
            command,
            shell=True,
            executable="/bin/sh",
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        stdout = _limited_text(result.stdout)
        stderr = _limited_text(result.stderr)
        if result.returncode == 0:
            return True, "", result.returncode, stdout, stderr
        error = stderr or stdout or f"shell command exited {result.returncode}"
        return False, _limited_text(error, 200), result.returncode, stdout, stderr
    except subprocess.TimeoutExpired as e:
        stdout = _limited_text(e.stdout)
        stderr = _limited_text(e.stderr)
        return False, f"shell command timed out after {timeout:.1f}s", 124, stdout, stderr
    except Exception as e:
        return False, f"Error running shell command: {e}", 127, "", ""


def get_version(timeout_seconds: float = 5.0) -> Tuple[bool, str, str, str]:
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
        version_str = ""
        serial_number = ""

        with popoto_api_lock:
            # Current pShell reports both values from "version". Older builds
            # used "getVersion", so keep that as a compatibility fallback.
            for command in ("version", "getVersion"):
                api.drainReplyQquiet()
                if command == "getVersion":
                    api.getVersion()
                else:
                    api.send(command)

                deadline = time.time() + max(1.0, timeout_seconds)
                while time.time() < deadline:
                    reply = api.waitForReply(1)
                    if "Timeout" in reply:
                        if version_str:
                            break
                        continue

                    info_str = _reply_value(reply, "Info")
                    if not info_str:
                        continue

                    parsed_version = _parse_version_info(info_str)
                    if parsed_version:
                        version_str = parsed_version
                        if _valid_identity(serial_number):
                            break
                        continue

                    parsed_serial = _parse_serial_info(info_str)
                    if parsed_serial is not None:
                        serial_number = parsed_serial
                        if version_str:
                            break

                if version_str and _valid_identity(serial_number):
                    break

        if not version_str:
            return False, "", "", "Timeout waiting for version response"

        set_version_cache(version_str, serial_number if _valid_identity(serial_number) else "unknown")
        fw_cached, serial_cached = read_cached_version_info()
        version_str = fw_cached
        serial_number = serial_cached

        logger.info(f"Got version: {version_str}, serial: {serial_number}")
        return True, version_str, serial_number, ""

    except Exception as e:
        error_msg = f"Error getting version: {e}"
        logger.error(error_msg)
        return False, "", "", error_msg


def _valid_identity(value) -> bool:
    if value is None:
        return False
    text = str(value).strip()
    if not text:
        return False
    if set(text) == {"0"}:
        return False
    return text.upper() not in ("UNKNOWN", "UNKNOWN ELEMENT", "NONE", "0")


def _reply_value(reply, key) -> str:
    if not isinstance(reply, dict):
        return ""
    for reply_key, value in reply.items():
        if str(reply_key).strip() == key and value is not None:
            return str(value).strip()
    return ""


def _parse_version_info(info: str) -> Optional[str]:
    prefix = "Popoto Modem Version"
    text = str(info).strip()
    if text.startswith(prefix):
        return text[len(prefix):].strip()
    return None


def _parse_serial_info(info: str) -> Optional[str]:
    match = re.match(r'\s*SerialNumber\s+(.+?)\s*$', str(info))
    if not match:
        return None
    serial = match.group(1).strip().strip('"')
    return serial if _valid_identity(serial) else "unknown"


def set_version_cache(fw: str, serial: str) -> None:
    global fw_cache, serial_cache
    with version_info_lock:
        fw_cache = fw if fw else "unknown"
        serial_cache = serial if serial else "unknown"


def set_serial_cache(serial: str) -> None:
    global serial_cache
    with version_info_lock:
        serial_cache = serial if serial else "unknown"


def read_cached_version_info() -> Tuple[str, str]:
    with version_info_lock:
        fw = fw_cache if fw_cache else "unknown"
        serial = serial_cache if serial_cache else "unknown"
    return fw, serial


def _refresh_version_cache_background() -> None:
    global version_refreshing
    try:
        get_version(timeout_seconds=5.0)
    finally:
        with version_info_lock:
            version_refreshing = False


def maybe_refresh_version_cache(min_probe_interval: float = 10.0) -> None:
    """
    Refresh FW/serial in the background when discovery only has unknown data.

    Discovery replies must stay fast. If startup missed pShell because the
    modem app was not ready yet, this lets a later discovery populate FW and
    manufacturing serial without blocking the discovery request path.
    """
    global serial_last_probe, version_refreshing

    fw, serial = read_cached_version_info()
    if _valid_identity(fw) and _valid_identity(serial):
        return

    now = time.time()
    with version_info_lock:
        if version_refreshing:
            return
        if serial_last_probe and now - serial_last_probe < min_probe_interval:
            return
        serial_last_probe = now
        version_refreshing = True

    thread = threading.Thread(target=_refresh_version_cache_background, name="version-refresh", daemon=True)
    thread.start()


def _identity_from_reply(reply, keys) -> Optional[str]:
    if not isinstance(reply, dict):
        return None
    for key in keys:
        value = reply.get(key)
        if _valid_identity(value):
            return str(value).strip()
    return None


def _serial_from_reply(reply) -> Optional[str]:
    return _identity_from_reply(reply, ("SerialNumber", "Serial"))


def _device_id_from_reply(reply) -> Optional[str]:
    return _identity_from_reply(reply, ("DeviceID", "UniqueID", "LocalID"))


def read_imx_cpu_uid() -> str:
    """
    Read the stable i.MX CPU unique ID from OCOTP.

    This is the stable discovery and command-target identity. It is distinct
    from the pShell/manufacturing serial number.
    """
    try:
        with open(IMX_OCOTP_NVMEM, "rb") as f:
            f.seek(IMX_CPU_UID_OFFSET)
            value = f.read(IMX_CPU_UID_SIZE)
        if len(value) != IMX_CPU_UID_SIZE:
            return ""
        text = value.hex()
        return text if _valid_identity(text) else ""
    except Exception as e:
        logger.debug(f"Could not read i.MX CPU UID: {e}")
        return ""


def read_device_identity(api=None, min_probe_interval: float = 5.0) -> str:
    """
    Return the stable device ID used for discovery and command targeting.

    Prefer the i.MX CPU UID. Only fall back to pShell device identifiers when
    the CPU UID cannot be read.
    """
    global device_id_cache, device_id_last_probe

    if _valid_identity(device_id_cache):
        return device_id_cache

    cpu_uid = read_imx_cpu_uid()
    if _valid_identity(cpu_uid):
        device_id_cache = cpu_uid
        return cpu_uid

    now = time.time()
    if device_id_last_probe and now - device_id_last_probe < min_probe_interval:
        return ""
    device_id_last_probe = now

    if api is None:
        api = get_popoto_api()

    if api is not None:
        for command in ("DeviceID", "UniqueID", "LocalID"):
            try:
                with popoto_api_lock:
                    api.drainReplyQquiet()
                    api.get(command)
                    deadline = time.time() + 3
                    while time.time() < deadline:
                        reply = api.waitForReply(1)
                        identity = _device_id_from_reply(reply)
                        if identity:
                            device_id_cache = identity
                            return identity
            except Exception as e:
                logger.debug(f"Could not query {command} for device identity: {e}")

    return ""


def read_pshell_serial(api=None, min_probe_interval: float = 5.0) -> str:
    """
    Return the cached pShell/manufacturing serial number.

    The serial is emitted as an Info message by getVersion(). Do not query
    SerialNumber or Serial as pShell elements here; those are invalid elements
    on current firmware and produce UNKNOWN ELEMENT noise.
    """
    return read_cached_version_info()[1]


def _parse_busctl_string(output: str) -> str:
    match = re.match(r'\s*s\s+"(.*)"\s*$', output.strip())
    if not match:
        return ""
    return bytes(match.group(1), "utf-8").decode("unicode_escape")


def get_mdns_hostname() -> str:
    """
    Return Avahi's negotiated mDNS hostname, without .local.

    When multiple freshly provisioned modems have the same static hostname,
    Avahi resolves the conflict by advertising names like pmm, pmm-2, pmm-3.
    That negotiated name is a better discovery fallback than /etc/hostname.
    """
    global mdns_identity_cache

    if _valid_identity(mdns_identity_cache):
        return mdns_identity_cache

    try:
        result = subprocess.run(
            [
                "busctl",
                "call",
                "org.freedesktop.Avahi",
                "/",
                "org.freedesktop.Avahi.Server",
                "GetHostName",
            ],
            capture_output=True,
            text=True,
            timeout=2,
        )
        if result.returncode == 0:
            value = _parse_busctl_string(result.stdout)
            if _valid_identity(value):
                mdns_identity_cache = value
                return value
    except Exception as e:
        logger.debug(f"Could not query Avahi mDNS hostname: {e}")

    return ""


def read_fallback_identity() -> str:
    mdns_hostname = get_mdns_hostname()
    if _valid_identity(mdns_hostname):
        return mdns_hostname

    try:
        if os.path.exists("/etc/machine-id"):
            with open("/etc/machine-id", 'r') as f:
                value = f.read().strip()
            if _valid_identity(value):
                return value
    except Exception as e:
        logger.warning(f"Could not read fallback machine-id: {e}")

    return get_hostname()


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


def get_discover_client_version() -> str:
    try:
        with open(DISCOVER_CLIENT_VERSION_FILE, "r", encoding="utf-8") as f:
            version = f.read().strip()
        return version or "unknown"
    except Exception:
        return "unknown"


def build_discovery_reply(nonce: str, interface: str, model: str, serial: str,
                          mac: str, fw: str, name: str, secret: Optional[str]):
    maybe_refresh_version_cache(min_probe_interval=10.0)
    cached_fw, cached_serial = read_cached_version_info()
    if _valid_identity(cached_fw):
        fw = cached_fw

    mdns_hostname = get_mdns_hostname()
    cpu_uid = read_imx_cpu_uid()
    device_id = read_device_identity(min_probe_interval=5.0)
    identity_source = "cpu_uid" if _valid_identity(cpu_uid) and device_id == cpu_uid else "device_id"
    if not _valid_identity(device_id):
        device_id = read_fallback_identity()
        identity_source = "mdns" if device_id == mdns_hostname else "fallback"

    reply_serial = cached_serial
    if not _valid_identity(reply_serial) and _valid_identity(serial):
        reply_serial = serial
    if not _valid_identity(reply_serial):
        reply_serial = "unknown"

    if _valid_identity(device_id):
        reply_name = f"{model}-{device_id}"
    else:
        reply_name = name

    ip = get_ip_address(interface)
    st = get_status()
    hostname = get_hostname()
    netmask, gateway = get_network_config(interface)

    reply = {
        "cmd": protocol.MSG_DISCOVER_REPLY,
        "nonce": nonce,
        "model": model,
        "serial": reply_serial,
        "device_id": device_id,
        "cpu_uid": cpu_uid,
        "ip": ip,
        "mac": mac,
        "fw": fw,
        "http_port": 80,
        "name": reply_name,
        "hostname": hostname,
        "mdns_hostname": mdns_hostname,
        "identity_source": identity_source,
        "netmask": netmask,
        "gateway": gateway,
        "discover_client_version": get_discover_client_version(),
        **st
    }

    if secret:
        reply = protocol.add_auth(reply, secret)
    return reply


def current_target_identity(startup_serial: str) -> str:
    """
    Return the same live identity used in discovery replies for targeted commands.

    Command targeting uses the CPU UID/device ID, not the pShell serial number.
    """
    identity = read_device_identity(min_probe_interval=0.0)
    return identity if _valid_identity(identity) else startup_serial


def build_status_reply(reply_cmd: str, nonce: str, success: bool, error_msg: str, secret: Optional[str]):
    reply = {
        "cmd": reply_cmd,
        "nonce": nonce,
        "status": "ok" if success else "error",
    }
    if not success:
        reply["error"] = error_msg
    if secret:
        reply = protocol.add_auth(reply, secret)
    return reply


def build_shell_exec_reply(
    nonce: str,
    success: bool,
    error_msg: str,
    returncode: int,
    stdout: str,
    stderr: str,
    secret: Optional[str],
):
    reply = {
        "cmd": protocol.MSG_SHELL_EXEC_REPLY,
        "nonce": nonce,
        "status": "ok" if success else "error",
        "returncode": returncode,
        "stdout": stdout,
        "stderr": stderr,
    }
    if not success:
        reply["error"] = error_msg
    if secret:
        reply = protocol.add_auth(reply, secret)
    return reply


def handle_system_command(msg, secret: Optional[str], mac: str, serial: str, send_reply) -> bool:
    cmd = msg.get("cmd", "")
    nonce = msg.get("nonce", "")

    if cmd == protocol.MSG_SET_UBOOT_ENV:
        try:
            protocol.validate_set_uboot_env_request(msg)
        except protocol.ValidationError as e:
            logger.warning(f"Invalid set_uboot_env request: {e}")
            return True

        if not protocol.target_matches(msg, mac, current_target_identity(serial)):
            logger.debug("Ignoring set_uboot_env for different target")
            return True

        name = str(msg.get("name"))
        value = str(msg.get("value"))
        success, error_msg = set_uboot_env(name, value)
        send_reply(build_status_reply(protocol.MSG_SET_UBOOT_ENV_REPLY, nonce, success, error_msg, secret))
        logger.info(f"Sent set_uboot_env reply: status={'ok' if success else 'error'}")
        return True

    if cmd == protocol.MSG_REBOOT:
        try:
            protocol.validate_reboot_request(msg)
        except protocol.ValidationError as e:
            logger.warning(f"Invalid reboot request: {e}")
            return True

        if not protocol.target_matches(msg, mac, current_target_identity(serial)):
            logger.debug("Ignoring reboot for different target")
            return True

        success, error_msg = schedule_reboot()
        send_reply(build_status_reply(protocol.MSG_REBOOT_REPLY, nonce, success, error_msg, secret))
        logger.info(f"Sent reboot reply: status={'ok' if success else 'error'}")
        return True

    if cmd == protocol.MSG_SHELL_EXEC:
        try:
            protocol.validate_shell_exec_request(msg)
        except protocol.ValidationError as e:
            logger.warning(f"Invalid shell_exec request: {e}")
            return True

        if not protocol.target_matches(msg, mac, current_target_identity(serial)):
            logger.debug("Ignoring shell_exec for different target")
            return True

        command = str(msg.get("command"))
        timeout_seconds = float(msg.get("timeout_seconds", protocol.DEFAULT_TIMEOUT))
        logger.warning(f"Running authenticated shell command: {command}")
        success, error_msg, returncode, stdout, stderr = run_shell_command(command, timeout_seconds)
        send_reply(build_shell_exec_reply(nonce, success, error_msg, returncode, stdout, stderr, secret))
        logger.info(f"Sent shell_exec reply: status={'ok' if success else 'error'} returncode={returncode}")
        return True

    return False


def start_l2_discovery(interface: str, secret: Optional[str], model: str, serial: str,
                       mac: str, fw: str, name: str) -> None:
    try:
        transport = l2_transport.open_transport(interface, 0.25)
    except Exception as e:
        logger.warning(f"Raw Ethernet discovery disabled on {interface}: {e}")
        return

    def loop() -> None:
        logger.info(f"Listening for raw Ethernet discovery on {interface}")
        while True:
            try:
                packet = transport.recv_json(0.25)
                if packet is None:
                    continue
                msg = packet.message
                cmd = msg.get("cmd")

                if cmd == protocol.MSG_DISCOVER:
                    try:
                        protocol.validate_discover_request(msg)
                    except protocol.ValidationError as e:
                        logger.warning(f"Invalid raw Ethernet discovery request on {interface}: {e}")
                        continue

                    if protocol.AUTH_ENABLED:
                        try:
                            if not protocol.verify_auth(msg, secret):
                                logger.warning(f"Authentication failed for raw Ethernet discovery on {interface}")
                                continue
                        except protocol.AuthenticationError as e:
                            logger.warning(f"Authentication error for raw Ethernet discovery on {interface}: {e}")
                            continue

                    # Spread responses out a little so 12 devices do not all answer at the same instant.
                    time.sleep(random.uniform(0.0, 0.150))
                    reply = build_discovery_reply(
                        msg.get("nonce", ""),
                        interface,
                        model,
                        serial,
                        mac,
                        fw,
                        name,
                        secret,
                    )
                    transport.send_json(packet.src, reply)
                    logger.info(
                        f"Sent raw Ethernet discovery reply on {interface} to "
                        f"{l2_transport.mac_to_text(packet.src)}"
                    )
                    continue

                if cmd in (protocol.MSG_SET_UBOOT_ENV, protocol.MSG_REBOOT, protocol.MSG_SHELL_EXEC):
                    if protocol.AUTH_ENABLED:
                        try:
                            if not protocol.verify_auth(msg, secret):
                                logger.warning(f"Authentication failed for raw Ethernet command on {interface}")
                                continue
                        except protocol.AuthenticationError as e:
                            logger.warning(f"Authentication error for raw Ethernet command on {interface}: {e}")
                            continue
                    handle_system_command(
                        msg,
                        secret,
                        mac,
                        serial,
                        lambda reply: transport.send_json(packet.src, reply),
                    )
                    continue
            except Exception as e:
                logger.error(f"Error in raw Ethernet discovery loop on {interface}: {e}", exc_info=True)
                time.sleep(0.1)

    thread = threading.Thread(target=loop, name=f"l2-discovery-{interface}", daemon=True)
    thread.start()


def udp_reply_address(msg, addr: Tuple[str, int]) -> Tuple[str, int]:
    if msg.get("reply_broadcast") is True:
        return (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT)

    return addr


def send_udp_reply(sock: socket.socket, msg, reply, addr: Tuple[str, int], description: str) -> None:
    dest = udp_reply_address(msg, addr)
    sock.sendto(json.dumps(reply).encode("utf-8"), dest)
    logger.info(f"Sent {description} reply to {dest}")


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
        l2_interfaces = l2_transport.candidate_interfaces()
        if l2_interfaces:
            interface = l2_interfaces[0]
            logger.warning(f"No IPv4 interface found; using {interface} for raw Ethernet discovery")
        else:
            logger.warning("No network interface found! Using placeholder values.")
            interface = "unknown"
        mac = get_mac_address(interface) if interface != "unknown" else None
        if not mac:
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

    # Get version and pShell serial number from device.
    success, fw, serial, error_msg = get_version()
    if not success:
        logger.warning(f"Could not get version from device: {error_msg}")
        fw = "unknown"
        serial = "unknown"
    elif not _valid_identity(serial):
        serial = "unknown"

    # Device ID is the discovery/targeting identity. Serial remains the pShell
    # manufacturing serial and may legitimately be unknown.
    device_id = read_device_identity(min_probe_interval=0.0)
    if not _valid_identity(device_id):
        device_id = read_fallback_identity()
        logger.warning(f"No CPU UID/device ID found, using discovery fallback: {device_id}")

    name = f"{model}-{device_id}"

    logger.info(f"Device: {name}, Model: {model}, Firmware: {fw}, Serial: {serial}, Device ID: {device_id}")

    if interface != "unknown":
        start_l2_discovery(interface, secret, model, serial, mac, fw, name)

    # Create and bind socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
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

                time.sleep(random.uniform(0.0, 0.150))
                reply = build_discovery_reply(nonce, interface, model, serial, mac, fw, name, secret)

                try:
                    send_udp_reply(sock, msg, reply, addr, "discovery")
                except Exception as e:
                    logger.error(f"Failed to send discovery reply to {addr}: {e}")

            # Handle set IP request
            elif cmd == protocol.MSG_SET_IP:
                try:
                    protocol.validate_set_ip_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid set_ip request from {addr}: {e}")
                    continue

                # Only respond if the device ID or fallback MAC matches.
                if not protocol.target_matches(msg, mac, current_target_identity(serial)):
                    logger.debug("Ignoring set_ip for different target")
                    continue

                logger.warning(f"Received IP configuration request from {addr}")

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
                    send_udp_reply(sock, msg, reply, addr, "set_ip")
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

                # Only respond if the device ID or fallback MAC matches.
                if not protocol.target_matches(msg, mac, current_target_identity(serial)):
                    logger.debug("Ignoring set_rtc for different target")
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
                    send_udp_reply(sock, msg, reply, addr, "set_rtc")
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

                # Only respond if the device ID or fallback MAC matches.
                if not protocol.target_matches(msg, mac, current_target_identity(serial)):
                    logger.debug("Ignoring get_rtc for different target")
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
                    send_udp_reply(sock, msg, reply, addr, "get_rtc")
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

                # Only respond if the device ID or fallback MAC matches.
                if not protocol.target_matches(msg, mac, current_target_identity(serial)):
                    logger.debug("Ignoring set_param for different target")
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
                    send_udp_reply(sock, msg, reply, addr, "set_param")
                    logger.info(f"Sent set_param reply to {addr}: status={reply['status']}")
                except Exception as e:
                    logger.error(f"Failed to send set_param reply to {addr}: {e}")

            # Handle U-Boot environment set request
            elif cmd == protocol.MSG_SET_UBOOT_ENV:
                def send_uboot_env_reply(reply):
                    send_udp_reply(sock, msg, reply, addr, "set_uboot_env")

                try:
                    handle_system_command(msg, secret, mac, serial, send_uboot_env_reply)
                except Exception as e:
                    logger.error(f"Failed to handle set_uboot_env from {addr}: {e}")

            # Handle reboot request
            elif cmd == protocol.MSG_REBOOT:
                def send_reboot_reply(reply):
                    send_udp_reply(sock, msg, reply, addr, "reboot")

                try:
                    handle_system_command(msg, secret, mac, serial, send_reboot_reply)
                except Exception as e:
                    logger.error(f"Failed to handle reboot from {addr}: {e}")

            # Handle shell command request
            elif cmd == protocol.MSG_SHELL_EXEC:
                def send_shell_exec_reply(reply):
                    send_udp_reply(sock, msg, reply, addr, "shell_exec")

                try:
                    handle_system_command(msg, secret, mac, serial, send_shell_exec_reply)
                except Exception as e:
                    logger.error(f"Failed to handle shell_exec from {addr}: {e}")

            # Handle get version request
            elif cmd == protocol.MSG_GET_VERSION:
                try:
                    protocol.validate_get_version_request(msg)
                except protocol.ValidationError as e:
                    logger.warning(f"Invalid get_version request from {addr}: {e}")
                    continue

                # Only respond if the device ID or fallback MAC matches.
                if not protocol.target_matches(msg, mac, current_target_identity(serial)):
                    logger.debug("Ignoring get_version for different target")
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
                    send_udp_reply(sock, msg, reply, addr, "get_version")
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
