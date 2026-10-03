$ErrorActionPreference = 'Stop'
$env:PSModulePath = "$PSHOME\Modules;$env:PSModulePath"
$hashes = @{
    'SeLow_x64.inf' = '24a51686aa7bfd7c335ac87a92d913632c65535e915db21624f1aac0c07036ea'
    'SeLow_x64.sys' = 'a4d553f8a1fb2d665f72dfe11725a7d848e847f155a25f13389d4674b905368f'
    'SeLow_Win10_x64.cat' = 'cd33b3dbd773f1a2f8cc2fbb41d007579a74239db45850c49b4a503deed75b17'
}
foreach ($name in $hashes.Keys) {
    $path = Join-Path $PSScriptRoot $name
    if ((Get-FileHash $path -Algorithm SHA256).Hash -ne $hashes[$name]) {
        throw "Bundled Ethernet driver hash mismatch: $name"
    }
}
$catalog = Get-AuthenticodeSignature (Join-Path $PSScriptRoot 'SeLow_Win10_x64.cat')
if ($catalog.Status -ne 'Valid' -or $catalog.SignerCertificate.Subject -notlike '*Microsoft Windows Hardware Compatibility Publisher*') {
    throw 'The Ethernet driver catalog must have a valid Microsoft signature.'
}
Write-Output 'Pinned Ethernet driver files and Microsoft catalog signature verified.'
