# Builds ruru-ble-win.exe, Ruru's Bluetooth LE helper for Windows (C++/WinRT, C++20).
# Needs the Visual Studio Build Tools (or Visual Studio) with the C++ workload and a Windows 10/11
# SDK; GitHub's windows-latest image has both. Finds them with vswhere, so no developer prompt is
# needed. The C runtime is linked in (/MT), so the exe runs on any Windows 10 or 11 PC as it is.
#
#   pwsh platform/windows/helper/build.ps1                      -> platform/windows/helper/build/ruru-ble-win.exe
#   pwsh platform/windows/helper/build.ps1 -Out <path\to.exe>   -> also copied there (CI: the app's resources)
param([string]$Out)
$ErrorActionPreference = 'Stop'

$here = $PSScriptRoot
$build = Join-Path $here 'build'
New-Item -ItemType Directory -Force $build | Out-Null

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw 'vswhere.exe not found: install the Visual Studio Build Tools with the C++ workload' }
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'No Visual Studio installation with the C++ x64 tools' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
# vcvars64 looks for vswhere on PATH itself.
$env:PATH = (Split-Path -Parent $vswhere) + ';' + $env:PATH

$src = Join-Path $here 'ruru-ble-win.cpp'
$exe = Join-Path $build 'ruru-ble-win.exe'
# /permissive- and C++20 as C++/WinRT expects; /GL + /LTCG and /OPT for a small exe; static CRT.
$cl = "cl /nologo /std:c++20 /EHsc /permissive- /W4 /O1 /GL /MT /DNDEBUG /DWIN32_LEAN_AND_MEAN /DNOMINMAX " +
      "/Fo`"$build\\`" /Fe`"$exe`" `"$src`" /link /LTCG /OPT:REF /OPT:ICF windowsapp.lib bcrypt.lib"
cmd /c "`"$vcvars`" >nul && $cl"
if ($LASTEXITCODE -ne 0) { throw "The helper did not build (cl exit $LASTEXITCODE)" }

# Its own checks (UUID parsing, the firmware-service refusal, opaque keys). Touches no Bluetooth.
$check = & $exe selftest
if ($LASTEXITCODE -ne 0 -or $check -ne 'selftest OK') { throw "The helper's self-test failed: $check" }

$size = (Get-Item $exe).Length
Write-Host ("Built {0} ({1:N0} KB), self-test OK" -f $exe, ($size / 1KB))
if ($Out) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
    Copy-Item $exe $Out -Force
    Write-Host "Copied to $Out"
}
