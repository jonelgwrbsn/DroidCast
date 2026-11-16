# DroidCast Architecture

DroidCast is a local-network Android screen and system-audio casting system built on MediaProjection, WebRTC, and WebSocket signaling.

## System Topology

```text
Android Device (Sender)
  ├─ MediaProjection + VirtualDisplay -> ScreenCapturerAndroid -> WebRTC VideoTrack
  ├─ AudioPlaybackCapture (API 29+)  -> WebRTC AudioTrack (when supported & permitted)
  ├─ NsdManager (_droidcast._tcp.) + UDP LAN Probe -> Local Receiver Discovery
  └─ OkHttp WebSocket -> SignalingClient
        │
        ▼ (Signaling only: SDP Offer/Answer, ICE Candidates, Pairing, Quality)
Node.js Signaling Server (ws + bonjour-service + UDP discovery)
        ▲
        │
Laptop Browser (Receiver)
  ├─ React + TypeScript + Vite + Tailwind CSS
  ├─ RTCPeerConnection -> MediaStream -> <video autoPlay playsInline>
  └─ Native Fullscreen API (Cinema Mode) + RTCStatsReport Telemetry
```

## Design Principles

1. **Zero Media Relay Through Signaling**: Once ICE negotiation completes, video and audio RTP packets travel directly peer-to-peer over the local Wi-Fi network between Android and the laptop browser.
2. **Explicit User Consent**: Screen capture always requires explicit user approval via Android's `MediaProjectionManager.createScreenCaptureIntent()` and runs visibly under a persistent foreground service notification (`CastService`).
3. **Temporary Pairing Authorization**: Every casting session creates a random session ID and a short-lived 6-digit pairing code displayed on Android and verified by the laptop receiver before any SDP/ICE messages are forwarded.
4. **In-Memory Stateless Sessions**: No database is required; session state lives strictly in memory on the signaling server and is cleaned up automatically upon disconnect or expiration.
