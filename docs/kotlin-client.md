# Kotlin Host Client

`SENG-982` starts the host rewrite as a JVM/Kotlin application while keeping the
existing Python client and daemon in place.

The first Kotlin command is discovery:

```bash
./gradlew shadowJar
sudo java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar --no-auth discover --transport all -i enp1s0
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

This Kotlin host is the foundation for the flashing workflow. The intended next
steps are:

1. Add target selection by stable device ID/serial.
2. Add AoE/WIC/BMAP flashing commands.
3. Add a small GUI on top of the same Kotlin core.
4. Package with a bundled Java runtime using `jpackage` so users get both a GUI
   launcher and a CLI command.
