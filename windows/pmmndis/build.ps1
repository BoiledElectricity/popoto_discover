param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",

    [ValidateSet("x64", "ARM64")]
    [string]$Platform = "x64"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Project = Join-Path $Root "sys\630\ndisprot630.vcxproj"

if (-not (Test-Path $Project)) {
    throw "Missing driver project: $Project"
}

$msbuild = Get-Command msbuild.exe -ErrorAction SilentlyContinue
if (-not $msbuild) {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $msbuildPath = & $vswhere `
            -latest `
            -products * `
            -requires Microsoft.Component.MSBuild `
            -find "MSBuild\**\Bin\MSBuild.exe" |
            Select-Object -First 1
    }

    if (-not $msbuildPath) {
        $candidates = @(
            "${env:ProgramFiles}\Microsoft Visual Studio\2022\Enterprise\MSBuild\Current\Bin\MSBuild.exe",
            "${env:ProgramFiles}\Microsoft Visual Studio\2022\Professional\MSBuild\Current\Bin\MSBuild.exe",
            "${env:ProgramFiles}\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe",
            "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe"
        )
        $msbuildPath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    }

    if (-not $msbuildPath) {
        throw "MSBuild was not found. Install Visual Studio Build Tools plus the Windows Driver Kit. PATH=$env:PATH"
    }
} else {
    $msbuildPath = $msbuild.Source
}

Write-Host "Using MSBuild: $msbuildPath"

& $msbuildPath $Project `
    /p:Configuration=$Configuration `
    /p:Platform=$Platform `
    /m

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$DriverRoot = Join-Path $Root "sys\630"
$PackageDir = Join-Path $Root "build\package\$Platform"
New-Item -ItemType Directory -Force $PackageDir | Out-Null

$sys = Get-ChildItem $DriverRoot -Recurse -File -Filter "pmmndis630.sys" | Select-Object -First 1
if (-not $sys) {
    throw "Driver build did not produce pmmndis630.sys under $DriverRoot"
}

Copy-Item (Join-Path $DriverRoot "pmmndis630.inf") $PackageDir -Force
Copy-Item $sys.FullName (Join-Path $PackageDir "pmmndis630.sys") -Force

$inf2Cat = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Recurse -File -Filter "Inf2Cat.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\x64\\Inf2Cat\.exe$" } |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if (-not $inf2Cat) {
    throw "Inf2Cat.exe was not found. Install the Windows Driver Kit."
}

$osTarget = if ($Platform -eq "ARM64") { "10_ARM64" } else { "10_X64" }
& $inf2Cat.FullName /driver:$PackageDir /os:$osTarget
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

foreach ($name in @("pmmndis630.inf", "pmmndis630.sys", "pmmndis630.cat")) {
    $file = Join-Path $PackageDir $name
    if (-not (Test-Path $file)) {
        throw "Driver package is missing $name"
    }
}

Get-ChildItem -Path $PackageDir -File | Select-Object FullName, Length
