# Popoto Discovery

Host-side discovery and management tooling for Popoto/PMM devices.

The host application is Kotlin/JVM. The modem-side service remains the existing
Python daemon under `/opt/popoto/popoto_discover`, so deployed PMM images keep
their current service integration while the PC-side tool is cross-platform.

## Components

- `src/main/kotlin/com/popotomodem/discover`: Kotlin host CLI and Swing GUI
- `client/popoto_discover_client.py`: Python daemon that runs on PMM devices
- `common/protocol.py`: Python protocol helper used by the modem daemon
- `host/`: legacy Python host tools kept for transition only

## Build

```bash
./gradlew shadowJar
```

The host jar is created at:

```bash
build/libs/popoto-discover-0.1.0-SNAPSHOT.jar
```

Java 17 or newer is required. Raw Ethernet discovery uses libpcap through Pcap4J,
so Linux and macOS usually require `sudo` for `--transport l2` or `--transport all`.
Windows support will require Npcap when packaged.

## Authentication

Authentication is enabled by default. The host reads `.popoto_secret` from the
current directory unless `--secret-file` is provided.

```bash
python3 -c "import secrets; print(secrets.token_hex(32))" > .popoto_secret
chmod 600 .popoto_secret
```

The same secret must be installed on the modem service. `--no-auth` exists only
for development.

## CLI

Discover devices using UDP broadcast plus raw Ethernet:

```bash
sudo java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  discover --transport all -i enp1s0 --timeout 4 --retries 4
```

Run the desktop GUI:

```bash
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar --secret-file .popoto_secret gui
```

Use the device ID/serial reported by discovery as the target. MAC targeting is
still accepted for older workflows, but real device ID is preferred because PMM
Ethernet MACs may be generated.

```bash
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  get-rtc eba9affefe64bada09122316 -i enp1s0 --timeout 5

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  get-version eba9affefe64bada09122316 -i enp1s0 --timeout 8

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  set-ip eba9affefe64bada09122316 10.1.0.231 255.255.255.0 10.1.0.1 -i enp1s0

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  set-rtc eba9affefe64bada09122316 2026.06.12-10:30:00 -i enp1s0

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  --secret-file .popoto_secret \
  set-param eba9affefe64bada09122316 TxPowerWatts 2 -i enp1s0
```

Static IP configuration is the supported network configuration mode.

## Discovery

The host supports two discovery transports:

- `udp`: UDP broadcast on port `33333`
- `l2`: raw Ethernet frames using EtherType `0x88b6`

`--transport auto` and `--transport all` send over both paths when raw Ethernet
is available. Raw Ethernet discovery is what makes devices visible on the same
Ethernet broadcast domain even when their IP address or subnet does not match
the host.

Use `-i/--interface` to force the Ethernet interface. It may be repeated.
Management commands also accept `-i` so replies work on hosts with multiple
interfaces or multiple configured subnets.

## Modem Service

The PMM service is still Python:

```bash
systemctl status popoto-discover.service
journalctl -u popoto-discover.service -f
```

The daemon reports the same live device identity in discovery replies and command
target matching. This matters on PMM boots where the startup serial can be
`unknown` before the modem app later reports the real `DeviceID`.

## Live Validation

Validated from `mini-ser` against `pmm6081` at `10.1.0.231`:

```bash
sudo java -jar /tmp/popoto-discover.jar \
  --secret-file /tmp/popoto-discover.secret \
  discover --transport all -i enp1s0 --timeout 4 --retries 4

java -jar /tmp/popoto-discover.jar \
  --secret-file /tmp/popoto-discover.secret \
  get-version eba9affefe64bada09122316 -i enp1s0 --timeout 8

java -jar /tmp/popoto-discover.jar \
  --secret-file /tmp/popoto-discover.secret \
  get-rtc eba9affefe64bada09122316 -i enp1s0 --timeout 5
```

Results:

- Discovery found `pmm6081` by UDP and raw Ethernet on `enp1s0`.
- Device ID target: `eba9affefe64bada09122316`.
- `get-version` returned `version=unknown`, `serial=eba9affefe64bada09122316`.
- `get-rtc` returned the modem RTC.

`get-version` can take about five seconds because it waits on the modem API, so
the Kotlin host uses a longer default timeout for that command.

## Development

```bash
./gradlew shadowJar
python3 -m py_compile common/protocol.py client/popoto_discover_client.py
```

Do not reintroduce host-side Python features as the primary host application.
New host functionality belongs in the Kotlin code path.
