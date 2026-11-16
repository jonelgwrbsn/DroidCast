# DroidCast Testing Plan

## Automated Verification (Included)

- **Protocol (`packages/protocol`)**: `npm test` verifies JSON message parsing, schema validation, invalid ICE candidate rejection, and pairing code normalization.
- **Signaling (`apps/signaling`)**: `npm test` spins up a live `WebSocketServer`, registers a receiver and sender, enforces pairing authorization before forwarding SDP offers, validates 6-digit pairing confirmation, and verifies SDP offer forwarding and peer disconnect cleanup.
- **Receiver (`apps/receiver`)**: `npm run typecheck && npm run build` verifies TypeScript strict mode and Vite production bundling.
- **Android (`app/`)**: `gradle :app:testDebugUnitTest` runs Robolectric + JVM unit tests covering `app_name` resource configuration, `ProtocolCodec` parsing, and `SignalingClient.normalizeWsUrl`.

## Physical Device & LAN Acceptance Matrix

Because hardware MediaProjection, AudioPlaybackCapture, and LAN Wi-Fi WebRTC require a physical Android device and laptop browser on the same Wi-Fi network, run the following end-to-end checklist in Android Studio + Chrome/Edge/Firefox:

### 1. Connection & Pairing
- [ ] Android -> Laptop: Tap receiver -> Start Casting -> verify 6-digit pairing code appears on Android -> enter code on laptop -> MediaProjection prompt appears -> approve -> live stream plays.
- [ ] Laptop -> Android: Click **Connect** on discovered Android device in receiver -> enter 6-digit code shown on Android -> stream starts.
- [ ] Invalid Pairing Code: Enter wrong 6-digit code -> verify error message and 5-attempt lockout.
- [ ] Disconnect -> Reconnect: Stop casting from Android notification (`Stop Casting`) and from receiver (`Stop`), then start a fresh session.

### 2. Video & Cinema Mode
- [ ] Presets: Switch between `Adaptive`, `720p · 30 FPS`, `1080p · 30 FPS`, `1080p · 60 FPS`.
- [ ] Orientation: Rotate Android between Portrait and Landscape; verify receiver maintains aspect ratio (`object-fit: contain`) with zero stretching.
- [ ] Cinema Mode: Toggle fullscreen (`F`, double-click, or fullscreen button); verify controls auto-hide after inactivity and reappear on mouse movement.

### 3. Audio
- [ ] Supported app on Android 10+ with `RECORD_AUDIO` granted -> verify `Audio: available` and laptop playback.
- [ ] `RECORD_AUDIO` denied -> verify video still casts and UI reports `Audio: unavailable`.
- [ ] Protected / opt-out app -> verify video continues without crashing.

### 4. Network & Lifecycle
- [ ] Brief Wi-Fi drop -> verify `Reconnecting...` banner and automatic recovery (`● Reconnected`).
- [ ] Android lock/unlock, backgrounding to play another app, and laptop browser refresh/tab close -> verify clean session teardown without orphaned foreground service.
