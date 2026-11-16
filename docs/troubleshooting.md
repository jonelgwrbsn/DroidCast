# DroidCast Troubleshooting Guide

## Cannot Connect / Receiver Not Found

1. Verify both your Android phone and laptop are connected to the **same Wi-Fi network** (avoid guest Wi-Fi networks with AP/client isolation enabled).
2. Check your laptop firewall allows inbound TCP on port `3000` (signaling) and UDP `39481` (LAN discovery) as well as ephemeral UDP ports for WebRTC ICE.
3. On Android, enter your laptop's LAN IP and port manually (e.g., `192.168.1.42:3000`) from `http://localhost:3000/info`.

## Screen Capture Permission Denied

- DroidCast never captures the screen silently. Tap **Start Casting** again and tap **Start now** / **Share entire screen** on the Android system prompt.

## Audio Unavailable

- System audio capture via `AudioPlaybackCapture` requires **Android 10 (API 29)** or higher and the **Record Audio** permission.
- Individual third-party apps can opt out of audio capture via `android:allowAudioPlaybackCapture="false"` or DRM protection (`USAGE_UNKNOWN` / protected playback). In those cases, DroidCast continues streaming video and displays `Audio: unavailable`.

## Stuttering or High Latency

- Lower the quality preset to `720p · 30 FPS` or `1080p · 30 FPS` in the receiver controls or Android settings.
- Move closer to a 5 GHz Wi-Fi access point.
