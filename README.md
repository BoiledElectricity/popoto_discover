# Popoto Discovery Tool

A UDP broadcast-based discovery and management tool for Popoto hydrophone devices.

## Overview

The Popoto Discovery Tool consists of two components:

- **Host Tool** (`host/popoto_discover.py`) - PC-side tool for discovering and managing popoto devices
- **Client** (`target/popoto_discover_client.py`) - Daemon that runs on popoto devices to respond to discovery and configuration requests

## Features

- **Device Discovery** - Find all popoto devices on the local network via UDP broadcast
- **IP Configuration** - Remotely configure network settings (IP, netmask, gateway) on specific devices
- **Authentication** - HMAC-SHA256 based authentication to prevent unauthorized access
- **Real-time Status** - View device telemetry (battery, storage, recording state, etc.)
- **MAC-based Targeting** - Configure specific devices by their MAC address

## Architecture

```
┌─────────────────┐                    ┌─────────────────┐
│   Host Tool     │◄──UDP Broadcast───►│  Popoto Client  │
│   (Your PC)     │   Port 33333       │   (Hydrophone)  │
└─────────────────┘                    └─────────────────┘
        │                                       │
        ├─ discover_hydrophone ────────────────►│
        │◄─ discover_reply ─────────────────────┤
        │                                       │
        ├─ set_ip ─────────────────────────────►│
        │◄─ set_ip_reply ───────────────────────┤
```

## Installation

### Prerequisites

- Python 3.7+
- Root/sudo access (required on client for IP configuration)

### Install Dependencies

```bash
# Install dependencies for both host and client
pip install -r requirements.txt

# Or in a virtual environment (recommended)
python3 -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

### Dependencies

- `netifaces` - Network interface information
- `PyQt5` - GUI application (optional, only needed for graphical interface)

## Security Setup

**IMPORTANT:** Authentication is enabled by default. You must create a shared secret file before using the tool.

### Generate Shared Secret

On your development machine:

```bash
# Generate a secure random secret (64 hex characters)
python3 -c "import secrets; print(secrets.token_hex(32))" > .popoto_secret

# Verify the file was created
cat .popoto_secret
```

### Distribute Secret to Devices

**The same secret must be present on both the host and all client devices.**

```bash
# Copy secret to popoto device (example using scp)
scp .popoto_secret user@popoto-device:/path/to/popoto_discover/.popoto_secret

# Or manually create the file on the device with the same content
```

### File Permissions

**CRITICAL:** Protect the secret file with appropriate permissions:

```bash
# On both host and client
chmod 600 .popoto_secret
```

### Disable Authentication (NOT RECOMMENDED)

For development/testing only, you can disable authentication:

**Option 1:** Edit `common/protocol.py` and set:
```python
AUTH_ENABLED = False
```

**Option 2:** Use the `--no-auth` flag:
```bash
python3 host/popoto_discover.py --no-auth discover
```

**WARNING:** Disabling authentication allows anyone on the network to discover and reconfigure your devices!

## Usage

### GUI Application (Recommended)

The easiest way to use the Popoto Discovery Tool is through the graphical interface.

#### Launch GUI

```bash
# Install PyQt5 if not already installed
pip install PyQt5

# Launch the GUI
python3 host/popoto_discover_gui.py
```

#### GUI Features

The GUI provides an intuitive interface for all discovery and management operations:

**Main Window:**
- **Settings Panel:** Configure authentication (secret file path, disable auth option)
- **Device Discovery Table:** View all discovered devices with their properties
  - Name, Model, Serial, IP, MAC, Firmware
  - Battery voltage, Sample rate, Recording state
  - Storage information
- **Action Buttons:** Quick access to all operations
  - Discover Devices
  - Set IP Address
  - Set RTC (Real-Time Clock)
  - Get RTC
  - Set Parameter
- **Activity Log:** Real-time logging of all operations
- **Status Bar:** Shows current authentication status and operation feedback

**Workflow:**
1. **Load Secret:** Click "Load Secret" to authenticate (or check "Disable Auth" for testing)
2. **Discover:** Click "Discover Devices" to find popoto devices on your network
3. **Select Device:** Click on a row in the device table
4. **Perform Operations:** Use the action buttons to manage the selected device

**Set IP Dialog:**
- Enter new IP address, netmask, and gateway
- Configurable timeout
- Immediate feedback on success/failure

**Set RTC Dialog:**
- Manual entry or "Use Current Time" button to sync with PC clock
- Format: YYYY.MM.DD-HH:MM:SS
- Built-in validation

**Set Parameter Dialog:**
- Dropdown of common parameters (TxPowerWatts, RecordMode, etc.)
- Custom parameter option
- Supports int and float values
- Example values shown for reference

**Benefits:**
- No need to remember command-line syntax
- Visual feedback and error messages
- Table view of all devices at once
- One-click operations
- Real-time activity logging

### Command-Line Tool

For automation and scripting, use the command-line interface.

#### Discover Devices

```bash
# Basic discovery
python3 host/popoto_discover.py discover

# With custom timeout
python3 host/popoto_discover.py discover --timeout 5.0

# With custom secret file
python3 host/popoto_discover.py --secret-file /path/to/secret discover
```

Example output:
```
Sent discovery (nonce=a3f8c21d), waiting for replies...

Discovered 2 device(s):
----
 Name:            HydrophoneX-HX-ABC123
 Model:           HydrophoneX
 Serial:          HX-ABC123
 IP:              192.168.1.100
 MAC:             00:1a:2b:3c:4d:5e
 FW:              1.2.3
 Battery [V]:     12.46
 Sample Rate [Hz]:96000
 Recording state: recording
 Storage Free [G]:128.4
 Storage Total[G]:256.0
 URL:             http://192.168.1.100/
```

#### Configure IP Address

```bash
# Set IP on a specific device by MAC address
python3 host/popoto_discover.py set-ip 00:1a:2b:3c:4d:5e 192.168.1.150 255.255.255.0 192.168.1.1

# With custom timeout
python3 host/popoto_discover.py set-ip 00:1a:2b:3c:4d:5e 192.168.1.150 255.255.255.0 192.168.1.1 --timeout 5.0
```

Example output:
```
Sent set_ip to MAC 00:1a:2b:3c:4d:5e, waiting for reply...
IP set successfully to 192.168.1.150 (reply from 192.168.1.100)
```

#### Help

```bash
# General help
python3 host/popoto_discover.py --help

# Command-specific help
python3 host/popoto_discover.py discover --help
python3 host/popoto_discover.py set-ip --help
```

### Client (Popoto Device)

#### Run as Daemon

```bash
# Run in foreground (for testing)
sudo python3 target/popoto_discover_client.py

# Run in background
sudo python3 target/popoto_discover_client.py &

# Or use systemd (see below)
```

#### Systemd Service (Recommended)

Create `/etc/systemd/system/popoto-discover.service`:

```ini
[Unit]
Description=Popoto Discovery Client
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/path/to/popoto_discover
ExecStart=/usr/bin/python3 /path/to/popoto_discover/target/popoto_discover_client.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable popoto-discover
sudo systemctl start popoto-discover

# Check status
sudo systemctl status popoto-discover

# View logs
sudo journalctl -u popoto-discover -f
```

#### Logs

Client logs are written to:
- `/var/log/popoto_discover_client.log` (if writable)
- stdout/stderr (visible in systemd logs)

## Protocol Details

### Port

- UDP port **33333** (hardcoded, configurable in `common/protocol.py`)

### Authentication

- HMAC-SHA256 based message authentication
- Shared secret (minimum 16 characters)
- Constant-time comparison to prevent timing attacks

### Message Types

#### 1. Discovery Request

```json
{
  "cmd": "discover_hydrophone",
  "nonce": "a3f8c21d",
  "auth": "abc123..."
}
```

#### 2. Discovery Reply

```json
{
  "cmd": "discover_reply",
  "nonce": "a3f8c21d",
  "model": "HydrophoneX",
  "serial": "HX-ABC123",
  "ip": "192.168.1.100",
  "mac": "00:1a:2b:3c:4d:5e",
  "fw": "1.2.3",
  "http_port": 80,
  "name": "HydrophoneX-HX-ABC123",
  "battery_v": 12.46,
  "sample_rate_hz": 96000,
  "recording_state": "recording",
  "storage_free_gb": 128.4,
  "storage_total_gb": 256.0,
  "auth": "def456..."
}
```

#### 3. Set IP Request

```json
{
  "cmd": "set_ip",
  "nonce": "b7e9f32a",
  "target_mac": "00:1a:2b:3c:4d:5e",
  "new_ip": "192.168.1.150",
  "netmask": "255.255.255.0",
  "gateway": "192.168.1.1",
  "auth": "ghi789..."
}
```

#### 4. Set IP Reply

```json
{
  "cmd": "set_ip_reply",
  "nonce": "b7e9f32a",
  "status": "ok",
  "ip": "192.168.1.150",
  "auth": "jkl012..."
}
```

Or on error:

```json
{
  "cmd": "set_ip_reply",
  "nonce": "b7e9f32a",
  "status": "error",
  "ip": "192.168.1.150",
  "error": "Failed to add IP address: ...",
  "auth": "jkl012..."
}
```

## Security Considerations

### Threats

1. **Unauthorized Discovery** - Without authentication, anyone can discover devices
2. **Unauthorized Reconfiguration** - Without authentication, anyone can change device IPs
3. **Man-in-the-Middle** - UDP is unencrypted; messages can be intercepted
4. **Replay Attacks** - Nonces are random but could be replayed within timeout window
5. **Network Sniffing** - Shared secret could be extracted from captured traffic via dictionary attack

### Mitigations

✅ **Implemented:**
- HMAC-SHA256 authentication prevents unauthorized access
- Nonce-based request/response matching prevents some replay attacks
- MAC address targeting prevents accidental misconfiguration
- Input validation prevents injection attacks
- Constant-time comparison prevents timing attacks

⚠️ **Recommendations:**
- Use strong secrets (generated with `secrets.token_hex(32)`)
- Protect secret file with `chmod 600`
- Run client with minimal privileges (though root required for IP changes)
- Use network segmentation to isolate devices
- Consider adding timestamp-based replay prevention
- Consider adding TLS/DTLS for encryption (future enhancement)

## Troubleshooting

### Authentication Errors

```
Error: Secret file not found: .popoto_secret
```

**Solution:** Create the secret file:
```bash
python3 -c "import secrets; print(secrets.token_hex(32))" > .popoto_secret
```

### Permission Errors

```
Error: Failed to flush addresses: Operation not permitted
```

**Solution:** Run client with sudo:
```bash
sudo python3 target/popoto_discover_client.py
```

### No Devices Discovered

1. Check firewall rules (UDP port 33333 must be open)
2. Verify devices are on the same network
3. Check client is running: `sudo systemctl status popoto-discover`
4. Check client logs:
   - **Linux:** `tail -f /var/log/popoto_discover_client.log`
   - **macOS:** `tail -f target/logs/popoto_discover_client.log`
5. Try increasing timeout: `--timeout 5.0`
6. Temporarily disable authentication to test: `--no-auth`

### Log Files

**Client:**
- **Linux:** `/var/log/popoto_discover_client.log`
- **macOS:** `target/logs/popoto_discover_client.log` (local directory)
- **systemd:** `journalctl -u popoto-discover` (if using systemd)
- Automatically falls back to console-only logging if file cannot be created

**Host CLI:**
- Logs to console (stderr)
- Set log level in code: `logging.basicConfig(level=logging.DEBUG)`

**Host GUI:**
- Activity log panel in the GUI window
- No separate log file

## Development

### Project Structure

```
popoto_discover/
├── common/
│   ├── __init__.py
│   └── protocol.py          # Shared protocol, constants, authentication
├── host/
│   ├── popoto_discover.py   # Command-line tool (PC-side)
│   └── popoto_discover_gui.py # GUI application (PC-side)
├── target/
│   └── popoto_discover_client.py  # Client daemon (device-side)
├── requirements.txt         # Python dependencies
└── README.md               # This file
```

### Testing

```bash
# Test discovery (from host)
python3 host/popoto_discover.py discover

# Run client in foreground for testing (on device)
sudo python3 target/popoto_discover_client.py

# Test with authentication disabled
python3 host/popoto_discover.py --no-auth discover
```

### Extending the Protocol

To add new commands:

1. Add message type constants to `common/protocol.py`
2. Add validation functions to `common/protocol.py`
3. Implement handler in `target/popoto_discover_client.py`
4. Add client function in `host/popoto_discover.py`
5. Add CLI command to `main()` in host tool

## TODO / Future Enhancements

- [ ] Add TLS/DTLS encryption for message security
- [ ] Add timestamp-based replay attack prevention
- [ ] Wire up real telemetry data in `get_status()`
- [ ] Add configuration file support (YAML/JSON)
- [ ] Add firmware update capability
- [ ] Add device reboot command
- [ ] Add configuration backup/restore
- [ ] Add support for multiple network interfaces
- [ ] Add mDNS/Bonjour discovery as alternative
- [ ] Add web UI for non-technical users
- [ ] Add unit tests
- [ ] Add integration tests

## License

[Your license here]

## Authors

[Your name here]

## Contributing

[Contributing guidelines here]
