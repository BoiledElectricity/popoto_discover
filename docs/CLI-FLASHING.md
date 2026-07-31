# Popoto Discover CLI Provisioning Guide

This is the operator and automation reference for provisioning PMM eMMC with
Popoto Discover. It is written for a person or script running the host-side
command-line application.

The CLI and desktop application use the same Kotlin flashing implementation.
The CLI is non-interactive: it can run from a terminal, an SSH session, a
factory script, `tmux`, or a service without opening the GUI.

## Quick Start

For the normal case, put these files in one directory:

```text
pmm-image-pmm.rootfs.wic.lz4
pmm-image-pmm.rootfs.wic.bmap
imx-boot-pmm-emmc.bin-flash_evk
```

Discover the PMM:

```bash
popoto-discover discover --transport all --interface enp1s0
```

Copy the reported `Device ID` or `CPU UID`, then validate the local artifacts
without touching the PMM:

```bash
popoto-discover flash fe64bada09122316 \
  ./pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader ./imx-boot-pmm-emmc.bin-flash_evk \
  --dry-run
```

Run the flash:

```bash
popoto-discover flash fe64bada09122316 \
  ./pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader ./imx-boot-pmm-emmc.bin-flash_evk \
  --interface enp1s0
```

The matching `.wic.bmap` is selected automatically. A successful command does
not stop at the last write: it flushes the eMMC, asks U-Boot to expand partition
2 and its ext4 filesystem, resets the PMM, waits for Linux to boot, independently
verifies that the rootfs occupies the eMMC, restores the preserved device files,
and reports the rediscovered unit.

## What The Command Changes

The flash has two independently optional inputs:

1. The WIC always programs the eMMC user area exposed by U-Boot AoE. It does
   not contain or overwrite the eMMC `boot0` and `boot1` hardware partitions.
2. `--bootloader IMX_BOOT` updates the eMMC boot partitions before the WIC is
   written. Omit it to leave U-Boot unchanged.

Popoto Discover automatically preserves these files when they exist:

```text
/etc/PopotoSerialNumber.txt
/etc/network/interfaces
/etc/network/interfaces.d/*
/opt/popoto/config.json
/opt/popoto/license.json
```

It stores the captured contents and metadata in a restricted, checksummed
per-CPU-UID journal before reboot. A later invocation can resume restoration
after an interrupted flash, including when the target is already in U-Boot.
Snapshots older than seven days are rejected explicitly instead of silently
restoring stale device state. If a target is already in U-Boot and no snapshot
exists, the command warns that device-specific files cannot be restored.
After Linux boots, it restores the files, runs `sync`, restarts networking when
network files were restored, and removes the journal only after successful
cleanup. Restoration and cleanup are still attempted if the independent rootfs
capacity check fails. Missing files are skipped. Root SSH keys are not
preserved by the current CLI command.

## Host Requirements

- Use the packaged Popoto Discover application when possible. Its installer
  includes the Java runtime and host packet-capture support.
- A development checkout requires Java 17:

  ```bash
  ./gradlew shadowJar
  java -jar build/libs/popoto-discover-0.1.0-SNAPSHOT.jar version
  ```

- The host must have an Ethernet path to the PMM Layer 2 network. IP subnets do
  not need to match for raw Ethernet discovery and AoE.
- The host process must be allowed to send and receive raw Ethernet frames.
  Packaged installations configure this for the supported operating system.
- Do not disconnect Ethernet or remove PMM power during bootloader or WIC
  writes.

All examples below use `popoto-discover`. For a development jar, replace it
with:

```bash
java -jar /absolute/path/to/popoto-discover-0.1.0-SNAPSHOT.jar
```

## Choose The Ethernet Interface

Specify the host interface connected to the PMM network. This avoids selecting
Wi-Fi, a VPN, a virtual interface, or an unrelated Ethernet port.

Linux:

```bash
ip -br link
ip -br address
```

macOS:

```bash
networksetup -listallhardwareports
ifconfig
```

Windows PowerShell:

```powershell
Get-NetAdapter | Format-Table Name, InterfaceDescription, Status, LinkSpeed
```

Typical names are `enp1s0` on Linux, `en0` or `en5` on macOS, and
`Ethernet 13` on Windows. Quote interface names containing spaces:

```powershell
popoto-discover discover --transport all --interface "Ethernet 13"
```

Do not hard-code a Windows interface number from another computer.

## Discover And Identify A Target

Use both UDP and Layer 2 discovery:

```bash
popoto-discover discover \
  --transport all \
  --interface enp1s0 \
  --timeout 4 \
  --retries 5
```

The stable target identity is the NXP CPU UID reported as `Device ID`/`CPU
UID`, for example:

```text
fe64bada09122316
```

Use that value as `TARGET`. Do not use the human serial number as identity.
The serial number is device metadata and can be unknown, duplicated, or changed
without changing the CPU UID.

Raw Ethernet discovery can find units whose IP addresses are on a different
subnet. UDP is still useful for normal IP communication and backward
compatibility.

## Validate Before Writing

### Validate An imx-boot File

```bash
popoto-discover check-bootloader \
  ./imx-boot-pmm-emmc.bin-flash_evk
```

This scans the binary for the functional command and status markers required by
automatic Popoto Discover AoE mode, U-Boot ext4 resizing, and the explicit
`finalize_flash` operation. Exit status `0` means support is present; exit
status `3` means the image is unsupported.

### Validate U-Boot Already On A PMM

When `--bootloader` will be omitted, inspect the currently active eMMC boot
slot:

```bash
popoto-discover check-active-bootloader \
  fe64bada09122316 \
  --interface enp1s0
```

This checks the active `boot0` or `boot1` image without rebooting the PMM. It
reports the eMMC `PARTITION_CONFIG`, active slot, present markers, and missing
markers.

### Validate The Complete Local Plan

`--dry-run` validates the WIC path, parses the selected bmap, and validates the
optional bootloader. It does not discover or contact any device:

```bash
popoto-discover flash fe64bada09122316 \
  ./pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader ./imx-boot-pmm-emmc.bin-flash_evk \
  --dry-run
```

Machine-readable dry run:

```bash
popoto-discover flash fe64bada09122316 \
  ./pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader ./imx-boot-pmm-emmc.bin-flash_evk \
  --dry-run --json
```

Run the dry run before an unattended batch so bad local paths and malformed
bmaps fail before any PMM is changed.

## Select The WIC Write Mode

### Automatic Bmap Selection

Given:

```text
/images/pmm-image-pmm.rootfs.wic.lz4
```

the CLI looks for:

```text
/images/pmm-image-pmm.rootfs.wic.bmap
```

If it exists, only the mapped WIC ranges are written:

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --interface enp1s0
```

This is normally the fastest mode.

### Explicit Bmap

Use a bmap with a different location or name:

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --bmap /manifests/pmm-image-pmm.rootfs.wic.bmap \
  --interface enp1s0
```

An explicit bmap must exist and parse successfully before device access.

### Full Image Without A Bmap

If no sibling bmap exists and `--bmap` is not provided, the CLI writes the
complete decompressed WIC automatically:

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --interface enp1s0
```

Use `--full` to force a full write even if a sibling bmap is present:

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --full \
  --interface enp1s0
```

`--full` and `--bmap` are mutually exclusive.

## Choose Whether To Update U-Boot

### Program U-Boot

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader /images/imx-boot-pmm-emmc.bin-flash_evk \
  --interface enp1s0
```

When the bootloader is supplied, Popoto Discover:

1. Validates the local image for PMM automatic AoE support.
2. Installs its bundled `mmc` and `uboot-flash` utilities on older rootfs
   images when necessary.
3. Uploads `imx-boot` and verifies the uploaded SHA-256.
4. Programs and byte-verifies the eMMC boot partitions.
5. Selects `boot0` as active.
6. Reads the active slot and independently verifies exactly the supplied image
   length against the local SHA-256.
7. Reboots and requires the expected U-Boot Popoto Discover/AoE response before
   writing the WIC.

The last step is the functional runtime check. Matching a binary marker alone
is not accepted as proof that the new U-Boot booted.

### Leave U-Boot Unchanged

Omit `--bootloader`:

```bash
popoto-discover flash TARGET \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --interface enp1s0
```

Before arming AoE mode, the CLI verifies that the currently active eMMC boot
slot already supports the required automatic workflow. An old U-Boot fails
before the PMM is rebooted into an unusable state.

### Recovery Override

`--allow-unsupported-bootloader` bypasses the local imx-boot marker check:

```bash
popoto-discover flash TARGET IMAGE \
  --bootloader IMX_BOOT \
  --allow-unsupported-bootloader \
  --interface enp1s0
```

Use this only for deliberate recovery work. It cannot make an unsupported
U-Boot provide automatic AoE, and the runtime check can still fail after the
bootloader is programmed.

### Use A Manually Started U-Boot AoE Export

Running this directly at the U-Boot prompt:

```text
aoe mmc 2
```

starts the default `e0.0` export. That differs from the CPU-specific target,
such as `e20865.221`, used by the automatic workflow. The mismatch is rejected
by default.

For exactly one physically verified board, authorize use of its current export:

```bash
popoto-discover flash TARGET IMAGE \
  --interface enp1s0 \
  --allow-single-target-aoe-fallback
```

This is not a blind `e0.0` write. Popoto Discover requires a U-Boot discovery
reply matching `TARGET`, obtains that reply's L2 source MAC, and pins AoE
discovery and all subsequent I/O to that MAC. U-Boot must also advertise the
`finalize_flash` capability so the rootfs can be expanded safely after the
write. The option is rejected when more than one target is selected, when the
source MAC cannot be proven, or when finalization is unavailable.

The desktop GUI presents the same authorization as a checkbox in the final
flash confirmation. The TUI presents a yes/no confirmation. All three front
ends then execute the same core resolver and flashing workflow.

## Flash Multiple PMMs

Put every CPU UID before the image path:

```bash
popoto-discover flash \
  TARGET_A TARGET_B TARGET_C \
  /images/pmm-image-pmm.rootfs.wic.lz4 \
  --bootloader /images/imx-boot-pmm-emmc.bin-flash_evk \
  --jobs 3 \
  --interface enp1s0
```

All targets receive the same WIC, bmap mode, and optional bootloader. Each unit
uses an AoE target derived from its CPU UID, so identical or random MAC
addresses are not used to distinguish units.

The default concurrency is `10`. In production, set `--jobs` explicitly:

- Start with the number of PMMs being flashed.
- Reduce it if the host NIC, switch uplink, or storage source is saturated.
- Use `--jobs 1` for diagnosis or recovery.

A failure returns a nonzero status for the batch. Preserve the complete output
so the failed target and phase are available for diagnosis.

## Understand A Successful Flash

The workflow completes these stages:

1. Resolve every target by CPU UID and choose an Ethernet interface.
2. Validate the active U-Boot, or program and verify the supplied imx-boot.
3. Preserve identity, license, and network files in a durable journal.
4. Arm one-shot automatic AoE mode and reboot.
5. Require the expected target-specific U-Boot discovery and AoE export.
6. Read LBA0 to prove the block device responds.
7. Stream bmap ranges or the full WIC with a pipelined AoE window.
8. Flush the AoE write cache.
9. Send the explicit `finalize_flash` command; U-Boot grows partition 2 and its
   ext4 filesystem, persists completion, clears AoE mode, and resets the unit.
10. Wait for Linux discovery.
11. Independently verify from Linux that partition 2 reaches the end of the
    eMMC and the mounted filesystem occupies at least 95% of that partition.
12. Restore preserved files and restart networking when needed.
13. Clear the one-shot AoE environment and durable preservation journal.

The process exits successfully only after Linux is rediscovered and storage
capacity verification passes. A completed progress bar by itself is not the
success condition.

## JSONL Automation Interface

Add `--json` to emit newline-delimited JSON on stdout:

```bash
popoto-discover flash TARGET IMAGE \
  --bootloader IMX_BOOT \
  --interface enp1s0 \
  --json
```

Each line is one complete JSON object. This is JSONL/NDJSON, not one JSON
array. Process it line by line and do not parse the human-readable output.

Record types:

| `type` | Purpose | Important fields |
| --- | --- | --- |
| `artifacts` | Resolved local plan | `image`, `mode`, `bmap`, `mapped_bytes`, `bootloader`, `bootloader_supported` |
| `dry_run` | Local validation result | `status`, `targets`, `device_accessed` |
| `target` | Resolved target setup | `target`, `interface`, `aoe_target` |
| `event` | Status or write progress | `target`, `phase`, `message`, `done_bytes`, `total_bytes` |
| `device` | Linux device found after reboot | `device_id`, `name`, `ip`, `fw` |
| `summary` | Successful final result | `status`, `rediscovered_devices` |

Example progress object:

```json
{"type":"event","target":"fe64bada09122316","phase":"write","message":"write: 42.0%  1056.0 MiB","done_bytes":1107296256,"total_bytes":2637852672}
```

Automation should use `done_bytes` and `total_bytes` for progress, not parse
the `message` string. Treat the command exit status as authoritative. A
`summary` with `"status":"ok"` is emitted only on success.

When `--allow-single-target-aoe-fallback` is requested, the initial `target`
record includes `"single_target_aoe_fallback":true`. If a different active
export is actually adopted, a later `event` records its AoE address and pinned
U-Boot MAC.

Example with `jq`:

```bash
popoto-discover flash "$TARGET" "$IMAGE" \
  --bootloader "$UBOOT" \
  --interface "$IFACE" \
  --json |
jq -c 'select(.type == "event" or .type == "summary")'
```

## Unattended Shell Script

This example validates first, writes a timestamped JSONL log, and prevents two
operators from flashing through the same host interface at once:

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

TARGET="${TARGET:?set TARGET to the PMM CPU UID}"
IMAGE="${IMAGE:?set IMAGE to the .wic.lz4 path}"
UBOOT="${UBOOT:-}"
IFACE="${IFACE:-enp1s0}"
LOG_DIR="${LOG_DIR:-/var/log/popoto-discover}"

mkdir -p "$LOG_DIR"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
log="$LOG_DIR/flash-${TARGET}-${timestamp}.jsonl"
lock="/tmp/popoto-discover-${IFACE}.lock"

args=(
  flash "$TARGET" "$IMAGE"
  --interface "$IFACE"
  --jobs 1
  --json
)
if [[ -n "$UBOOT" ]]; then
  args+=(--bootloader "$UBOOT")
fi

popoto-discover "${args[@]}" --dry-run

echo "Starting PMM flash; log: $log" >&2
flock "$lock" \
  popoto-discover "${args[@]}" |
  tee "$log"
```

Run it as:

```bash
TARGET=fe64bada09122316 \
IMAGE=/srv/pmm/pmm-image-pmm.rootfs.wic.lz4 \
UBOOT=/srv/pmm/imx-boot-pmm-emmc.bin-flash_evk \
IFACE=enp1s0 \
./flash-one-pmm.sh
```

If the host does not provide `flock`, remove the lock line and ensure
serialization in the calling system.

## Run Reliably Over SSH

The CLI runs on the host physically connected to the PMM Layer 2 network. An
SSH client can start it remotely, but a dropped SSH connection must not kill a
long flash.

Use `tmux`:

```bash
tmux new-session -d -s pmm-flash \
  "popoto-discover flash TARGET /srv/pmm/pmm-image-pmm.rootfs.wic.lz4 \
   --bootloader /srv/pmm/imx-boot-pmm-emmc.bin-flash_evk \
   --interface enp1s0 --jobs 1 --json \
   2>&1 | tee /srv/pmm/flash-TARGET.jsonl"
```

Attach to the live session:

```bash
tmux attach -t pmm-flash
```

Read the log without attaching:

```bash
tail -f /srv/pmm/flash-TARGET.jsonl
```

For a factory service, run the same command under systemd and use the process
exit status as the job result. Do not background only the Java process inside a
normal SSH shell; session cleanup can still terminate it.

## Exit Status

| Status | Meaning |
| --- | --- |
| `0` | Command completed successfully |
| `1` | Device, network, authentication, boot, AoE, restore, or flashing failure |
| `2` | Invalid command line or invalid/missing local input |
| `3` | Bootloader check found unsupported PMM AoE/discovery support |

Examples:

```bash
if popoto-discover check-bootloader "$UBOOT"; then
  echo "Bootloader accepted"
else
  rc=$?
  echo "Bootloader validation failed with status $rc" >&2
  exit "$rc"
fi
```

```bash
if ! popoto-discover flash "$TARGET" "$IMAGE" \
  --interface "$IFACE" --json >"$LOG"; then
  echo "Flash failed; retain $LOG and inspect the PMM serial console" >&2
  exit 1
fi
```

## Authentication

The built-in Popoto secret is used by default:

```bash
popoto-discover discover --transport all --interface enp1s0
```

Use a site-specific secret:

```bash
popoto-discover --secret-file /secure/site.secret \
  discover --transport all --interface enp1s0
```

`--secret-file` and `--no-auth` are global options and must appear before the
command. `--no-auth` is intended only for explicitly unauthenticated legacy or
lab deployments.

## Troubleshooting

### Target Was Not Discovered

1. Confirm the cable, PMM power, and switch port.
2. Confirm the interface name.
3. Probe both transports:

   ```bash
   popoto-discover discover --transport all \
     --interface enp1s0 --timeout 8 --retries 8
   ```

4. Check raw-packet permissions on the host.
5. If the modem-side client is absent, install it by known IP:

   ```bash
   popoto-discover install-client 10.1.0.228
   ```

### Raw Ethernet Or Pcap Interface Is Unavailable

Run the packaged installer’s L2 permission setup. On macOS this is the
one-time BPF access installation; on Linux the packaged launcher is granted raw
network capabilities; on Windows the package installs the required packet
transport driver. Then close and reopen the terminal/application.

### Bmap Was Not Found

- Correct the path supplied to `--bmap`.
- Put `IMAGE.wic.bmap` beside `IMAGE.wic.lz4` for automatic selection.
- Omit `--bmap` when a bmap is unavailable.
- Use `--full` to explicitly request a full-image write.

### Active U-Boot Is Unsupported

Supply a current, validated imx-boot:

```bash
popoto-discover flash TARGET IMAGE \
  --bootloader IMX_BOOT \
  --interface IFACE
```

Do not set the AoE environment manually and retry with the same old U-Boot.

### Timed Out Waiting For U-Boot AoE

The expected target-specific U-Boot discovery/AoE export did not appear. Keep
the entire JSONL log. Check the PMM serial console for the active boot slot,
`pmm_aoe_flash`, `pmm_aoe_major`, `pmm_aoe_minor`, Ethernet negotiation, and
the exported AoE target.

### U-Boot Is Exporting e0.0 Or Another Unexpected Target

If only one physically verified board is selected, use
`--allow-single-target-aoe-fallback`. For multiple boards, boot Linux and use
the normal automatic path so every board receives its CPU-specific AoE target.
Never use a shared default target as an unpinned multi-board fallback.

### Flash Wrote Data But The Command Failed Later

Do not call the unit provisioned merely because all bytes were sent. The
workflow also requires flush, U-Boot finalization, Linux boot, rediscovery,
full-capacity rootfs verification, preserved-file restore, and AoE environment
cleanup. Retain the log and diagnose the phase that failed.

### Recovery And Safe Retry

- Use `--jobs 1`.
- Run discovery again and record whether the PMM is in Linux or U-Boot.
- If it is already exporting the expected AoE target, the workflow can resume
  from that state.
- If Linux is available, rerun `check-active-bootloader` before omitting
  `--bootloader`.
- Do not remove power while a boot partition or WIC write is active.

## Complete Flash Option Reference

```text
popoto-discover flash TARGET [TARGET ...] IMAGE [options]

--bmap PATH
    Write only ranges described by this bmap.

--full
    Write the complete decompressed WIC. Cannot be combined with --bmap.

--bootloader PATH
    Program and verify this imx-boot before writing the WIC.

--allow-unsupported-bootloader
    Override failed local bootloader marker validation. Recovery only.

--allow-single-target-aoe-fallback
    For exactly one selected board, authorize its current mismatched U-Boot
    AoE export. The exporter is pinned to its discovered L2 source MAC.

-i, --interface NAME
    Host Ethernet interface used for discovery, console traffic, and AoE.

--jobs N
    Maximum concurrent target flashes. Default: 10.

--timeout SECONDS
    Management/discovery reply timeout.

--dry-run
    Validate local artifacts and print the plan without device access.

--json
    Emit JSONL records for automation.
```

Use the built-in help for the exact options in the installed build:

```bash
popoto-discover --help
popoto-discover version
```
