# DroidCast Android Sender Guide

## Core Components

- `MainActivity.kt` — Jetpack Compose UI, runtime permission requests (`POST_NOTIFICATIONS`, `RECORD_AUDIO`), `MediaProjectionManager` launcher, and orientation handling.
- `CastService.kt` — Foreground Service (`foregroundServiceType="mediaProjection"`) that maintains screen/audio capture and displays the persistent notification with a **Stop Casting** action.
- `ScreenCapturer.kt` — Wraps `ScreenCapturerAndroid` and computes device-proportional capture dimensions via `WindowMetrics` across `Adaptive`, `720p30`, `1080p30`, and `1080p60`.
- `AudioCapturer.kt` — Evaluates Android API level (`>= Android 10 / API 29`) and `RECORD_AUDIO` permission, captures eligible media/game playback, and temporarily mutes/restores the phone media volume during a cast.
- `WebRTCManager.kt` — Manages `PeerConnectionFactory`, `EglBase`, `VideoTrack`, optional `AudioTrack`, `RTCPeerConnection` SDP offer generation, ICE candidate exchange, high-bitrate screen encoding profiles, bitrate/FPS clamping via `RtpSender.parameters`, and outbound WebRTC stats monitoring.
- `SignalingClient.kt` — OkHttp WebSocket client with heartbeat `ping`/`pong`, `ConnectivityManager.NetworkCallback` Wi-Fi monitoring, and exponential backoff reconnection.
- `DeviceDiscovery.kt` — Discovers receivers via Android `NsdManager` (`_droidcast._tcp.`) and UDP LAN probes (`port 39481`), with manual `host:port` connection fallback.
- `CastSession.kt` — Shared `StateFlow`-backed coordinator synchronizing `CastService`, `WebRTCManager`, `SignalingClient`, and the Compose UI.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` — install-time permissions for local-network signaling and mDNS discovery.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` — required by Android 14+ to run `MediaProjection` inside `CastService`.
- `POST_NOTIFICATIONS` (Android 13+) — runtime prompt so the active casting notification is visible.
- `RECORD_AUDIO` (Android 10+) — runtime prompt required by Android's `AudioPlaybackCaptureConfiguration`. If denied, DroidCast continues video-only and reports why audio is unavailable.
- `MODIFY_AUDIO_SETTINGS` — temporarily mutes phone media playback while system audio is sent to the receiver, then restores the previous volume when casting stops.

Audio playback capture is subject to Android's capture policy: protected content, voice-call audio, and apps that opt out of playback capture cannot be streamed.
