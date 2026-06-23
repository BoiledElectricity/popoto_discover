# Popoto Discovery

Host-side discovery and management tooling for Popoto/PMM devices.

The host application is Kotlin/JVM. The modem-side service remains the existing
Python daemon under `/opt/popoto/popoto_discover`, so deployed PMM images keep
their current service integration while the PC-side tool is cross-platform.

## Components

- `src/main/kotlin/com/popotomodem/discover`: Kotlin host CLI and Compose GUI
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
use libpcap through Pcap4J on Linux/macOS and the PMM NDIS driver on Windows.

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

- macOS `.dmg`: includes the Java runtime and automatically performs one-time
  BPF setup from inside the app when L2 capture is needed.
- Linux `.deb`: includes the Java runtime, depends on system `libpcap0.8`, and
  applies the packet-capture capabilities needed by the bundled GUI and CLI.
- Windows `.msi`: includes the Java runtime and embeds the PMM NDIS raw
  Ethernet driver package when the Windows CI driver build/signing step
  produces `pmmndis630.inf`, `pmmndis630.sys`, and `pmmndis630.cat`.
  CI-built MSIs use a stable Windows upgrade UUID and an increasing package
  version so installing a newer artifact upgrades the existing Popoto Discover
  install instead of requiring a manual uninstall.

Linux operators can use either the `.deb` or the AppImage. The deb depends on
`libpcap0.8` and applies packet-capture capabilities to the bundled Popoto
Discover GUI and CLI launchers at install time. The AppImage performs its own
one-time L2 setup on first launch: it copies the bundled app into the user's
local Popoto Discover cache, requests administrator permission, grants raw
Ethernet capabilities to the cached launchers, and then runs the GUI as the
normal desktop user. Do not run the GUI with `sudo`.

macOS uses the same pattern as Wireshark: the app automatically requests the
one-time BPF access setup when L2 capture is needed. After that, the normal
desktop user can discover and flash.

GitHub macOS packaging signs and notarizes the DMG when these repository
secrets are configured:

- `MACOS_DEVELOPER_ID_APPLICATION_P12_B64`: base64-encoded Developer ID
  Application certificate exported as `.p12`
- `MACOS_DEVELOPER_ID_APPLICATION_P12_PASSWORD`: password for that `.p12`
- `MACOS_SIGNING_KEY_USER_NAME`: the signing identity team/user portion, for
  example `Popoto Modem LLC (TEAMID)`
- `APPLE_ID`: Apple Developer account email used for notarization
- `APPLE_TEAM_ID`: Apple Developer team ID
- `APPLE_APP_SPECIFIC_PASSWORD`: app-specific password for `notarytool`

Windows raw Ethernet uses the PMM NDIS protocol driver in `windows/pmmndis`.
The app opens `\\.\PmmNdis` and uses it for L2 discovery, U-Boot Ethernet
console, and AoE flashing. Production Windows packages still require a properly
signed driver catalog; CI fails the Windows driver packaging step if the PMM
driver package is not produced.

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

The Windows workflow builds the PMM NDIS driver package before the MSI and
copies the resulting driver files into `packaging/windows/pmmndis` so Gradle can
embed them. Driver signing or Microsoft attestation must be wired into that
step before a production Windows MSI can install raw Ethernet support on normal
operator machines.

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

Platform capture setup:

- macOS: the app requests one-time BPF setup automatically.
- Linux deb install: packet-capture capabilities are applied at install time.
- Linux AppImage: first launch requests administrator permission once and then
  runs as the normal desktop user.
- Linux development jar: run from an environment that has raw Ethernet
  permission, or use the packaged AppImage/deb.
- Windows MSI with bundled PMM NDIS driver: the app installs the driver
  automatically with UAC on first launch if it is missing.

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

## Browser Desktop Container

The Kasm container runs the Popoto Discover desktop app in a browser while using
the Linux host Ethernet interfaces for L2 discovery and AoE flashing.

```bash
./scripts/popoto-discover-kasm start
```

Open:

```text
http://localhost:3000
```

The default shared file folder is `$HOME/Downloads`, mounted in the container as
`/downloads` and shown on the desktop as `Downloads`. To share a different
folder:

```bash
./scripts/popoto-discover-kasm start /path/to/images
```

Useful commands:

```bash
./scripts/popoto-discover-kasm logs
./scripts/popoto-discover-kasm stop
./scripts/popoto-discover-kasm update
```

`update` pulls `registry.popotomodem.com/delresearch/popoto_discover-kasm:latest`
and restarts the container. On startup the container also checks the configured
Popoto Discover source branch and rebuilds the app jar if the source SHA changed.
