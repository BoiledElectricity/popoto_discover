#!/usr/bin/env python3
import socket
import json
import time
import uuid
import argparse
import sys
import os
import logging

# Add parent directory to path to import common module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from common import protocol

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('popoto_host')

def discover(timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Discover popoto devices on the network via UDP broadcast.

    Args:
        timeout: How long to wait for responses (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        List of discovered device dictionaries
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(timeout)
    except Exception as e:
        logger.error(f"Failed to create socket: {e}")
        return []

    # Create discovery message
    nonce = uuid.uuid4().hex[:8]
    try:
        request = protocol.create_discover_message(nonce, secret)
    except Exception as e:
        logger.error(f"Failed to create discovery message: {e}")
        return []

    data = json.dumps(request).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent discovery broadcast (nonce={nonce})")
        print(f"Sent discovery (nonce={nonce}), waiting for replies...")
    except Exception as e:
        logger.error(f"Failed to send discovery broadcast: {e}")
        sock.close()
        return []

    # Collect responses
    found = []
    start = time.time()
    while True:
        remaining = timeout - (time.time() - start)
        if remaining <= 0:
            break

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            break
        except Exception as e:
            logger.error(f"Error receiving response: {e}")
            break

        # Parse JSON response
        try:
            j = json.loads(resp.decode("utf-8", errors="ignore"))
        except json.JSONDecodeError as e:
            logger.warning(f"Non-JSON reply from {addr}: {resp!r}")
            print(f"Non-JSON reply from {addr}")
            continue

        # Validate response type and nonce
        if j.get("cmd") != protocol.MSG_DISCOVER_REPLY:
            logger.debug(f"Ignoring non-discovery-reply from {addr}")
            continue

        # Check nonce (allow empty for backwards compatibility, but warn)
        reply_nonce = j.get("nonce", "")
        if reply_nonce and reply_nonce != nonce:
            logger.warning(f"Nonce mismatch from {addr}: expected {nonce}, got {reply_nonce}")
            continue

        # Verify authentication
        if protocol.AUTH_ENABLED and secret:
            try:
                if not protocol.verify_auth(j, secret):
                    logger.warning(f"Authentication failed for response from {addr}")
                    print(f"Warning: Authentication failed for device at {addr[0]}")
                    continue
            except protocol.AuthenticationError as e:
                logger.warning(f"Authentication error from {addr}: {e}")
                print(f"Warning: Authentication error from {addr[0]}: {e}")
                continue

        # Validate message format
        try:
            protocol.validate_discover_reply(j)
        except protocol.ValidationError as e:
            logger.warning(f"Invalid discovery reply from {addr}: {e}")
            print(f"Warning: Invalid reply from {addr[0]}: {e}")
            continue

        # Add source IP and append to results
        j["_source_ip"] = addr[0]
        found.append(j)
        logger.info(f"Discovered device: {j.get('name')} at {addr[0]} (MAC: {j.get('mac')})")

    sock.close()
    logger.info(f"Discovery complete: found {len(found)} device(s)")
    return found


def set_ip(target_mac, new_ip, netmask, gateway, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set IP address on a specific popoto device by MAC address.

    Args:
        target_mac: Target device MAC address
        new_ip: New IP address to set
        netmask: Network mask
        gateway: Gateway address
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        if not protocol.validate_mac_address(target_mac):
            print(f"Error: Invalid MAC address: {target_mac}")
            logger.error(f"Invalid MAC address: {target_mac}")
            return None
        if not protocol.validate_ip_address(new_ip):
            print(f"Error: Invalid IP address: {new_ip}")
            logger.error(f"Invalid IP address: {new_ip}")
            return None
        if not protocol.validate_netmask(netmask):
            print(f"Error: Invalid netmask: {netmask}")
            logger.error(f"Invalid netmask: {netmask}")
            return None
        if not protocol.validate_ip_address(gateway):
            print(f"Error: Invalid gateway address: {gateway}")
            logger.error(f"Invalid gateway address: {gateway}")
            return None
    except Exception as e:
        print(f"Error: Validation failed: {e}")
        logger.error(f"Validation error: {e}")
        return None

    # Create socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    except Exception as e:
        print(f"Error: Failed to create socket: {e}")
        logger.error(f"Failed to create socket: {e}")
        return None

    # Create set_ip message
    nonce = uuid.uuid4().hex[:8]
    try:
        req = protocol.create_set_ip_message(nonce, target_mac, new_ip, netmask, gateway, secret)
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create set_ip message: {e}")
        sock.close()
        return None
    except Exception as e:
        print(f"Error: Failed to create message: {e}")
        logger.error(f"Failed to create set_ip message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent set_ip to MAC {target_mac}")
        print(f"Sent set_ip to MAC {target_mac}, waiting for reply...")
    except Exception as e:
        print(f"Error: Failed to send set_ip request: {e}")
        logger.error(f"Failed to send set_ip request: {e}")
        sock.close()
        return None

    # Wait for response
    start = time.time()
    while True:
        remaining = timeout - (time.time() - start)
        if remaining <= 0:
            print("No set_ip_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_ip_reply from {target_mac}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_ip_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_ip_reply from {target_mac}")
            sock.close()
            return None
        except Exception as e:
            print(f"Error receiving response: {e}")
            logger.error(f"Error receiving set_ip_reply: {e}")
            sock.close()
            return None

        # Parse response
        try:
            j = json.loads(resp.decode("utf-8", errors="ignore"))
        except json.JSONDecodeError as e:
            logger.warning(f"Non-JSON reply from {addr}")
            continue

        # Validate response
        if j.get("cmd") != protocol.MSG_SET_IP_REPLY:
            logger.debug(f"Ignoring non-set-ip-reply from {addr}")
            continue

        if j.get("nonce") != nonce:
            logger.warning(f"Nonce mismatch from {addr}: expected {nonce}, got {j.get('nonce')}")
            continue

        # Verify authentication
        if protocol.AUTH_ENABLED and secret:
            try:
                if not protocol.verify_auth(j, secret):
                    print(f"Warning: Authentication failed for response from {addr[0]}")
                    logger.warning(f"Authentication failed for set_ip_reply from {addr}")
                    continue
            except protocol.AuthenticationError as e:
                print(f"Warning: Authentication error from {addr[0]}: {e}")
                logger.warning(f"Authentication error from {addr}: {e}")
                continue

        # Validate message format
        try:
            protocol.validate_set_ip_reply(j)
        except protocol.ValidationError as e:
            print(f"Warning: Invalid reply from {addr[0]}: {e}")
            logger.warning(f"Invalid set_ip_reply from {addr}: {e}")
            continue

        # Success
        j["_source_ip"] = addr[0]
        logger.info(f"Received set_ip_reply from {addr[0]}: status={j.get('status')}")
        sock.close()
        return j


def set_rtc(target_mac, rtc, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set real-time clock on a specific popoto device by MAC address.

    Args:
        target_mac: Target device MAC address
        rtc: RTC string in format YYYY.MM.DD-HH:MM:SS
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        if not protocol.validate_mac_address(target_mac):
            print(f"Error: Invalid MAC address: {target_mac}")
            logger.error(f"Invalid MAC address: {target_mac}")
            return None
        if not protocol.validate_rtc_format(rtc):
            print(f"Error: Invalid RTC format: {rtc}")
            logger.error(f"Invalid RTC format: {rtc}")
            return None
    except Exception as e:
        print(f"Error: Validation failed: {e}")
        logger.error(f"Validation error: {e}")
        return None

    # Create socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    except Exception as e:
        print(f"Error: Failed to create socket: {e}")
        logger.error(f"Failed to create socket: {e}")
        return None

    # Create set_rtc message
    nonce = uuid.uuid4().hex[:8]
    try:
        req = protocol.create_set_rtc_message(nonce, target_mac, rtc, secret)
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create set_rtc message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent set_rtc to MAC {target_mac}")
        print(f"Sent set_rtc to MAC {target_mac}, waiting for reply...")
    except Exception as e:
        print(f"Error: Failed to send set_rtc request: {e}")
        logger.error(f"Failed to send set_rtc request: {e}")
        sock.close()
        return None

    # Wait for response (similar to set_ip)
    start = time.time()
    while True:
        remaining = timeout - (time.time() - start)
        if remaining <= 0:
            print("No set_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_rtc_reply from {target_mac}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_rtc_reply from {target_mac}")
            sock.close()
            return None

        try:
            j = json.loads(resp.decode("utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue

        if j.get("cmd") != protocol.MSG_SET_RTC_REPLY:
            continue
        if j.get("nonce") != nonce:
            continue

        # Verify authentication
        if protocol.AUTH_ENABLED and secret:
            try:
                if not protocol.verify_auth(j, secret):
                    logger.warning(f"Authentication failed for set_rtc_reply from {addr}")
                    continue
            except protocol.AuthenticationError as e:
                logger.warning(f"Authentication error from {addr}: {e}")
                continue

        j["_source_ip"] = addr[0]
        logger.info(f"Received set_rtc_reply from {addr[0]}: status={j.get('status')}")
        sock.close()
        return j


def get_rtc(target_mac, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Get real-time clock from a specific popoto device by MAC address.

    Args:
        target_mac: Target device MAC address
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        if not protocol.validate_mac_address(target_mac):
            print(f"Error: Invalid MAC address: {target_mac}")
            logger.error(f"Invalid MAC address: {target_mac}")
            return None
    except Exception as e:
        print(f"Error: Validation failed: {e}")
        logger.error(f"Validation error: {e}")
        return None

    # Create socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    except Exception as e:
        print(f"Error: Failed to create socket: {e}")
        logger.error(f"Failed to create socket: {e}")
        return None

    # Create get_rtc message
    nonce = uuid.uuid4().hex[:8]
    try:
        req = protocol.create_get_rtc_message(nonce, target_mac, secret)
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create get_rtc message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent get_rtc to MAC {target_mac}")
        print(f"Sent get_rtc to MAC {target_mac}, waiting for reply...")
    except Exception as e:
        print(f"Error: Failed to send get_rtc request: {e}")
        logger.error(f"Failed to send get_rtc request: {e}")
        sock.close()
        return None

    # Wait for response
    start = time.time()
    while True:
        remaining = timeout - (time.time() - start)
        if remaining <= 0:
            print("No get_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for get_rtc_reply from {target_mac}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No get_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for get_rtc_reply from {target_mac}")
            sock.close()
            return None

        try:
            j = json.loads(resp.decode("utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue

        if j.get("cmd") != protocol.MSG_GET_RTC_REPLY:
            continue
        if j.get("nonce") != nonce:
            continue

        # Verify authentication
        if protocol.AUTH_ENABLED and secret:
            try:
                if not protocol.verify_auth(j, secret):
                    logger.warning(f"Authentication failed for get_rtc_reply from {addr}")
                    continue
            except protocol.AuthenticationError as e:
                logger.warning(f"Authentication error from {addr}: {e}")
                continue

        j["_source_ip"] = addr[0]
        logger.info(f"Received get_rtc_reply from {addr[0]}: status={j.get('status')}")
        sock.close()
        return j


def set_param(target_mac, param_name, param_value, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set a popoto parameter on a specific device by MAC address.

    Args:
        target_mac: Target device MAC address
        param_name: Name of the parameter
        param_value: Value to set
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        if not protocol.validate_mac_address(target_mac):
            print(f"Error: Invalid MAC address: {target_mac}")
            logger.error(f"Invalid MAC address: {target_mac}")
            return None

        # Try to convert param_value to int or float
        try:
            if '.' in param_value:
                param_value = float(param_value)
            else:
                param_value = int(param_value)
        except ValueError:
            print(f"Error: Parameter value must be a number: {param_value}")
            logger.error(f"Invalid parameter value: {param_value}")
            return None

    except Exception as e:
        print(f"Error: Validation failed: {e}")
        logger.error(f"Validation error: {e}")
        return None

    # Create socket
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    except Exception as e:
        print(f"Error: Failed to create socket: {e}")
        logger.error(f"Failed to create socket: {e}")
        return None

    # Create set_param message
    nonce = uuid.uuid4().hex[:8]
    try:
        req = protocol.create_set_param_message(nonce, target_mac, param_name, param_value, secret)
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create set_param message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent set_param to MAC {target_mac}")
        print(f"Sent set_param to MAC {target_mac}, waiting for reply...")
    except Exception as e:
        print(f"Error: Failed to send set_param request: {e}")
        logger.error(f"Failed to send set_param request: {e}")
        sock.close()
        return None

    # Wait for response
    start = time.time()
    while True:
        remaining = timeout - (time.time() - start)
        if remaining <= 0:
            print("No set_param_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_param_reply from {target_mac}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_param_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_param_reply from {target_mac}")
            sock.close()
            return None

        try:
            j = json.loads(resp.decode("utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue

        if j.get("cmd") != protocol.MSG_SET_PARAM_REPLY:
            continue
        if j.get("nonce") != nonce:
            continue

        # Verify authentication
        if protocol.AUTH_ENABLED and secret:
            try:
                if not protocol.verify_auth(j, secret):
                    logger.warning(f"Authentication failed for set_param_reply from {addr}")
                    continue
            except protocol.AuthenticationError as e:
                logger.warning(f"Authentication error from {addr}: {e}")
                continue

        j["_source_ip"] = addr[0]
        logger.info(f"Received set_param_reply from {addr[0]}: status={j.get('status')}")
        sock.close()
        return j


def main():
    """Main CLI entry point."""
    p = argparse.ArgumentParser(
        description="Popoto Discovery Tool - Discover and manage popoto devices on the network"
    )
    p.add_argument(
        "--secret-file",
        default=protocol.DEFAULT_SECRET_FILE,
        help=f"Path to shared secret file (default: {protocol.DEFAULT_SECRET_FILE})"
    )
    p.add_argument(
        "--no-auth",
        action="store_true",
        help="Disable authentication (WARNING: insecure!)"
    )

    sub = p.add_subparsers(dest="cmd")

    d = sub.add_parser("discover", help="discover hydrophones on the LAN")
    d.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Discovery timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    s = sub.add_parser("set-ip", help="set IP on a hydrophone by MAC")
    s.add_argument("mac", help="target MAC address (e.g., 00:11:22:33:44:55)")
    s.add_argument("ip", help="new IP address")
    s.add_argument("netmask", help="netmask, e.g. 255.255.255.0")
    s.add_argument("gateway", help="gateway IP")
    s.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    r = sub.add_parser("set-rtc", help="set real-time clock on a hydrophone by MAC")
    r.add_argument("mac", help="target MAC address (e.g., 00:11:22:33:44:55)")
    r.add_argument("rtc", help="RTC string in format YYYY.MM.DD-HH:MM:SS")
    r.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    g = sub.add_parser("get-rtc", help="get real-time clock from a hydrophone by MAC")
    g.add_argument("mac", help="target MAC address (e.g., 00:11:22:33:44:55)")
    g.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    p_param = sub.add_parser("set-param", help="set a popoto parameter on a hydrophone by MAC")
    p_param.add_argument("mac", help="target MAC address (e.g., 00:11:22:33:44:55)")
    p_param.add_argument("param_name", help="parameter name (e.g., TxPowerWatts)")
    p_param.add_argument("param_value", help="parameter value (int or float)")
    p_param.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                         help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    args = p.parse_args()

    # Load shared secret
    secret = None
    if protocol.AUTH_ENABLED and not args.no_auth:
        try:
            secret = protocol.load_shared_secret(args.secret_file)
            logger.info(f"Loaded shared secret from {args.secret_file}")
        except FileNotFoundError as e:
            print(f"Error: {e}")
            print("\nAuthentication is enabled but no secret file found.")
            print("Options:")
            print(f"  1. Create a secret file: python3 -c \"import secrets; print(secrets.token_hex(32))\" > {args.secret_file}")
            print(f"  2. Specify a different file with --secret-file")
            print("  3. Disable authentication with --no-auth (NOT RECOMMENDED)")
            sys.exit(1)
        except ValueError as e:
            print(f"Error: Invalid secret file: {e}")
            sys.exit(1)
    elif args.no_auth:
        logger.warning("Authentication disabled by user (--no-auth)")
        print("WARNING: Running without authentication is insecure!")
    else:
        logger.warning("Authentication is disabled in protocol.py")

    # Execute command
    if args.cmd == "discover":
        devices = discover(timeout=args.timeout, secret=secret)
        if not devices:
            print("No hydrophones discovered.")
            return
        print(f"\nDiscovered {len(devices)} device(s):")
        for d in devices:
            ip = d.get("ip") or d.get("_source_ip")
            port = d.get("http_port", 80)
            url = f"http://{ip}:{port}/" if port != 80 else f"http://{ip}/"

            print("----")
            print(f" Name:            {d.get('name')}")
            print(f" Model:           {d.get('model')}")
            print(f" Serial:          {d.get('serial')}")
            print(f" IP:              {ip}")
            print(f" MAC:             {d.get('mac')}")
            print(f" FW:              {d.get('fw')}")
            print(f" Battery [V]:     {d.get('battery_v')}")
            print(f" Sample Rate [Hz]:{d.get('sample_rate_hz')}")
            print(f" Recording state: {d.get('recording_state')}")
            print(f" Storage Free [G]:{d.get('storage_free_gb')}")
            print(f" Storage Total[G]:{d.get('storage_total_gb')}")
            print(f" URL:             {url}")

    elif args.cmd == "set-ip":
        resp = set_ip(args.mac, args.ip, args.netmask, args.gateway,
                     timeout=args.timeout, secret=secret)
        if not resp:
            sys.exit(1)
        if resp.get("status") == "ok":
            print(f"IP set successfully to {resp.get('ip')} (reply from {resp.get('_source_ip')})")
        else:
            error_msg = resp.get("error", "Unknown error")
            print(f"Failed to set IP: {error_msg}")
            sys.exit(1)

    elif args.cmd == "set-rtc":
        resp = set_rtc(args.mac, args.rtc, timeout=args.timeout, secret=secret)
        if not resp:
            sys.exit(1)
        if resp.get("status") == "ok":
            print(f"RTC set successfully to {args.rtc} (reply from {resp.get('_source_ip')})")
        else:
            error_msg = resp.get("error", "Unknown error")
            print(f"Failed to set RTC: {error_msg}")
            sys.exit(1)

    elif args.cmd == "get-rtc":
        resp = get_rtc(args.mac, timeout=args.timeout, secret=secret)
        if not resp:
            sys.exit(1)
        if resp.get("status") == "ok":
            rtc_value = resp.get("rtc", "Unknown")
            print(f"RTC value: {rtc_value} (reply from {resp.get('_source_ip')})")
        else:
            error_msg = resp.get("error", "Unknown error")
            print(f"Failed to get RTC: {error_msg}")
            sys.exit(1)

    elif args.cmd == "set-param":
        resp = set_param(args.mac, args.param_name, args.param_value,
                        timeout=args.timeout, secret=secret)
        if not resp:
            sys.exit(1)
        if resp.get("status") == "ok":
            print(f"Parameter {args.param_name} set successfully to {args.param_value} (reply from {resp.get('_source_ip')})")
        else:
            error_msg = resp.get("error", "Unknown error")
            print(f"Failed to set parameter: {error_msg}")
            sys.exit(1)

    else:
        p.print_help()


if __name__ == "__main__":
    main()
