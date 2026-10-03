param([Parameter(Mandatory = $true)][string]$InstallerDirectory)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$packages = @(Get-ChildItem $InstallerDirectory -Filter '*.msi')
if ($packages.Count -ne 1) { throw 'Expected exactly one Windows MSI.' }
$database = $installer.OpenDatabase($packages[0].FullName, 1)

function Read-Property([string]$name) {
    $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$name'")
    $view.Execute() | Out-Null
    $record = $view.Fetch()
    if ($record) { return ([string]$record.StringData(1)).Trim() }
    return ''
}

$version = Read-Property 'ProductVersion'
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid MSI version.' }
$legacyCode = '{9C31E6B4-2292-39C3-B527-E9223E69D08F}'
$property = 'POPOTO_LEGACY_UPGRADE'
$view = $database.OpenView("DELETE FROM ``Upgrade`` WHERE ``UpgradeCode`` = '$legacyCode'")
$view.Execute() | Out-Null
$view = $database.OpenView("INSERT INTO ``Upgrade`` (``UpgradeCode``, ``VersionMax``, ``Attributes``, ``ActionProperty``) VALUES ('$legacyCode', '$version', 1, '$property')")
$view.Execute() | Out-Null
$secure = Read-Property 'SecureCustomProperties'
$secure = (@($secure -split ';' | Where-Object { $_ }) + $property | Select-Object -Unique) -join ';'
$view = $database.OpenView("UPDATE ``Property`` SET ``Value`` = '$secure' WHERE ``Property`` = 'SecureCustomProperties'")
$view.Execute() | Out-Null
$database.Commit() | Out-Null
Write-Output 'Windows MSI migrates the legacy Popoto Discover installer.'
