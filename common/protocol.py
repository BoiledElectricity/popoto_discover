"""
Popoto Discovery Protocol - Shared constants, message formats, and authentication
"""

import hashlib
import hmac
import json
import re
from typing import Dict, Any, Optional, Tuple

# Protocol Constants
DISCOVERY_PORT = 33333
PROTOCOL_VERSION = "1.0"
DEFAULT_TIMEOUT = 2.0
BROADCAST_ADDRESS = "255.255.255.255"

# Message Types
MSG_DISCOVER = "discover_hydrophone"
MSG_DISCOVER_REPLY = "discover_reply"
MSG_SET_IP = "set_ip"
MSG_SET_IP_REPLY = "set_ip_reply"
MSG_SET_RTC = "set_rtc"
MSG_SET_RTC_REPLY = "set_rtc_reply"
MSG_GET_RTC = "get_rtc"
MSG_GET_RTC_REPLY = "get_rtc_reply"
MSG_SET_PARAM = "set_param"
MSG_SET_PARAM_REPLY = "set_param_reply"
MSG_GET_VERSION = "get_version"
MSG_GET_VERSION_REPLY = "get_version_reply"

# Authentication
AUTH_ENABLED = True  # Set to False to disable authentication during development
DEFAULT_SECRET_FILE = ".popoto_secret"


class ProtocolError(Exception):
    """Base exception for protocol-related errors"""
    pass


class AuthenticationError(ProtocolError):
    """Raised when authentication fails"""
    pass


class ValidationError(ProtocolError):
    """Raised when message validation fails"""
    pass


def compute_message_auth(message: Dict[str, Any], secret: str) -> str:
    """
    Compute HMAC-SHA256 authentication code for a message.

    Args:
        message: Message dictionary (without 'auth' field)
        secret: Shared secret string

    Returns:
        Hex-encoded HMAC-SHA256 hash
    """
    # Create a copy without the 'auth' field
    msg_copy = {k: v for k, v in message.items() if k != 'auth'}

    # Sort keys for consistent ordering
    message_bytes = json.dumps(msg_copy, sort_keys=True).encode('utf-8')
    secret_bytes = secret.encode('utf-8')

    # Compute HMAC-SHA256
    h = hmac.new(secret_bytes, message_bytes, hashlib.sha256)
    return h.hexdigest()


def add_auth(message: Dict[str, Any], secret: str) -> Dict[str, Any]:
    """
    Add authentication to a message.

    Args:
        message: Message dictionary
        secret: Shared secret string

    Returns:
        Message dictionary with 'auth' field added
    """
    if not AUTH_ENABLED:
        return message

    auth_code = compute_message_auth(message, secret)
    message['auth'] = auth_code
    return message


def verify_auth(message: Dict[str, Any], secret: str) -> bool:
    """
    Verify message authentication.

    Args:
        message: Message dictionary with 'auth' field
        secret: Shared secret string

    Returns:
        True if authentication is valid, False otherwise

    Raises:
        AuthenticationError: If auth field is missing when AUTH_ENABLED is True
    """
    if not AUTH_ENABLED:
        return True

    if 'auth' not in message:
        raise AuthenticationError("Missing authentication field")

    provided_auth = message['auth']
    expected_auth = compute_message_auth(message, secret)

    # Use constant-time comparison to prevent timing attacks
    return hmac.compare_digest(provided_auth, expected_auth)


def validate_ip_address(ip: str) -> bool:
    """
    Validate IPv4 address format.

    Args:
        ip: IP address string

    Returns:
        True if valid IPv4 address, False otherwise
    """
    pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
    if not re.match(pattern, ip):
        return False

    octets = ip.split('.')
    return all(0 <= int(octet) <= 255 for octet in octets)


def validate_mac_address(mac: str) -> bool:
    """
    Validate MAC address format.

    Args:
        mac: MAC address string (colon or hyphen separated)

    Returns:
        True if valid MAC address, False otherwise
    """
    pattern = r'^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$'
    return bool(re.match(pattern, mac))


def validate_netmask(netmask: str) -> bool:
    """
    Validate netmask format and value.

    Args:
        netmask: Netmask string (e.g., "255.255.255.0")

    Returns:
        True if valid netmask, False otherwise
    """
    if not validate_ip_address(netmask):
        return False

    # Convert to binary and check it's a valid netmask (contiguous 1s followed by 0s)
    octets = [int(x) for x in netmask.split('.')]
    binary = ''.join(format(octet, '08b') for octet in octets)

    # Valid netmask: starts with 1s, followed by 0s, no transitions from 0 to 1
    stripped = binary.lstrip('1')
    return '1' not in stripped


def validate_discover_request(message: Dict[str, Any]) -> None:
    """
    Validate a discovery request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_DISCOVER:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not isinstance(message['nonce'], str) or len(message['nonce']) == 0:
        raise ValidationError("Invalid nonce")


def validate_discover_reply(message: Dict[str, Any]) -> None:
    """
    Validate a discovery reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'model', 'serial', 'ip', 'mac', 'fw']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_DISCOVER_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_ip_address(message['ip']):
        raise ValidationError(f"Invalid IP address: {message['ip']}")

    if not validate_mac_address(message['mac']):
        raise ValidationError(f"Invalid MAC address: {message['mac']}")


def validate_set_ip_request(message: Dict[str, Any]) -> None:
    """
    Validate a set IP request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'target_mac', 'new_ip', 'netmask', 'gateway']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_IP:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_mac_address(message['target_mac']):
        raise ValidationError(f"Invalid target MAC address: {message['target_mac']}")

    if not validate_ip_address(message['new_ip']):
        raise ValidationError(f"Invalid new IP address: {message['new_ip']}")

    if not validate_netmask(message['netmask']):
        raise ValidationError(f"Invalid netmask: {message['netmask']}")

    if not validate_ip_address(message['gateway']):
        raise ValidationError(f"Invalid gateway address: {message['gateway']}")


def validate_set_ip_reply(message: Dict[str, Any]) -> None:
    """
    Validate a set IP reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'status']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_IP_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if message['status'] not in ['ok', 'error']:
        raise ValidationError(f"Invalid status: {message['status']}")


def load_shared_secret(secret_file: Optional[str] = None) -> str:
    """
    Load shared secret from file.

    Args:
        secret_file: Path to secret file (defaults to DEFAULT_SECRET_FILE in current directory)

    Returns:
        Shared secret string

    Raises:
        FileNotFoundError: If secret file doesn't exist
        ValueError: If secret file is empty or invalid
    """
    if secret_file is None:
        secret_file = DEFAULT_SECRET_FILE

    try:
        with open(secret_file, 'r') as f:
            secret = f.read().strip()

        if not secret:
            raise ValueError("Secret file is empty")

        if len(secret) < 16:
            raise ValueError("Secret must be at least 16 characters")

        return secret
    except FileNotFoundError:
        raise FileNotFoundError(
            f"Secret file not found: {secret_file}\n"
            f"Create a secret file with: python3 -c \"import secrets; "
            f"print(secrets.token_hex(32))\" > {secret_file}"
        )


def create_discover_message(nonce: str, secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a discovery request message.

    Args:
        nonce: Unique nonce for this request
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary
    """
    message = {
        'cmd': MSG_DISCOVER,
        'nonce': nonce
    }

    if secret:
        message = add_auth(message, secret)

    return message


def create_set_ip_message(nonce: str, target_mac: str, new_ip: str,
                         netmask: str, gateway: str,
                         secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a set IP request message.

    Args:
        nonce: Unique nonce for this request
        target_mac: MAC address of target device
        new_ip: New IP address to set
        netmask: Network mask
        gateway: Gateway address
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary

    Raises:
        ValidationError: If any parameters are invalid
    """
    # Validate inputs
    if not validate_mac_address(target_mac):
        raise ValidationError(f"Invalid target MAC address: {target_mac}")
    if not validate_ip_address(new_ip):
        raise ValidationError(f"Invalid new IP address: {new_ip}")
    if not validate_netmask(netmask):
        raise ValidationError(f"Invalid netmask: {netmask}")
    if not validate_ip_address(gateway):
        raise ValidationError(f"Invalid gateway address: {gateway}")

    message = {
        'cmd': MSG_SET_IP,
        'nonce': nonce,
        'target_mac': target_mac,
        'new_ip': new_ip,
        'netmask': netmask,
        'gateway': gateway
    }

    if secret:
        message = add_auth(message, secret)

    return message


def validate_rtc_format(rtc_str: str) -> bool:
    """
    Validate RTC string format (YYYY.MM.DD-HH:MM:SS).

    Args:
        rtc_str: RTC string

    Returns:
        True if valid format, False otherwise
    """
    pattern = r'^\d{4}\.\d{2}\.\d{2}-\d{2}:\d{2}:\d{2}$'
    return bool(re.match(pattern, rtc_str))


def validate_set_rtc_request(message: Dict[str, Any]) -> None:
    """
    Validate a set RTC request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'target_mac', 'rtc']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_RTC:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_mac_address(message['target_mac']):
        raise ValidationError(f"Invalid target MAC address: {message['target_mac']}")

    if not validate_rtc_format(message['rtc']):
        raise ValidationError(f"Invalid RTC format: {message['rtc']} (expected YYYY.MM.DD-HH:MM:SS)")


def validate_set_rtc_reply(message: Dict[str, Any]) -> None:
    """
    Validate a set RTC reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'status']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_RTC_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if message['status'] not in ['ok', 'error']:
        raise ValidationError(f"Invalid status: {message['status']}")


def validate_get_rtc_request(message: Dict[str, Any]) -> None:
    """
    Validate a get RTC request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'target_mac']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_GET_RTC:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_mac_address(message['target_mac']):
        raise ValidationError(f"Invalid target MAC address: {message['target_mac']}")


def validate_get_rtc_reply(message: Dict[str, Any]) -> None:
    """
    Validate a get RTC reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'status']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_GET_RTC_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if message['status'] not in ['ok', 'error']:
        raise ValidationError(f"Invalid status: {message['status']}")


def validate_set_param_request(message: Dict[str, Any]) -> None:
    """
    Validate a set parameter request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'target_mac', 'param_name', 'param_value']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_PARAM:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_mac_address(message['target_mac']):
        raise ValidationError(f"Invalid target MAC address: {message['target_mac']}")


def validate_set_param_reply(message: Dict[str, Any]) -> None:
    """
    Validate a set parameter reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'status']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_SET_PARAM_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if message['status'] not in ['ok', 'error']:
        raise ValidationError(f"Invalid status: {message['status']}")


def create_set_rtc_message(nonce: str, target_mac: str, rtc: str,
                           secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a set RTC request message.

    Args:
        nonce: Unique nonce for this request
        target_mac: MAC address of target device
        rtc: RTC string in format YYYY.MM.DD-HH:MM:SS
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary

    Raises:
        ValidationError: If any parameters are invalid
    """
    if not validate_mac_address(target_mac):
        raise ValidationError(f"Invalid target MAC address: {target_mac}")
    if not validate_rtc_format(rtc):
        raise ValidationError(f"Invalid RTC format: {rtc} (expected YYYY.MM.DD-HH:MM:SS)")

    message = {
        'cmd': MSG_SET_RTC,
        'nonce': nonce,
        'target_mac': target_mac,
        'rtc': rtc
    }

    if secret:
        message = add_auth(message, secret)

    return message


def create_get_rtc_message(nonce: str, target_mac: str,
                           secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a get RTC request message.

    Args:
        nonce: Unique nonce for this request
        target_mac: MAC address of target device
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary

    Raises:
        ValidationError: If any parameters are invalid
    """
    if not validate_mac_address(target_mac):
        raise ValidationError(f"Invalid target MAC address: {target_mac}")

    message = {
        'cmd': MSG_GET_RTC,
        'nonce': nonce,
        'target_mac': target_mac
    }

    if secret:
        message = add_auth(message, secret)

    return message


def create_set_param_message(nonce: str, target_mac: str, param_name: str,
                             param_value: Any, secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a set parameter request message.

    Args:
        nonce: Unique nonce for this request
        target_mac: MAC address of target device
        param_name: Name of the popoto parameter to set
        param_value: Value to set (int or float)
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary

    Raises:
        ValidationError: If any parameters are invalid
    """
    if not validate_mac_address(target_mac):
        raise ValidationError(f"Invalid target MAC address: {target_mac}")

    message = {
        'cmd': MSG_SET_PARAM,
        'nonce': nonce,
        'target_mac': target_mac,
        'param_name': param_name,
        'param_value': param_value
    }

    if secret:
        message = add_auth(message, secret)

    return message


def validate_get_version_request(message: Dict[str, Any]) -> None:
    """
    Validate a get version request message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'target_mac']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_GET_VERSION:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if not validate_mac_address(message['target_mac']):
        raise ValidationError(f"Invalid target MAC address: {message['target_mac']}")


def validate_get_version_reply(message: Dict[str, Any]) -> None:
    """
    Validate a get version reply message.

    Args:
        message: Message dictionary

    Raises:
        ValidationError: If message is invalid
    """
    required_fields = ['cmd', 'nonce', 'status']
    for field in required_fields:
        if field not in message:
            raise ValidationError(f"Missing required field: {field}")

    if message['cmd'] != MSG_GET_VERSION_REPLY:
        raise ValidationError(f"Invalid command: {message['cmd']}")

    if message['status'] not in ['ok', 'error']:
        raise ValidationError(f"Invalid status: {message['status']}")


def create_get_version_message(nonce: str, target_mac: str,
                                secret: Optional[str] = None) -> Dict[str, Any]:
    """
    Create a get version request message.

    Args:
        nonce: Unique nonce for this request
        target_mac: MAC address of target device
        secret: Shared secret for authentication (optional)

    Returns:
        Message dictionary

    Raises:
        ValidationError: If any parameters are invalid
    """
    if not validate_mac_address(target_mac):
        raise ValidationError(f"Invalid target MAC address: {target_mac}")

    message = {
        'cmd': MSG_GET_VERSION,
        'nonce': nonce,
        'target_mac': target_mac
    }

    if secret:
        message = add_auth(message, secret)

    return message
