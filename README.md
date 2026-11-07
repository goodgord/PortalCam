# PortalCam

Turns a Facebook Portal (the 10 inch model, Android 10) into a webcam and microphone for a PC.

Meta ended support for Portal devices, but the hardware has a good wide-angle camera and a four-microphone array.
This project puts them to use for video calls on a computer that has no webcam. It was written over a weekend for a
job interview; it is small, has no dependencies beyond the Android framework, and is easy to change.

Two programs:

- **On the Portal**, an Android app opens the front camera and microphone and serves them over the network: video as
  Motion JPEG, microphone as a live WAV stream. It also plays audio the PC sends it, so the Portal is the call's
  speaker, and it announces itself on the network so nothing has to be typed. It starts at boot.
- **On the PC**, a tray program finds the Portal (Wi-Fi or USB), shows its picture as a virtual camera called
  "PortalCam Camera", plays its microphone into a virtual audio cable that apps see as a microphone, and sends the
  PC's sound to the Portal through a second virtual cable that apps see as a speaker.

After a one-time setup, using it is: turn the Portal on, open Teams.

Tested on a Portal (`omni`, Android 10, SDK 29): 1280x720 at 28 to 30 frames per second, about 16 Mbit/s over
Wi-Fi at JPEG quality 75, plus 0.8 Mbit/s of audio.

## Folders

- `app/` the Android app. Kotlin, Camera2, no library dependencies.
- `release/PortalCam-0.2.0.apk` the built Portal app, debug-signed, ready to install.
- `windows-app/` the Windows tray program (C#, .NET 9, NAudio) and the one-time setup script. The virtual camera
  is softcam (MIT), built from source by the GitHub Actions workflow and renamed "PortalCam Camera".
- `windows/README.md` the guide for the PC.
- `.github/workflows/windows.yml` builds `PortalCam-windows.zip` on a Windows runner and attaches it to a release.

## Install on the Portal

The Portal needs USB debugging turned on so that `adb` can reach it. Guides for enabling developer mode on a Portal
are easy to find; this repository does not cover that step. Then, from a computer with adb:

```
adb install -r -g release/PortalCam-0.1.0.apk
adb shell pm grant au.gordo.portalcam android.permission.CAMERA
adb shell pm grant au.gordo.portalcam android.permission.RECORD_AUDIO
adb shell appops set au.gordo.portalcam SYSTEM_ALERT_WINDOW allow
adb shell am start -n au.gordo.portalcam/.MainActivity
```

The `pm grant` lines are optional; the app asks for both permissions the first time it opens. The `appops` line
matters. The Portal's Android 10 only lets the app that is on screen use the camera, so PortalCam has to stay on the
Portal's screen while it is used as a webcam. That permission lets it open its own screen at boot and bring itself
back if something else covers it. Press Stop in the app when the Portal is wanted for other things.

## Use

Open PortalCam on the Portal. The top-left box shows the stream address, the picture size, frame rate and the number
of connected viewers. Buttons along the bottom: Stop/Start, Rotate, Mirror, Camera 0/1, picture size, JPEG quality, Mic on/off.
Each setting is kept across restarts.

The app runs as a foreground service, so the stream keeps going if the screen turns off or another app comes to the
front. It can also be driven over adb without the screen:

```
adb shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.START
adb shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.STOP
```

Endpoints on port 8080:

| Path | What it returns |
| --- | --- |
| `/` | A test page with the live picture |
| `/stream` | `multipart/x-mixed-replace` MJPEG, for OBS, ffmpeg or a browser |
| `/snapshot.jpg` | The latest frame |
| `/audio.wav` | The microphone as a live 48 kHz 16-bit mono WAV stream |
| `/volume?percent=NN` | Sets the Portal's call volume; without a query, reads it |
| `/status` | JSON: picture size, fps, clients, mic and speaker state and levels, volume |

Other ports: TCP 8081 takes speaker audio (one header line `PCM <rate> <channels> 16`, then raw 16-bit PCM).
UDP 18475 carries a JSON beacon once a second and answers a `PORTALCAM?` probe.

PC side: see `windows/README.md`. On a Mac or Linux PC the streams can be used through OBS Studio (OBS Virtual
Camera plus a virtual audio device such as BlackHole or a PipeWire null sink); there is no native app for those yet.

## Build

Windows program: `dotnet publish windows-app/PortalCam/PortalCam.csproj -c Release -r win-x64` builds the exe on
any OS with the .NET 9 SDK. `softcam.dll` needs Visual Studio's C++ tools, which is why the GitHub Actions workflow
does the full package. `windows-app/PortalCam.Probe` is a console check of the network side that runs anywhere.

Portal app: needs the Android SDK and JDK 17. `local.properties` must contain `sdk.dir=<path to the SDK>`.

```
./gradlew assembleRelease
```

The release build is signed with the debug key on purpose, so it installs with plain `adb install` and needs no
keystore. It is meant for sideloading onto your own device, not for an app store.

## How the picture gets from the camera to the PC

1. `CamService` opens the camera with Camera2 and asks for YUV_420_888 frames at the chosen size.
2. Each frame is converted to NV21, rotated and mirrored as configured (`Yuv.kt`), and compressed with the
   framework's `YuvImage` JPEG encoder.
3. The JPEG goes into `FrameStore`, which keeps only the newest frame. Slow clients skip frames instead of lagging.
4. `MjpegServer` accepts HTTP connections and writes each new frame as a multipart part.
5. `AudioCapture` reads the microphone with `AudioRecord` (the VOICE_COMMUNICATION source, so the Portal's own echo
   cancelling and noise suppression apply) in 20 ms chunks and fans them out to each `/audio.wav` client. A client
   more than a second behind loses its oldest chunks rather than building up delay.

The default rotation comes from the camera's sensor orientation (270 degrees on this Portal) and the display rotation,
following the Camera2 formula for a front camera. Standing the Portal the other way up needs two presses of Rotate.

## Known limits

- Video and audio are separate streams with no shared clock. OBS plays them with similar delay, and the Sync Offset
  on the audio source corrects any drift you notice.
- The Portal's own assistant software holds camera 1 most of the time. Camera 0 works; the Camera button switches.
- The Windows program was written and compiled without a Windows PC to hand. The network side was tested against a
  real Portal; the virtual camera and audio cable parts follow their documentation and need a first run on Windows.
- New Teams has had periods of not listing DirectShow virtual cameras (the kind softcam is). Zoom, Meet and browsers
  list them. If Teams does not show "PortalCam Camera", quit Teams fully and reopen it; if it still does not, use
  the browser version of Teams for the call.

## Licence

MIT. See `LICENSE`.
