#!/usr/bin/env python3
import socket
import json
import time
import uuid
import argparse
import sys
import os
import logging
import select
from typing import Any, Dict, List, Optional, Tuple

# Add parent directory to path to import common module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from common import protocol
from common import l2_transport

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('popoto_host')


def _device_identity(device: Dict[str, Any]) -> Optional[Tuple[str, str]]:
    for field in ("device_id", "serial"):
        value = str(device.get(field) or "").strip()
        if value and value.lower() not in ("unknown", "none"):
            if not value.upper().startswith("UNKNOWN-"):
                return field, value.lower()

    for field in ("hostname", "name"):
        value = str(device.get(field) or "").strip()
        if value and value.lower() not in ("unknown", "none"):
            return field, value.lower()

    mac = str(device.get("mac") or "").strip().lower()
    if mac and mac != "00:00:00:00:00:00":
        return "mac", mac

    return None


def _merge_device(found: List[Dict[str, Any]], by_key: Dict[Tuple[str, str], Dict[str, Any]],
                  device: Dict[str, Any]) -> None:
    key = _device_identity(device)
    if key is None:
        found.append(device)
        return

    existing = by_key.get(key)
    if existing is None:
        by_key[key] = device
        found.append(device)
        return

    paths = existing.setdefault("_paths", [])
    for path in device.get("_paths", []):
        if path not in paths:
            paths.append(path)

    for field in ("_source_ip", "_source_mac", "_interface", "_transport"):
        if not existing.get(field) and device.get(field):
            existing[field] = device[field]


def _broadcast_targets(interfaces: Optional[List[str]] = None) -> List[Tuple[str, int]]:
    targets = {(protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT)}
    try:
        import netifaces

        wanted = set(interfaces or [])
        for iface in netifaces.interfaces():
            if wanted and iface not in wanted:
                continue
            addrs = netifaces.ifaddresses(iface).get(netifaces.AF_INET, [])
            for addr in addrs:
                broadcast = addr.get("broadcast")
                if broadcast:
                    targets.add((broadcast, protocol.DISCOVERY_PORT))
    except Exception as e:
        logger.debug(f"Could not enumerate interface broadcast addresses: {e}")

    return sorted(targets)


def _accept_discover_reply(message: Dict[str, Any], nonce: str, secret: Optional[str],
                           path: Dict[str, str]) -> Optional[Dict[str, Any]]:
    if message.get("cmd") != protocol.MSG_DISCOVER_REPLY:
        return None

    reply_nonce = message.get("nonce", "")
    if reply_nonce and reply_nonce != nonce:
        logger.warning(f"Nonce mismatch: expected {nonce}, got {reply_nonce}")
        return None

    if protocol.AUTH_ENABLED and secret:
        try:
            if not protocol.verify_auth(message, secret):
                logger.warning("Authentication failed for discovery response")
                return None
        except protocol.AuthenticationError as e:
            logger.warning(f"Authentication error for discovery response: {e}")
            return None

    try:
        protocol.validate_discover_reply(message)
    except protocol.ValidationError as e:
        logger.warning(f"Invalid discovery reply: {e}")
        return None

    device = dict(message)
    device["_paths"] = [path]
    device["_transport"] = path.get("transport")
    if "source_ip" in path:
        device["_source_ip"] = path["source_ip"]
    if "source_mac" in path:
        device["_source_mac"] = path["source_mac"]
    if "interface" in path:
        device["_interface"] = path["interface"]

    return device


def _open_l2_transports(interfaces: Optional[List[str]], timeout: float) -> List[l2_transport.EthernetTransport]:
    names = interfaces or l2_transport.candidate_interfaces()
    transports = []
    for iface in names:
        try:
            transports.append(l2_transport.open_transport(iface, timeout))
            logger.info(f"Enabled raw Ethernet discovery on {iface}")
        except Exception as e:
            logger.debug(f"Raw Ethernet discovery unavailable on {iface}: {e}")
    return transports


def _split_target_selector(target: str) -> Tuple[Optional[str], Optional[str], str]:
    target = str(target or "").strip()
    if not target:
        raise ValueError("empty target")
    return None, target, target


def discover(timeout=protocol.DEFAULT_TIMEOUT, secret=None, transport="auto",
             interfaces=None, retries=3):
    """
    Discover popoto devices via UDP broadcast and raw Ethernet.

    Args:
        timeout: How long to wait for responses (seconds)
        secret: Shared secret for authentication (optional)
        transport: auto, udp, l2, or all
        interfaces: Optional interface names for raw Ethernet and directed UDP broadcasts
        retries: Number of probe bursts to send during the timeout

    Returns:
        List of discovered device dictionaries
    """
    interfaces = interfaces or None
    retries = max(1, retries)

    nonce = uuid.uuid4().hex[:8]
    try:
        request = protocol.create_discover_message(nonce, secret)
    except Exception as e:
        logger.error(f"Failed to create discovery message: {e}")
        return []

    data = json.dumps(request).encode("utf-8")
    use_udp = transport in ("auto", "all", "udp")
    use_l2 = transport in ("auto", "all", "l2")

    udp_sock = None
    udp_targets = []
    if use_udp:
        try:
            udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            udp_sock.setblocking(False)
            udp_targets = _broadcast_targets(interfaces)
        except Exception as e:
            logger.warning(f"UDP discovery unavailable: {e}")
            udp_sock = None

    l2_transports = _open_l2_transports(interfaces, timeout) if use_l2 else []
    if not udp_sock and not l2_transports:
        logger.error("No discovery transports are available")
        return []

    found = []
    by_key = {}
    deadline = time.monotonic() + timeout
    send_count = 0
    next_send = 0.0
    interval = max(0.2, min(0.5, timeout / retries))

    transports_used = []
    if udp_sock:
        transports_used.append("UDP")
    if l2_transports:
        transports_used.append("raw Ethernet")
    print(f"Sent discovery (nonce={nonce}) over {', '.join(transports_used)}, waiting for replies...")

    while time.monotonic() < deadline:
        now = time.monotonic()
        if send_count < retries and now >= next_send:
            if udp_sock:
                for target in udp_targets:
                    try:
                        udp_sock.sendto(data, target)
                    except Exception as e:
                        logger.debug(f"Failed to send UDP discovery to {target}: {e}")
            for l2 in l2_transports:
                try:
                    l2.send_json(l2_transport.ETH_BROADCAST, request)
                except Exception as e:
                    logger.debug(f"Failed to send raw Ethernet discovery on {l2.interface}: {e}")

            send_count += 1
            next_send = now + interval

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break

        fd_map = {}
        if udp_sock:
            fd_map[udp_sock.fileno()] = ("udp", udp_sock)
        for l2 in l2_transports:
            fd = l2.fileno()
            if fd is not None:
                fd_map[fd] = ("l2", l2)

        wait = min(0.05, remaining, max(next_send - time.monotonic(), 0.0) if send_count < retries else remaining)
        readable = []
        if fd_map:
            try:
                readable, _, _ = select.select(list(fd_map.keys()), [], [], wait)
            except Exception as e:
                logger.debug(f"Discovery select failed: {e}")
                time.sleep(wait)
        else:
            time.sleep(wait)

        for fd in readable:
            kind, obj = fd_map[fd]
            if kind == "udp":
                while True:
                    try:
                        resp, addr = obj.recvfrom(4096)
                    except BlockingIOError:
                        break
                    except Exception as e:
                        logger.debug(f"Error receiving UDP discovery response: {e}")
                        break
                    try:
                        message = json.loads(resp.decode("utf-8", errors="ignore"))
                    except json.JSONDecodeError:
                        continue
                    device = _accept_discover_reply(
                        message,
                        nonce,
                        secret,
                        {"transport": "udp", "source_ip": addr[0]},
                    )
                    if device:
                        _merge_device(found, by_key, device)
            else:
                while True:
                    try:
                        packet = obj.recv_json(0)
                    except Exception as e:
                        logger.debug(f"Raw Ethernet receive failed on {obj.interface}: {e}")
                        break
                    if packet is None:
                        break
                    device = _accept_discover_reply(
                        packet.message,
                        nonce,
                        secret,
                        {
                            "transport": "l2",
                            "interface": packet.interface,
                            "source_mac": l2_transport.mac_to_text(packet.src),
                        },
                    )
                    if device:
                        _merge_device(found, by_key, device)

        for l2 in l2_transports:
            if l2.fileno() is not None:
                continue
            while True:
                try:
                    packet = l2.recv_json(0)
                except Exception as e:
                    logger.debug(f"Raw Ethernet receive failed on {l2.interface}: {e}")
                    break
                if packet is None:
                    break
                device = _accept_discover_reply(
                    packet.message,
                    nonce,
                    secret,
                    {
                        "transport": "l2",
                        "interface": packet.interface,
                        "source_mac": l2_transport.mac_to_text(packet.src),
                    },
                )
                if device:
                    _merge_device(found, by_key, device)

    if udp_sock:
        udp_sock.close()
    for l2 in l2_transports:
        l2.close()

    logger.info(f"Discovery complete: found {len(found)} device(s)")
    return found


def set_ip(target_id, new_ip, netmask, gateway, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set IP address on a specific popoto device by CPU UID/device ID.

    Args:
        target_id: Target CPU UID/device ID
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
        target_id_value, target_serial, target_label = _split_target_selector(target_id)

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
        req = protocol.create_set_ip_message(
            nonce,
            target_id_value,
            new_ip,
            netmask,
            gateway,
            secret,
            target_serial=target_serial,
        )
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
        logger.info(f"Sent set_ip to {target_label}")
        print(f"Sent set_ip to {target_label}, waiting for reply...")
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
            logger.warning(f"Timeout waiting for set_ip_reply from {target_label}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_ip_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_ip_reply from {target_label}")
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


def set_rtc(target_id, rtc, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set real-time clock on a specific popoto device by MAC address.

    Args:
        target_id: Target device CPU UID/device ID
        rtc: RTC string in format YYYY.MM.DD-HH:MM:SS
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        target_id_value, target_serial, target_label = _split_target_selector(target_id)
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
        req = protocol.create_set_rtc_message(
            nonce,
            target_id_value,
            rtc,
            secret,
            target_serial=target_serial,
        )
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create set_rtc message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent set_rtc to {target_label}")
        print(f"Sent set_rtc to {target_label}, waiting for reply...")
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
            logger.warning(f"Timeout waiting for set_rtc_reply from {target_label}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_rtc_reply from {target_label}")
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


def get_rtc(target_id, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Get real-time clock from a specific popoto device by MAC address.

    Args:
        target_id: Target device CPU UID/device ID
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        target_id_value, target_serial, target_label = _split_target_selector(target_id)
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
        req = protocol.create_get_rtc_message(
            nonce,
            target_id_value,
            secret,
            target_serial=target_serial,
        )
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create get_rtc message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent get_rtc to {target_label}")
        print(f"Sent get_rtc to {target_label}, waiting for reply...")
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
            logger.warning(f"Timeout waiting for get_rtc_reply from {target_label}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No get_rtc_reply received (timeout).")
            logger.warning(f"Timeout waiting for get_rtc_reply from {target_label}")
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


def set_param(target_id, param_name, param_value, timeout=protocol.DEFAULT_TIMEOUT, secret=None):
    """
    Set a popoto parameter on a specific device by MAC address.

    Args:
        target_id: Target device CPU UID/device ID
        param_name: Name of the parameter
        param_value: Value to set
        timeout: How long to wait for response (seconds)
        secret: Shared secret for authentication (optional)

    Returns:
        Response dictionary or None if failed/timeout
    """
    # Validate inputs
    try:
        target_id_value, target_serial, target_label = _split_target_selector(target_id)

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
        req = protocol.create_set_param_message(
            nonce,
            target_id_value,
            param_name,
            param_value,
            secret,
            target_serial=target_serial,
        )
    except protocol.ValidationError as e:
        print(f"Error: {e}")
        logger.error(f"Failed to create set_param message: {e}")
        sock.close()
        return None

    data = json.dumps(req).encode("utf-8")

    # Send broadcast
    try:
        sock.sendto(data, (protocol.BROADCAST_ADDRESS, protocol.DISCOVERY_PORT))
        logger.info(f"Sent set_param to {target_label}")
        print(f"Sent set_param to {target_label}, waiting for reply...")
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
            logger.warning(f"Timeout waiting for set_param_reply from {target_label}")
            sock.close()
            return None

        try:
            resp, addr = sock.recvfrom(4096)
        except socket.timeout:
            print("No set_param_reply received (timeout).")
            logger.warning(f"Timeout waiting for set_param_reply from {target_label}")
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
    d.add_argument("--transport", choices=["auto", "udp", "l2", "all"], default="auto",
                   help="Discovery transport (default: auto = UDP plus raw Ethernet when available)")
    d.add_argument("-i", "--interface", action="append",
                   help="Network interface to probe; may be passed more than once")
    d.add_argument("--retries", type=int, default=3,
                   help="Discovery probe bursts to send during the timeout (default: 3)")

    s = sub.add_parser("set-ip", help="set IP on a hydrophone by MAC")
    s.add_argument("mac", help="target CPU UID/device ID")
    s.add_argument("ip", help="new IP address")
    s.add_argument("netmask", help="netmask, e.g. 255.255.255.0")
    s.add_argument("gateway", help="gateway IP")
    s.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    r = sub.add_parser("set-rtc", help="set real-time clock on a hydrophone by MAC")
    r.add_argument("mac", help="target CPU UID/device ID")
    r.add_argument("rtc", help="RTC string in format YYYY.MM.DD-HH:MM:SS")
    r.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    g = sub.add_parser("get-rtc", help="get real-time clock from a hydrophone by MAC")
    g.add_argument("mac", help="target CPU UID/device ID")
    g.add_argument("--timeout", type=float, default=protocol.DEFAULT_TIMEOUT,
                   help=f"Timeout in seconds (default: {protocol.DEFAULT_TIMEOUT})")

    p_param = sub.add_parser("set-param", help="set a popoto parameter on a hydrophone by MAC")
    p_param.add_argument("mac", help="target CPU UID/device ID")
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
        devices = discover(
            timeout=args.timeout,
            secret=secret,
            transport=args.transport,
            interfaces=args.interface,
            retries=args.retries,
        )
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
            print(f" mDNS Hostname:   {d.get('mdns_hostname')}")
            print(f" Identity source: {d.get('identity_source')}")
            print(f" FW:              {d.get('fw')}")
            print(f" Battery [V]:     {d.get('battery_v')}")
            print(f" Sample Rate [Hz]:{d.get('sample_rate_hz')}")
            print(f" Recording state: {d.get('recording_state')}")
            print(f" Storage Free [G]:{d.get('storage_free_gb')}")
            print(f" Storage Total[G]:{d.get('storage_total_gb')}")
            print(f" URL:             {url}")
            paths = d.get("_paths") or []
            if paths:
                path_text = ", ".join(
                    "{}{}".format(
                        p.get("transport"),
                        "@{}".format(p.get("interface")) if p.get("interface") else "",
                    )
                    for p in paths
                )
                print(f" Discovered via:  {path_text}")

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
