@echo off
rem Use this only if the Portal is on USB rather than the same Wi-Fi as the PC.
rem It needs Android platform-tools (adb.exe) in the same folder as this script or on PATH.
rem Download: https://developer.android.com/tools/releases/platform-tools
rem After it runs, the camera is at http://127.0.0.1:8080/stream on this PC.
cd /d "%~dp0"
adb kill-server >nul 2>&1
adb start-server
adb devices
adb forward tcp:8080 tcp:8080
adb shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.START
echo.
echo Forwarded. Open http://127.0.0.1:8080/ in a browser to check the picture.
echo Leave this window open while you use the camera.
pause
