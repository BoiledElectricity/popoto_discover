$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Windows Ethernet setup requires administrator approval.'
}

$service = Get-Service SeLow -ErrorAction SilentlyContinue
if (-not $service) {
    $catalog = Get-AuthenticodeSignature (Join-Path $PSScriptRoot 'SeLow_Win10_x64.cat')
    if ($catalog.Status -ne 'Valid' -or $catalog.SignerCertificate.Subject -notlike '*Microsoft Windows Hardware Compatibility Publisher*') {
        throw 'The bundled Ethernet driver catalog does not have a valid Microsoft signature.'
    }
    & "$env:SystemRoot\System32\netcfg.exe" -l (Join-Path $PSScriptRoot 'SeLow_x64.inf') -c p -i SeLow
    $code = $LASTEXITCODE
    if ($code -eq 3010 -or $code -eq 0x0004A020) {
        Write-Output 'Windows requires a restart to finish installing the Ethernet driver.'
        exit 3010
    }
    if ($code -ne 0) { throw "Windows Ethernet driver installation failed (netcfg exit $code)." }
}
Start-Service SeLow
if ((Get-Service SeLow).Status -ne 'Running') { throw 'The Windows Ethernet driver did not start.' }
Write-Output 'Windows Ethernet driver installed and running.'
