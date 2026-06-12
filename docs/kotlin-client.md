# Kotlin Host Client

`SENG-982` moves the host tool to a JVM/Kotlin application while keeping the
existing Python daemon on the modem.

The Kotlin host CLI supports the host-side discovery and management commands:

```bash
./gradlew shadowJar
sudo java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar --no-auth discover --transport all -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-ip <target> 10.1.0.239 255.255.255.0 10.1.0.1 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-rtc <target> 2026.06.12-10:30:00 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar get-rtc <target> -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar set-param <target> TxPowerWatts 2 -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar get-version <target> -i enp1s0
java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar gui
```

Authentication matches the Python implementation. By default the CLI reads
`.popoto_secret` from the current directory. For development only, `--no-auth`
matches the Python flag.

Discovery transports:

- `udp`: existing UDP broadcast on port `33333`
- `l2`: raw Ethernet discovery using EtherType `0x88b6`
- `auto`: UDP plus raw Ethernet where available
- `all`: same as auto, kept explicit for tests and operator workflows

Raw Ethernet uses libpcap through Pcap4J, so it can discover PMM devices on the
same Ethernet broadcast domain even when the device IP address or subnet is
wrong. Linux and macOS normally require `sudo`; Windows will require Npcap when
we add packaged Windows support.

Static IP configuration is the supported network configuration mode.

This Kotlin host is the base for the flashing workflow. The intended next steps
are:

1. Add AoE/WIC/BMAP flashing commands.
2. Package with a bundled Java runtime using `jpackage` so users get both a GUI
   launcher and a CLI command.
