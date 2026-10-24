<#
.SYNOPSIS
  Fetch bionic-linked command line tools and stage them as lib*.so for the APK.

.DESCRIPTION
  Downloads Termux packages (already built against bionic), extracts the
  binaries, renames them to the lib*.so shape the Android installer unpacks,
  and neutralises their hardcoded DT_RUNPATH.

  Run from the repository root:
      pwsh tools/fetch-prebuilts.ps1 -Abi arm64-v8a -Packages busybox,git

.NOTES
  Requires: tar (built into Windows 10+), and Python 3 for the ELF patch step.
  The script is intentionally explicit rather than clever — you are putting
  third-party binaries into your APK and should be able to read what it does.
#>
param(
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',

    [string[]]$Packages = @('busybox'),

    [string]$Repo = 'https://packages.termux.dev/apt/termux-main'
)

$ErrorActionPreference = 'Stop'

$termuxArch = switch ($Abi) {
    'arm64-v8a'   { 'aarch64' }
    'armeabi-v7a' { 'arm' }
    'x86_64'      { 'x86_64' }
}

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "native/prebuilt/$Abi"
$workDir = Join-Path $env:TEMP "apkcli-prebuilt-$Abi"

New-Item -ItemType Directory -Force -Path $outDir, $workDir | Out-Null
Write-Host "[apkcli] staging into $outDir" -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# 1. Resolve package versions from the repository index.
# ---------------------------------------------------------------------------
$packagesIndexUrl = "$Repo/dists/stable/main/binary-$termuxArch/Packages"
$indexPath = Join-Path $workDir 'Packages'
Write-Host "[apkcli] fetching package index"
Invoke-WebRequest -Uri $packagesIndexUrl -OutFile $indexPath -UseBasicParsing

$index = Get-Content $indexPath -Raw
$stanzas = $index -split "`n`n"

function Get-PackagePath([string]$name) {
    foreach ($stanza in $stanzas) {
        if ($stanza -match "(?m)^Package:\s*$([regex]::Escape($name))\s*$") {
            if ($stanza -match '(?m)^Filename:\s*(.+?)\s*$') {
                return $Matches[1]
            }
        }
    }
    return $null
}

# ---------------------------------------------------------------------------
# 2. Download + unpack each .deb (ar archive containing data.tar.xz).
# ---------------------------------------------------------------------------
foreach ($pkg in $Packages) {
    $relPath = Get-PackagePath $pkg
    if (-not $relPath) {
        Write-Warning "package '$pkg' not found in index for $termuxArch — skipping"
        continue
    }

    $debUrl = "$Repo/$relPath"
    $debPath = Join-Path $workDir "$pkg.deb"
    Write-Host "[apkcli] downloading $pkg" -ForegroundColor Cyan
    Invoke-WebRequest -Uri $debUrl -OutFile $debPath -UseBasicParsing

    $extractDir = Join-Path $workDir $pkg
    Remove-Item -Recurse -Force $extractDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

    # tar on Windows 10+ understands .deb (ar) only via bsdtar's auto-detect,
    # which it has. Two passes: outer ar, then the inner data tarball.
    tar -xf $debPath -C $extractDir
    $dataArchive = Get-ChildItem $extractDir -Filter 'data.tar.*' | Select-Object -First 1
    if (-not $dataArchive) { throw "no data.tar.* inside $pkg.deb" }
    tar -xf $dataArchive.FullName -C $extractDir

    # ---- 3. Stage binaries as lib*.so ------------------------------------
    $binRoot = Join-Path $extractDir 'data/data/com.termux/files/usr/bin'
    $libRoot = Join-Path $extractDir 'data/data/com.termux/files/usr/lib'

    if (Test-Path $binRoot) {
        Get-ChildItem $binRoot -File | ForEach-Object {
            $target = Join-Path $outDir ("lib" + $_.BaseName + ".so")
            Copy-Item $_.FullName $target -Force
            Write-Host "    bin  $($_.Name) -> $(Split-Path -Leaf $target)"
        }
    }
    if (Test-Path $libRoot) {
        # Shared dependencies keep their own names; they are already lib*.so.
        Get-ChildItem $libRoot -File -Filter '*.so*' | ForEach-Object {
            # Android's installer only unpacks exactly "*.so", so versioned
            # names like libcurl.so.4 must be flattened.
            $flat = ($_.Name -replace '\.so\..*$', '.so')
            Copy-Item $_.FullName (Join-Path $outDir $flat) -Force
            Write-Host "    lib  $($_.Name) -> $flat"
        }
    }
}

# ---------------------------------------------------------------------------
# 4. Zero DT_RPATH / DT_RUNPATH in place.
# ---------------------------------------------------------------------------
$patchScript = Join-Path $PSScriptRoot 'zero-runpath.py'
if (Test-Path $patchScript) {
    $python = (Get-Command python, python3 -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($python) {
        Get-ChildItem $outDir -Filter '*.so' | ForEach-Object {
            & $python.Source $patchScript $_.FullName
        }
    } else {
        Write-Warning "python not found — DT_RUNPATH not neutralised. Binaries may fail to load."
    }
}

Write-Host "[apkcli] done. Rebuild the APK to pick these up." -ForegroundColor Green
Write-Host "[apkcli] verify with: readelf -d <file> | Select-String RUNPATH"
