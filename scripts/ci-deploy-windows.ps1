#Requires -Version 5.1
<#
.SYNOPSIS
    CI deploy step for ArtMoon Windows (Qt 6.8.3, msvc2022_64).

.DESCRIPTION
    Replicates FoggyBytes' manual build-release.ps1 recipe, adapted for a
    headless GitHub Actions runner:
      1. build-arch.bat release (its own windeployqt step fails on Qt 6.8.3 —
         the --no-quickcontrols2fluentwinui3styleimpl flag is unsupported there;
         this is EXPECTED and ignored as long as ArtMoon.exe was produced)
      2. windeployqt run separately with the Qt 6.8.3-compatible flag set
      3. libs/windows/lib/x64 DLLs + AntiHooking.dll + gamecontrollerdb.txt
         copied into the deploy folder
      4. unused Qt Quick Controls styles pruned
    The final folder (build/deploy-x64-release) is what ArtMoon.iss packages.
#>
$ErrorActionPreference = 'Stop'

$RepoRoot    = $PSScriptRoot | Split-Path
$QtBinPath   = $env:ARTMOON_QT_BIN
if (-not $QtBinPath) { $QtBinPath = 'C:\Qt\6.8.3\msvc2022_64\bin' }
$BuildArchBat = Join-Path $RepoRoot 'scripts\build-arch.bat'
$CompiledExe  = Join-Path $RepoRoot 'build\build-x64-release\app\release\ArtMoon.exe'
$DeployFolder = Join-Path $RepoRoot 'build\deploy-x64-release'
$LibsFolder   = Join-Path $RepoRoot 'libs\windows\lib\x64'
$QmlDir       = Join-Path $RepoRoot 'app\gui'

function Fail { param($msg) Write-Host "##vso[task.logissue type=error]$msg"; exit 1 }
function Ok   { param($msg) Write-Host "[OK] $msg" }

# ---------------------------------------------------------------------------
# Step 1 — PATH
# ---------------------------------------------------------------------------
$env:PATH = "$QtBinPath;$env:PATH"

# ---------------------------------------------------------------------------
# Step 2 — Build (build-arch.bat release). Known-wrong exit code tolerated.
# ---------------------------------------------------------------------------
Push-Location $RepoRoot
cmd /c "`"$BuildArchBat`" release" 2>&1 | Write-Host
$batExit = $LASTEXITCODE
Pop-Location

if (-not (Test-Path $CompiledExe)) {
    Fail "build-arch.bat failed AND ArtMoon.exe was not produced (bat exit: $batExit)"
}
Ok "ArtMoon.exe produced (bat exit $batExit tolerated — expected windeployqt flag issue on Qt 6.8.3)"

# ---------------------------------------------------------------------------
# Step 3 — windeployqt with Qt 6.8.3-compatible flags
# ---------------------------------------------------------------------------
$windeployArgs = @(
    '--release'
    '--qmldir', $QmlDir
    '--no-opengl-sw'
    '--no-compiler-runtime'
    '--no-sql'
    '--no-system-d3d-compiler'
    '--no-system-dxc-compiler'
    '--skip-plugin-types', 'qmltooling,generic'
    '--no-ffmpeg'
    '--no-quickcontrols2fusion'
    '--no-quickcontrols2imagine'
    '--no-quickcontrols2universal'
    '--dir', $DeployFolder
    $CompiledExe
)

& (Join-Path $QtBinPath 'windeployqt.exe') @windeployArgs
if ($LASTEXITCODE -ne 0) { Fail "windeployqt failed with exit $LASTEXITCODE" }
Ok "windeployqt complete"

# ---------------------------------------------------------------------------
# Step 4 — libs DLLs (SDL2, ssl, crypto, avcodec, opus, placebo, ...)
# ---------------------------------------------------------------------------
$libDlls = Get-ChildItem -Path $LibsFolder -Filter '*.dll' -ErrorAction SilentlyContinue
if ($libDlls.Count -eq 0) { Fail "No DLLs found in $LibsFolder" }
foreach ($dll in $libDlls) { Copy-Item $dll.FullName -Destination $DeployFolder -Force }
Ok "$($libDlls.Count) DLLs copied from libs"

# ---------------------------------------------------------------------------
# Step 5 — AntiHooking.dll + gamecontrollerdb.txt
# ---------------------------------------------------------------------------
$antiHook = Join-Path $RepoRoot 'build\build-x64-release\AntiHooking\release\AntiHooking.dll'
if (Test-Path $antiHook) { Copy-Item $antiHook -Destination $DeployFolder -Force; Ok "AntiHooking.dll copied" }
else { Fail "AntiHooking.dll missing at $antiHook" }

$gcDb = Join-Path $RepoRoot 'app\SDL_GameControllerDB\gamecontrollerdb.txt'
Copy-Item $gcDb -Destination $DeployFolder -Force
Ok "gamecontrollerdb.txt copied"

# ---------------------------------------------------------------------------
# Step 6 — ensure ArtMoon.exe itself is in the deploy folder
# ---------------------------------------------------------------------------
Copy-Item $CompiledExe -Destination $DeployFolder -Force

# ---------------------------------------------------------------------------
# Step 7 — prune unused Qt Quick Controls styles
# ---------------------------------------------------------------------------
foreach ($rel in @(
    'qml\QtQuick\Controls\Fusion',
    'qml\QtQuick\Controls\Imagine',
    'qml\QtQuick\Controls\Universal',
    'qml\QtQuick\Controls\Windows',
    'qml\QtQuick\Controls\FluentWinUI3',
    'qml\QtQuick\Controls\NativeStyle')) {
    $p = Join-Path $DeployFolder $rel
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
Ok "Unused Qt styles pruned"

$exe = Join-Path $DeployFolder 'ArtMoon.exe'
if (-not (Test-Path $exe)) { Fail "deploy folder missing ArtMoon.exe" }
$count = (Get-ChildItem -Recurse -File $DeployFolder).Count
Ok "Deploy folder ready: $DeployFolder ($count files)"
