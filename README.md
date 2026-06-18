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

Java 17 or newer is required when running from the development jar. Packaged
installers include their own Java runtime. Raw Ethernet discovery and flashing
use libpcap/Npcap through Pcap4J.

## Installers

The host app can be packaged with a bundled Java runtime:

```bash
./gradlew packageHost
```

Installer packaging is native to the OS running the build:

- Linux builds `Popoto-Discover-<version>-x86_64.AppImage` for double-click GUI use and `popoto-discover_<version>_amd64.deb` for installed desktop/CLI use.
- macOS builds a `.dmg`.
- Windows builds an `.msi`.

The private GitHub mirror builds these artifacts with GitHub Actions on native
Linux, macOS, and Windows runners. Download the finished packages from the
`Package installers` workflow artifacts.

The supported operator packages are intended to be self-contained:

- macOS `.dmg`: includes the Java runtime and performs one-time BPF setup from
  inside the app when L2 capture is needed.
- Linux `.deb`: includes the Java runtime, depends on system `libpcap0.8`, and
  applies the packet-capture capabilities needed by the bundled GUI and CLI.
- Windows `.msi`: includes the Java runtime and, when CI is given the Npcap OEM
  installer secret, embeds Npcap so the app can install packet capture from
  inside Popoto Discover.

Linux operators should install the `.deb` for the cleanest flashing setup. The
deb depends on `libpcap0.8` and applies packet-capture capabilities to the
bundled Popoto Discover GUI and CLI launchers. Use the AppImage for a portable
GUI, but run it with elevated capture permission, for example `sudo`, when L2
discovery or AoE flashing is required.

macOS uses the same pattern as Wireshark: the app offers a one-time BPF access
setup prompt when L2 capture is needed. After that, the normal desktop user can
discover and flash.

Windows raw Ethernet requires Npcap. The Windows package build is configured to
fail if `-PrequireBundledNpcap=true` is set and no bundled Npcap OEM installer
is supplied, so we do not publish an MSI that sends operators to a website. The
app installs bundled Npcap with WinPcap API compatibility enabled and
administrator-only capture disabled.

## GitLab to GitHub Packaging Bridge

GitLab remains the source repository. The GitLab pipeline verifies the host
tool, then mirrors the exact pushed branch or tag commit to the private GitHub
repo `BoiledElectricity/popoto_discover`. That GitHub push triggers the
cross-OS `Package installers` workflow.

Set this masked GitLab CI variable to enable the bridge:

```text
GITHUB_MIRROR_TOKEN
```

The token needs write access to the private GitHub mirror contents. If the
variable is not present, the GitLab mirror job exits cleanly without pushing to
GitHub.

For a fully self-contained Windows MSI, set this GitHub Actions secret in the
mirror repository:

```text
NPCAP_OEM_INSTALLER_B64
```

Its value is the base64 contents of the approved Npcap OEM installer executable.
The workflow writes it to `packaging/windows/npcap-oem.exe` before running
Gradle. The app then embeds that installer and can request UAC once to install
Npcap locally.

## Authentication

Authentication is enabled by default. The host has the shared Popoto production
secret embedded, so normal discovery and management commands do not need a
secret file.

```bash
python3 -c "import secrets; print(secrets.token_hex(32))" > .popoto_secret
chmod 600 .popoto_secret
```

Use `--secret-file` only for a nonstandard deployment with a different modem
secret. `--no-auth` exists only for development.

## CLI

Discover devices using UDP broadcast plus raw Ethernet:

```bash
sudo java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  discover --transport all -i enp1s0 --timeout 4 --retries 4
```

Run the desktop GUI:

```bash
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar gui
```

Use the device ID/serial reported by discovery as the target. MAC targeting is
still accepted for older workflows, but real device ID is preferred because PMM
Ethernet MACs may be generated.

```bash
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  get-rtc eba9affefe64bada09122316 -i enp1s0 --timeout 5

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  get-version eba9affefe64bada09122316 -i enp1s0 --timeout 8

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  set-ip eba9affefe64bada09122316 10.1.0.231 255.255.255.0 10.1.0.1 -i enp1s0

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
  set-rtc eba9affefe64bada09122316 2026.06.12-10:30:00 -i enp1s0

java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar \
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

Platform capture requirements:

- macOS: use the in-app `Enable L2` prompt once.
- Linux deb install: packet-capture capabilities are applied at install time.
- Linux AppImage or development jar: run with `sudo` for L2 discovery/flashing.
- Windows MSI with bundled Npcap: use the in-app `Install Npcap` prompt once.

Use `-i/--interface` to force the Ethernet interface. It may be repeated.
Management commands also accept `-i` so replies work on hosts with multiple
interfaces or multiple configured subnets.

## Modem Service

The PMM service is still Python:

```bash
systemctl status popoto-discover.service
journalctl -u popoto-discover.service -f
```

The daemon reports the i.MX CPU UID as `device_id`/`cpu_uid` and uses that value
for command target matching. The `serial` field is separate pShell/manufacturing
metadata and may legitimately be `unknown`.

## Live Validation

Validated from `mini-ser` against `pmm6081` at `10.1.0.231`:

```bash
sudo java -jar /tmp/popoto-discover.jar \
  discover --transport all -i enp1s0 --timeout 4 --retries 4

java -jar /tmp/popoto-discover.jar \
  get-version eba9affefe64bada09122316 -i enp1s0 --timeout 8

java -jar /tmp/popoto-discover.jar \
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
