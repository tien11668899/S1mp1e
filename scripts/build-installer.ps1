# Build the S1mp1e Windows installer.
#
#   1. `dotnet publish` the Avalonia app self-contained for win-x64 (bundles the
#      .NET runtime + itest.exe, so the target machine needs nothing preinstalled).
#   2. Compile installer\S1mp1e.iss with Inno Setup's ISCC into dist\S1mp1e-Setup-<ver>.exe.
#
# Usage:  pwsh scripts\build-installer.ps1
# The version comes from avalonia\S1mp1e.Avalonia.csproj (<Version>).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$csproj = Join-Path $root 'avalonia\S1mp1e.Avalonia.csproj'
$iss = Join-Path $root 'installer\S1mp1e.iss'
$publishDir = Join-Path $root 'dist\publish'
$distDir = Join-Path $root 'dist'

# --- version from the csproj (single source of truth) ---
[xml]$proj = Get-Content $csproj
$version = ($proj.Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1)
if (-not $version) { throw "No <Version> in $csproj" }
Write-Host "S1mp1e version: $version"

# --- locate ISCC ---
$iscc = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "ISCC.exe (Inno Setup 6) not found — install Inno Setup." }

# --- 1) publish self-contained ---
if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
Write-Host "Publishing (self-contained win-x64)..."
dotnet publish $csproj -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=false -o $publishDir --nologo -v quiet
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed" }
if (-not (Test-Path (Join-Path $publishDir 'S1mp1e.exe'))) { throw "publish produced no S1mp1e.exe" }
if (-not (Test-Path (Join-Path $publishDir 'itest.exe'))) { throw "publish produced no itest.exe" }

# --- 2) compile the installer ---
New-Item -ItemType Directory -Force $distDir | Out-Null
Write-Host "Compiling installer..."
& $iscc "/DVersion=$version" "/DPublishDir=$publishDir" $iss
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }

$setup = Join-Path $distDir "S1mp1e-Setup-$version.exe"
if (-not (Test-Path $setup)) { throw "installer not produced at $setup" }
Write-Host "OK -> $setup  ($([math]::Round((Get-Item $setup).Length/1MB,1)) MB)"
