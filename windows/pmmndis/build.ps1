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

function Add-UniquePathList {
    param(
        [string[]]$Prefix,
        [string]$Existing
    )

    $items = @()
    foreach ($item in $Prefix) {
        if (-not [string]::IsNullOrWhiteSpace($item)) {
            $items += $item
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($Existing)) {
        $items += @($Existing.Split(";") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }

    return ($items | Select-Object -Unique) -join ";"
}

function Resolve-Wdk {
    param(
        [ValidateSet("x64", "ARM64")]
        [string]$TargetPlatform
    )

    $roots = @()
    if (-not [string]::IsNullOrWhiteSpace($env:WindowsSdkDir)) {
        $roots += $env:WindowsSdkDir.TrimEnd("\")
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $roots += Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10"
    }

    $libArch = if ($TargetPlatform -eq "ARM64") { "arm64" } else { "x64" }

    foreach ($root in ($roots | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
        $includeRoot = Join-Path $root "Include"
        if (-not (Test-Path $includeRoot)) {
            continue
        }

        $versions = Get-ChildItem $includeRoot -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                try {
                    [pscustomobject]@{
                        Version = [version]$_.Name
                        Name = $_.Name
                        Path = $_.FullName
                    }
                } catch {
                    $null
                }
            } |
            Where-Object { $_ -ne $null } |
            Sort-Object Version -Descending

        foreach ($version in $versions) {
            $km = Join-Path $version.Path "km"
            $shared = Join-Path $version.Path "shared"
            $um = Join-Path $version.Path "um"
            $ucrt = Join-Path $version.Path "ucrt"
            $kmLib = Join-Path $root "Lib\$($version.Name)\km\$libArch"
            $binDir = Join-Path $root "bin\$($version.Name)\x64"

            if (
                (Test-Path (Join-Path $km "ndis.h")) -and
                (Test-Path (Join-Path $shared "ntdef.h")) -and
                (Test-Path (Join-Path $kmLib "ndis.lib")) -and
                (Test-Path (Join-Path $kmLib "wdmsec.lib"))
            ) {
                return [pscustomobject]@{
                    Root = $root
                    Version = $version.Name
                    IncludeDirs = @($km, $shared, $um, $ucrt)
                    KmLib = $kmLib
                    BinDir = $binDir
                }
            }
        }
    }

    throw "Windows Driver Kit headers/libs were not found. Install the Windows Driver Kit with kernel-mode headers and libraries."
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

$wdk = Resolve-Wdk -TargetPlatform $Platform
$env:WindowsSdkDir = "$($wdk.Root)\"
$env:WindowsSDKDir = "$($wdk.Root)\"
$env:WindowsTargetPlatformVersion = $wdk.Version
$env:DDK_LIB_PATH = $wdk.KmLib
$env:INCLUDE = Add-UniquePathList -Prefix $wdk.IncludeDirs -Existing $env:INCLUDE
$env:LIB = Add-UniquePathList -Prefix @($wdk.KmLib) -Existing $env:LIB

Write-Host "Using MSBuild: $msbuildPath"
Write-Host "Using WDK: $($wdk.Root) ($($wdk.Version))"
Write-Host "Using WDK KM lib: $($wdk.KmLib)"

$msbuildArgs = @(
    $Project,
    "/p:Configuration=$Configuration",
    "/p:Platform=$Platform",
    "/p:WindowsTargetPlatformVersion=$($wdk.Version)",
    "/p:WDKContentRoot=$($wdk.Root)\",
    "/p:DDK_LIB_PATH=$($wdk.KmLib)",
    "/m"
)

& $msbuildPath @msbuildArgs

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

$inf2Cat = $null
$preferredInf2Cat = Join-Path $wdk.BinDir "Inf2Cat.exe"
if (Test-Path $preferredInf2Cat) {
    $inf2Cat = Get-Item $preferredInf2Cat
}

if (-not $inf2Cat) {
    $inf2Cat = Get-ChildItem (Join-Path $wdk.Root "bin") -Recurse -File -Filter "Inf2Cat.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\x64\\Inf2Cat\.exe$" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
}

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
