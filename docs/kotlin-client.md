# Kotlin Host Client

`SENG-982` moves the host tool to a JVM/Kotlin application while keeping the
existing Python daemon on the modem.

The Kotlin host CLI supports the host-side discovery and management commands:

```bash
./gradlew shadowJar
sudo java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar discover --transport all -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-ip <target> 10.1.0.239 255.255.255.0 10.1.0.1 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-rtc <target> 2026.06.12-10:30:00 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar get-rtc <target> -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-param <target> TxPowerWatts 2 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar get-version <target> -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar gui
```

Authentication matches the Python implementation. The Kotlin host embeds the
shared Popoto production secret, so normal discovery does not need a secret
file. Use `--secret-file` only for a nonstandard modem secret. For development
only, `--no-auth` matches the Python flag.

Discovery transports:

- `udp`: existing UDP broadcast on port `33333`
- `l2`: raw Ethernet discovery using EtherType `0x88b6`
- `auto`: UDP plus raw Ethernet where available
- `all`: same as auto, kept explicit for tests and operator workflows

Raw Ethernet uses AF_PACKET/libpcap on Linux, BPF/libpcap on macOS, and the PMM
NDIS driver on Windows, so it can discover PMM devices on the same Ethernet
broadcast domain even when the device IP address or subnet is wrong. Packaged
macOS, Windows, and Linux AppImage builds automatically perform their one-time
raw Ethernet setup from inside the app. Linux development jars still need to run
from an environment that already has raw Ethernet permission.

Static IP configuration is the supported network configuration mode.

This Kotlin host is the base for the flashing workflow. The intended next steps
are:

1. Add AoE/WIC/BMAP flashing commands.
2. Package with a bundled Java runtime using `jpackage` so users get both a GUI
   launcher and a CLI command.
