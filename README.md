# PortalCam

Turns a Facebook Portal (the 10 inch model, Android 10) into a webcam for a Windows PC.

An Android app runs on the Portal, opens the front camera and microphone, and serves them over HTTP on port 8080:
video as a Motion JPEG stream, audio as a live WAV stream. On the PC, OBS Studio reads both. It exposes the video to
Windows as "OBS Virtual Camera" and plays the audio into VB-Audio Virtual Cable, which Windows sees as a microphone.

Tested on a Portal (`omni`, Android 10, SDK 29): 1280x720 at 28 to 30 frames per second, about 16 Mbit/s over
Wi-Fi at JPEG quality 75, plus 0.8 Mbit/s of audio.

## Folders

- `app/` the Android app. Kotlin, Camera2, no library dependencies.
- `release/PortalCam-0.1.0.apk` the built app, debug-signed, ready to install.
- `windows/` instructions for the PC, a ready-made OBS scene collection and a USB fallback script.

## Install on the Portal

The Portal needs developer mode and USB debugging on. Then from a computer with adb:

```
adb install -r -g release/PortalCam-0.1.0.apk
adb shell pm grant au.gordo.portalcam android.permission.CAMERA
adb shell pm grant au.gordo.portalcam android.permission.RECORD_AUDIO
adb shell am start -n au.gordo.portalcam/.MainActivity
```

The `pm grant` lines are optional; the app asks for both permissions the first time it opens.

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
| `/status` | JSON: width, height, fps, clients, mic state, mic level, mic listeners |

PC side: see `windows/README.md`.

## Build

Needs the Android SDK and JDK 17. `local.properties` must contain `sdk.dir=<path to the SDK>`.

```
./gradlew assembleRelease
```

The release build is signed with the debug key on purpose, so it installs with plain `adb install` and needs no
keystore. Do not publish it anywhere that matters.

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
