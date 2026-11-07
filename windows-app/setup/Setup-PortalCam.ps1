<#
One-time setup of PortalCam on a Windows PC. Run as administrator (Setup-PortalCam.bat does that).

What it does, in order:
  1. Copies PortalCam to C:\Program Files\PortalCam.
  2. Installs VB-Audio Virtual Cable if it is not already there (the Portal's microphone arrives through it).
  3. Installs VB-Audio Hi-Fi Cable if it is not already there (the PC's sound leaves through it to the Portal).
  4. Registers the PortalCam virtual camera with Windows.
  5. Downloads adb (Android platform-tools) so a Portal on a USB cable also works. Optional; Wi-Fi needs nothing.
  6. Sets PortalCam to start with Windows, starts it, and makes the Portal the default speaker and microphone.

Both VB-Audio installers are downloaded from vb-audio.com, because their licence does not allow bundling them.
Each shows its own window: click "Install Driver" and accept the Windows security prompt.
#>
$ErrorActionPreference = "Stop"
$source = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $env:ProgramFiles "PortalCam"
$tmp = Join-Path $env:TEMP "PortalCam-setup"
New-Item -ItemType Directory -Force $target, $tmp | Out-Null

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function HasAudioDevice($name) {
    $devs = Get-PnpDevice -Class MEDIA -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -like "*$name*" -and $_.Status -eq "OK" }
    return ($devs | Measure-Object).Count -gt 0
}
function Download($url, $file) {
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
}

Step "1. Copying PortalCam to $target"
Get-Process PortalCam -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500
Copy-Item (Join-Path $source "*") $target -Recurse -Force

Step "2. VB-Audio Virtual Cable (microphone path)"
if (HasAudioDevice "VB-Audio Virtual Cable") {
    Write-Host "Already installed."
} else {
    $zip = Join-Path $tmp "vbcable.zip"
    Download "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack46.zip" $zip
    Expand-Archive $zip (Join-Path $tmp "vbcable") -Force
    $exe = Get-ChildItem (Join-Path $tmp "vbcable") -Recurse -Filter "VBCABLE_Setup_x64.exe" | Select-Object -First 1
    Write-Host "In the window that opens, click 'Install Driver', then accept the Windows prompt, then close it."
    Start-Process $exe.FullName -Wait
}

Step "3. VB-Audio Hi-Fi Cable (speaker path)"
if (HasAudioDevice "Hi-Fi Cable") {
    Write-Host "Already installed."
} else {
    $zip = Join-Path $tmp "hificable.zip"
    Download "https://download.vb-audio.com/Download_CABLE/HiFiCableAsioBridgeSetup_v1007.zip" $zip
    Expand-Archive $zip (Join-Path $tmp "hificable") -Force
    $exe = Get-ChildItem (Join-Path $tmp "hificable") -Recurse -Filter "*.exe" | Where-Object { $_.Name -match "x64|Setup" } | Select-Object -First 1
    Write-Host "In the window that opens, click 'Install Driver', then accept the Windows prompt, then close it."
    Start-Process $exe.FullName -Wait
}

Step "4. Registering the PortalCam virtual camera"
& (Join-Path $target "softcam_installer.exe") register (Join-Path $target "softcam.dll")

Step "5. adb for a USB-connected Portal (optional)"
try {
    $zip = Join-Path $tmp "platform-tools.zip"
    Download "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" $zip
    Expand-Archive $zip $tmp -Force
    foreach ($f in "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll") { Copy-Item (Join-Path $tmp "platform-tools\$f") $target -Force }
    Write-Host "adb installed. If the Portal is on USB, accept its 'Allow USB debugging' prompt the first time."
} catch {
    Write-Host "Could not download adb ($_). Wi-Fi still works; skipping USB support." -ForegroundColor Yellow
}

Step "6. Start with Windows, start now, set defaults"
$exe = Join-Path $target "PortalCam.exe"
# The Portal announces itself with a UDP broadcast; let it in without the firewall asking.
Get-NetFirewallRule -DisplayName "PortalCam" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "PortalCam" -Direction Inbound -Program $exe -Action Allow -Profile Any | Out-Null
# HKLM Run starts it for every user who logs in; the elevated session's HKCU would be the wrong user.
Set-ItemProperty -Path "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "PortalCam" -Value "`"$exe`""
Start-Process $exe
Start-Sleep -Seconds 4
& $exe --set-defaults | Write-Host

Write-Host ""
Write-Host "Done. PortalCam is in the tray by the clock. Turn the Portal on and it connects by itself." -ForegroundColor Green
Write-Host "In Teams, Settings > Devices should show: Camera 'PortalCam Camera', Microphone 'CABLE Output', Speaker 'Hi-Fi Cable Input'."
Write-Host "If Teams was open during setup, quit it fully (right-click its tray icon > Quit) and open it again."
