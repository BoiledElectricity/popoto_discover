# PMM NDIS Raw Ethernet Driver

This directory contains the Windows raw Ethernet backend for Popoto Discover.
It is derived from Microsoft's `ndisprot` WDK sample and is kept under the
Microsoft Public License in `LICENSE-MS-PL.txt`.

The driver exposes `\\.\PmmNdis` to user mode. Popoto Discover opens that device
directly on Windows and uses it for the same raw Ethernet paths used on macOS and
Linux:

- `0x88A2`: ATA over Ethernet flashing
- `0x88B5`: PMM U-Boot Ethernet console
- `0x88B6`: PMM L2 discovery

The original sample accepted one EtherType. The PMM driver accepts only the three
PMM protocols above, validates that transmitted frames use the selected adapter's
source MAC address, and leaves all higher-level flashing logic in Kotlin.

## Build

Build on Windows with Visual Studio Build Tools and the Windows Driver Kit:

```powershell
.\windows\pmmndis\build.ps1 -Configuration Release -Platform x64
```

The active project is `sys\630\ndisprot630.vcxproj`, which emits
`pmmndis630.sys` and uses `sys\630\pmmndis630.inf`.

## Install For Local Testing

The package must be installed from an elevated PowerShell session:

```powershell
.\windows\pmmndis\install.ps1
```

The script calls `pnputil /add-driver ... /install`, starts the `PmmNdis`
service, and verifies that `\\.\PmmNdis` opens.

## Release Signing

Normal Windows machines require a properly signed driver package. The source
tree and scripts are enough for development and CI build work, but production
release still needs Microsoft-compatible driver signing or attestation before
the MSI can install the driver without developer/test-signing setup.

## Provenance

The imported Microsoft sample README is preserved as
`MICROSOFT-NDISPROT-README.md`.
