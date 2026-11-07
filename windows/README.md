# Using the Portal as a webcam and microphone on Windows

The Portal app serves its camera as a Motion JPEG video stream and its microphone as a WAV audio stream over the
network. Windows does not treat a network stream as a webcam or a microphone by itself, so two free programs sit in
the middle:

- OBS Studio reads both streams. It presents the video to Windows as "OBS Virtual Camera".
- VB-Audio Virtual Cable is a virtual audio device. OBS plays the Portal's microphone into it, and Windows apps
  see it as a microphone called "CABLE Output".

Zoom, Teams, Google Meet and browsers all list those two devices like an ordinary webcam and microphone.

## One-time setup (about 10 minutes)

1. On the Portal, open the PortalCam app. The top-left box shows a line like `http://192.168.86.50:8080/stream`.
   Write that address down. The Portal and the PC must be on the same Wi-Fi network.
2. On the PC, open a browser and go to the address but without `/stream` on the end, for example
   `http://192.168.86.50:8080/`. You should see the live picture. If you do not, see "If it does not work" below.
3. Install OBS Studio from https://obsproject.com/download (Windows installer, free). Skip the auto-configuration
   wizard when it first opens (choose Cancel).
4. In OBS, go to Scene Collection → Import, click the "..." under Collection Path, choose `PortalCam-OBS-Scene.json`
   from this folder, and click Import. Then go to Scene Collection → PortalCam.
5. In the Sources list, double-click "Portal Camera". Check that the Input field matches the address from step 1
   (with `/stream` on the end). Change it if the Portal's address is different. Click OK.
   The picture should appear in the OBS preview.
6. In the Controls panel at the bottom right, click "Start Virtual Camera".
7. In Zoom, Teams or the browser, choose "OBS Virtual Camera" as the camera.

Then the microphone:

8. Install VB-Audio Virtual Cable from https://vb-audio.com/Cable/ (VBCABLE_Driver_Pack, free). Run the
   installer as administrator and restart the PC when it asks.
9. In OBS, go to Settings → Audio → Advanced → Monitoring Device, choose "CABLE Input (VB-Audio Virtual Cable)",
   and click OK.
10. In the Sources list there is a second source, "Portal Mic". Double-click it and check its Input is the Portal's
    address with `/audio.wav` on the end, for example `http://192.168.86.50:8080/audio.wav`. Click OK.
11. In the Audio Mixer panel, click the gear icon next to "Portal Mic" → Advanced Audio Properties. Set its Audio
    Monitoring to "Monitor Only (mute output)". Close the window. The Portal Mic level bar should move when you
    talk near the Portal.
12. In Zoom, Teams or the browser, choose "CABLE Output (VB-Audio Virtual Cable)" as the microphone.
13. Plug headphones or earbuds into the PC and use them for the call. The Portal's microphone will otherwise hear the
    interviewer from the PC speakers and send their voice back to them as an echo.

If the import in step 4 did not include "Portal Mic", add it by hand: Sources → "+" → Media Source → name it
"Portal Mic" → untick "Local File" → Input: the `/audio.wav` address → Input Format: `wav` → Network Buffering: 0 MB
→ tick "Restart playback when source becomes active" → OK. Then do steps 11 and 12.

If the import in step 4 does not work, make the source by hand: Sources → "+" → Media Source → name it
"Portal Camera" → untick "Local File" → Input: the stream address → Input Format: `mjpeg` → Network Buffering: 0 MB
→ tick "Restart playback when source becomes active" → OK.

## On the day

1. Turn the Portal on, make sure its privacy switch is not covering the camera, and open PortalCam. Wait for the
   top-left box to say "Streaming".
2. Open OBS and click "Start Virtual Camera". OBS remembers the scene and source.
3. Join the call and pick "OBS Virtual Camera" as the camera and "CABLE Output" as the microphone. Put the
   headphones on.
4. Do a test call first (Zoom has "Test video" and "Test mic" in Settings) to check framing, lighting and that your
   voice is heard. If your voice arrives noticeably after your lips move, open Advanced Audio Properties in OBS and
   adjust the Sync Offset on "Portal Mic" (a negative value plays the sound earlier).

The Portal's screen shows exactly what the PC receives. Use the buttons along the bottom of the Portal screen:

- Rotate: turns the picture by 90 degrees each press, if it comes out sideways or upside down.
- Mirror: flips left and right. Most calling apps mirror your own preview but send the unmirrored picture, so leave
  this off unless text in shot looks wrong on the far end.
- Camera 0 / Camera 1: the Portal has two camera devices. Use whichever gives the better picture.
- 360p / 720p / 1080p: picture size. 720p is right for a video call.
- Q 50 to Q 95: JPEG quality. Higher is sharper but uses more Wi-Fi bandwidth. 75 is a good default.
- Mic: on / off: whether the microphone stream is served. The level bar in the top-left box moves when the Portal
  hears something.

## If the audio chain is too much trouble

A wired headset with a 3.5 mm plug or a USB headset plugged into the PC is a microphone and headphones in one. It
needs no setup and has no echo. If one is to hand, use it and skip steps 8 to 12.

## If it does not work

- The browser test in step 2 shows nothing: the PC and the Portal are probably on different networks (for example
  the PC on Ethernet and the Portal on a guest Wi-Fi), or Windows Firewall is blocking the browser. Try the
  address on a phone on the same Wi-Fi to tell which. If the networks cannot be made the same, use USB instead:
  install Android platform-tools, plug the Portal into the PC with a USB cable, accept the "Allow USB debugging"
  prompt on the Portal, run `usb-forward.bat`, and use `http://127.0.0.1:8080/stream` as the address in OBS.
- OBS shows a black picture: double-click the Portal Camera source and untick then re-tick "Restart playback when
  source becomes active", or check the address.
- The picture freezes: the Portal's Wi-Fi dropped or it went to sleep. Tap the Portal screen, check the top-left box
  says "Streaming", then in OBS right-click the source → Restart.
- The Portal says "Camera taken by another app, retrying": the Portal's own camera software has the camera. Wait a
  few seconds, it retries on its own. If it never recovers, press Camera 0 / Camera 1 to try the other device.
- The Portal Mic level bar in OBS does not move: check the Portal screen says "Mic on". If it says "no permission",
  open the Portal's Settings → Apps → PortalCam → Permissions and allow Microphone, then press Stop and Start in
  PortalCam. Also check that OBS's Monitoring Device is "CABLE Input" and the source is set to "Monitor Only".
- "CABLE Output" is missing from the microphone list: VB-Cable did not finish installing. Run its installer as
  administrator again and restart the PC.
- "OBS Virtual Camera" is missing from the camera list in Zoom or Teams: restart that app after OBS has started the
  virtual camera. In the browser, reload the page.
