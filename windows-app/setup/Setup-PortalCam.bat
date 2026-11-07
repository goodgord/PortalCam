@echo off
rem Runs the PowerShell setup as administrator. Right-click this file and choose "Run as administrator",
rem or just double-click it and accept the prompt.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -NoExit -File \"%~dp0Setup-PortalCam.ps1\"'"
