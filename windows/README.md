# PortalCam on Windows

PortalCam for Windows is a small program that lives in the tray by the clock. It finds the Portal on its own, over
Wi-Fi or a USB cable, and gives Windows three devices:

- a camera called "PortalCam Camera", showing the Portal's picture
- a microphone, the Portal's microphone
- a speaker, which plays through the Portal

Day to day there is nothing to do. The Portal starts streaming when it powers on, PortalCam starts with Windows and
connects within a few seconds, and Teams or Zoom use the devices like any webcam, mic and speaker. Because the call's
sound comes out of the Portal, the Portal's own echo cancelling removes it from the microphone, so no headphones are
needed.

## One-time setup (about 10 minutes, once, by whoever is comfortable with installers)

Windows cannot create a camera or an audio device from a normal program; each needs a small driver. PortalCam uses
two free ones from VB-Audio for the sound, and an MIT-licensed virtual camera (softcam) for the picture. The setup
script installs all three.

1. Download `PortalCam-windows.zip` from the Releases page of this repository and unzip it anywhere.
2. Double-click `Setup-PortalCam.bat` and accept the administrator prompt.
3. Two VB-Audio installer windows will appear, one after the other. In each, click "Install Driver", accept the
   Windows security prompt, then close the window. The script carries on by itself.
4. When the script says "Done", PortalCam is running in the tray and set to start with Windows.
5. Open Teams. Settings → Devices should show Camera "PortalCam Camera", Microphone "CABLE Output (VB-Audio Virtual
   Cable)" and Speaker "Hi-Fi Cable Input (VB-Audio Hi-Fi Cable)". The script has already made the last two the
   Windows defaults, so Teams normally picks them without being asked. If Teams was open during setup, quit it fully
   first (right-click its tray icon → Quit) and open it again.
6. Make a test call. Teams has "Make a test call" under Settings → Devices.

## On the day

1. Make sure the Portal is on, its camera privacy switch is not covering the lens, and its screen shows the
   PortalCam picture. It opens by itself after the Portal starts up. If another app is showing, tap PortalCam; the
   Portal's camera only works for the app on its screen.
2. Check the PortalCam tray icon is green. Hover over it to read its state. Grey means it is still looking for the
   Portal.
3. Join the call.

## The tray menu

Right-click the PortalCam icon:

- The first lines show the state, the frame rate, and whether the microphone and speaker paths are running.
- Portal speaker volume: 40 to 100 percent.
- Use the Portal as the default speaker and microphone: run this again if another device took over the defaults.
- Open the Portal's picture in a browser: a quick check of what the camera sees.
- Start with Windows.
- Open log file: for troubleshooting.

## If something is wrong

- Icon stays grey: the PC and the Portal are on different networks, or the Portal's app is not running. Open the
  PortalCam app on the Portal and check it says "Streaming".
- Icon is green but the camera shows the waiting message or a dark frame: PortalCam is not the app on the Portal's
  screen. Tap it on the Portal. If the PC is on Ethernet and the Portal on a guest
  Wi-Fi, move one of them. Or plug the Portal into the PC with a USB cable, tap "Allow" on the Portal when it asks
  about USB debugging, and PortalCam switches to USB on its own.
- No camera in Teams: quit Teams fully and open it again. Check Windows Settings → Privacy → Camera allows desktop
  apps. If "PortalCam Camera" is still missing, run `Setup-PortalCam.bat` again.
- Teams shows the camera but a dark picture: the Portal connected after Teams opened and Teams kept the old frame.
  Switch to another camera and back in Teams' device settings.
- They cannot hear you: in Teams' device settings, check the microphone is "CABLE Output". On the Portal screen
  the "Mic" level bar should move when you talk.
- You cannot hear them: check the Teams speaker is "Hi-Fi Cable Input", and raise the Portal speaker volume from the
  tray menu. On the Portal screen the "Speaker" line should say "playing".
- Your voice reaches them late or echoing: that is the chain's delay. The Portal's echo cancelling handles its own
  speaker; if echo persists, lower the Portal volume a step.

## If the setup script cannot be used

The pieces can be installed by hand: VB-CABLE and Hi-Fi Cable from https://vb-audio.com/Cable/, then run
`softcam_installer.exe register <full path>\softcam.dll` as administrator, then start `PortalCam.exe` and tick
"Start with Windows" in its menu. The folder also has `usb-forward.bat` and the OBS scene from the first version of
this project, in case a manual OBS route is preferred.
