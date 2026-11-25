# Proves a built setup works the way the updater uses it: installs silently for the current user, the
# files and the Start menu shortcut and the Settings > Apps entry exist, Ruru starts in demo mode, a
# second silent run with /RELAUNCH replaces the running copy and starts it again, and a silent
# uninstall removes the folder. Used by .github/workflows/build.yml; also runnable by hand:
#
#   pwsh packaging\windows\check-setup.ps1 -Setup <Ruru-x.y.z-setup.exe> [-ExtraArgs /DRYRUNMSI]
#
# On a PC that has the MSI copy of Ruru, pass -ExtraArgs /DRYRUNMSI or setup removes that copy.
param(
    [Parameter(Mandatory)][string]$Setup,
    [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = 'Stop'
$silent = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') + $ExtraArgs
$app = Join-Path $env:LOCALAPPDATA 'Programs\Ruru'
$exe = Join-Path $app 'Ruru.exe'
$shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Ruru.lnk'
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{7C1F3E52-9B0D-4E7A-8A63-5D2E1B94C0AF}_is1'

function Fail($message) { Write-Host "::error::$message"; exit 1 }
function Run-Silent($more) {
    $p = Start-Process -FilePath $Setup -ArgumentList ($silent + $more) -PassThru
    if (-not $p.WaitForExit(180000)) { $p.Kill(); Fail 'Setup did not finish in three minutes' }
    if ($p.ExitCode -ne 0) { Fail "Setup exited with $($p.ExitCode)" }
}
function Ruru-Processes { Get-Process Ruru -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$app\*" } }

Run-Silent @()
if (-not (Test-Path $exe)) { Fail "No $exe after a silent install" }
if (-not (Test-Path (Join-Path $app 'unins000.exe'))) { Fail 'No unins000.exe in the install folder' }
if (-not (Test-Path $shortcut)) { Fail 'No Start menu shortcut' }
if (-not (Test-Path $uninstallKey)) { Fail 'No entry in Settings > Apps' }
# The class archives that make Ruru start quickly: the runtime's own and the app's, both inside the per-user folder.
if (-not (Test-Path (Join-Path $app 'runtime\bin\server\classes.jsa'))) { Fail 'No runtime class archive (classes.jsa) in the install folder' }
if (-not (Test-Path (Join-Path $app 'app\ruru.jsa'))) { Fail 'No app class archive (ruru.jsa) in the install folder' }
Write-Host 'Installed: files, shortcut and Settings > Apps entry are there.'

# Ruru runs from where setup put it (demo mode), without errors.
$p = Start-Process -FilePath $exe -ArgumentList '--demo' -WindowStyle Minimized -PassThru -RedirectStandardOutput ruru-setup-start.log -RedirectStandardError ruru-setup-start-err.log
Start-Sleep -Seconds 12
$output = (Get-Content ruru-setup-start.log, ruru-setup-start-err.log -ErrorAction SilentlyContinue) -join "`n"
if ($p.HasExited) { Write-Host $output; Fail 'The installed Ruru stopped after launch' }
if ($output -match 'Exception in thread|Error was captured in composition') { Write-Host $output; Fail 'The installed Ruru failed at launch' }
$before = @(Ruru-Processes | ForEach-Object { $_.Id })

# What the updater does: a silent setup over the running copy, with /RELAUNCH.
Run-Silent @('/RELAUNCH')
Start-Sleep -Seconds 12
$after = @(Ruru-Processes | ForEach-Object { $_.Id })
if ($after.Count -eq 0) { Fail '/RELAUNCH did not start Ruru again' }
if (@($after | Where-Object { $before -contains $_ }).Count -gt 0) { Fail 'The old Ruru was still running after the update' }
Write-Host 'Updated in place: the old Ruru was closed and a new one started.'

Ruru-Processes | Stop-Process -Force
Start-Sleep -Seconds 2
$u = Start-Process -FilePath (Join-Path $app 'unins000.exe') -ArgumentList ('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -PassThru
if (-not $u.WaitForExit(120000)) { $u.Kill(); Fail 'The uninstaller did not finish in two minutes' }
Start-Sleep -Seconds 2
if (Test-Path $app) { Fail "$app is still there after a silent uninstall" }
if (Test-Path $shortcut) { Fail 'The Start menu shortcut is still there after uninstall' }
if (Test-Path $uninstallKey) { Fail 'The Settings > Apps entry is still there after uninstall' }
Write-Host 'Uninstalled: the folder, shortcut and Settings > Apps entry are gone.'
