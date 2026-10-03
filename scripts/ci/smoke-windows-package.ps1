param([string]$ExpectedVersion = $env:PACKAGE_VERSION)
$ErrorActionPreference = 'Stop'
if (-not $ExpectedVersion) { throw 'Expected package version is required.' }
$packages = @(Get-ChildItem 'build\jpackage\installer' -Filter '*.msi')
if ($packages.Count -ne 1) { throw 'Expected exactly one Windows MSI.' }
$logDirectory = New-Item -ItemType Directory -Force 'build\package-smoke'
$log = Join-Path $logDirectory.FullName 'msi-install.log'
$process = Start-Process msiexec.exe -ArgumentList @('/i', ('"' + $packages[0].FullName + '"'), '/qn', '/norestart', '/L*v', ('"' + $log + '"')) -Wait -PassThru
if ($process.ExitCode -notin @(0, 3010)) { throw "MSI installation failed: $($process.ExitCode). See $log" }

$app = Join-Path $env:ProgramFiles 'Popoto Discover'
$version = & (Join-Path $app 'popoto-discover.exe') version
if ($LASTEXITCODE -ne 0) { throw 'Installed CLI failed to launch.' }
$version | Write-Output
if ($version -notcontains "package: $ExpectedVersion") { throw 'Installed package version does not match the build.' }
& (Join-Path $app 'popoto-discover.exe') discover --transport udp --timeout 0.2 --retries 1
if ($LASTEXITCODE -ne 0) { throw 'Installed UDP discovery failed.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead((Join-Path $app 'app\popoto-discover.jar'))
try {
    $scriptEntry = $jar.GetEntry('tools/uboot-flash')
    if (-not $scriptEntry) { throw 'Installed package is missing uboot-flash.' }
    $reader = [IO.StreamReader]::new($scriptEntry.Open())
    try { $scriptText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if (-not $scriptText.StartsWith("#!/bin/sh`n") -or $scriptText.Contains("`r")) {
        throw 'Installed uboot-flash must use Linux LF line endings.'
    }
    foreach ($name in @('SeLow_x64.inf', 'SeLow_x64.sys', 'SeLow_Win10_x64.cat', 'install.ps1', 'LICENSE.txt', 'NOTICE.txt')) {
        $entry = $jar.GetEntry("windows/selow/$name")
        if (-not $entry) { throw "Installed package is missing $name" }
        $path = Join-Path $logDirectory.FullName $name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $path, $true)
        $source = Join-Path 'packaging\windows\selow' $name
        if ((Get-FileHash $path).Hash -ne (Get-FileHash $source).Hash) { throw "Installed file differs from verified source: $name" }
    }
} finally { $jar.Dispose() }
foreach ($name in @('LICENSE.txt', 'NOTICE.txt')) {
    if (-not (Test-Path (Join-Path $app "app\licenses\softether-selow\$name"))) { throw "Installed driver attribution is missing: $name" }
}
Write-Output 'MSI installation, bundled runtime, UDP discovery, Linux script, and embedded signed driver files pass.'
