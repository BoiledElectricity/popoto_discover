# Popoto Discover Kasm Container

This image runs the Popoto Discover desktop app inside KasmVNC so it can be used from a browser while still using the host Ethernet interfaces for L2 discovery and AoE flashing.

Use the wrapper from the repo root:

```sh
./scripts/popoto-discover-kasm start
```

Then open:

```text
http://localhost:3000
```

The default shared folder is `$HOME/Downloads`, mounted inside the desktop as `/downloads` and linked on the Kasm desktop as `Downloads`.

Common commands:

```sh
./scripts/popoto-discover-kasm start [downloads-folder]
./scripts/popoto-discover-kasm stop
./scripts/popoto-discover-kasm logs
./scripts/popoto-discover-kasm update
./scripts/popoto-discover-kasm build
./scripts/popoto-discover-kasm push
```

`start` pulls the published image if needed and starts/restarts the container. On container startup, the app source is cloned or updated from `POPOTO_REPO`/`POPOTO_BRANCH`; if the source SHA changed, the jar is rebuilt automatically.

`update` pulls the newest container image and restarts it. It also gets the latest app source on startup.

The container uses host networking plus `NET_RAW`/`NET_ADMIN`; those are required for raw Ethernet discovery and AoE flashing.
