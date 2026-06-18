param(
    [string]$DriverDirectory = "",
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($DriverDirectory)) {
    $DriverDirectory = Join-Path $Root "sys\630"
}

$Inf = Join-Path $DriverDirectory "pmmndis630.inf"
if (-not (Test-Path $Inf)) {
    throw "Missing PMM NDIS INF: $Inf"
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "PMM NDIS install must be run from an elevated PowerShell session."
}

pnputil.exe /add-driver $Inf /install
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (-not $NoStart) {
    sc.exe start PmmNdis | Out-Host
}

$device = "\\.\PmmNdis"
$handle = [System.IO.File]::Open($device, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::ReadWrite)
$handle.Dispose()
Write-Host "PMM NDIS driver is installed and $device opens."
