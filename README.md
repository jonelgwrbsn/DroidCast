# DroidCast

**Cast your Android screen to your laptop.**

DroidCast is a real-time, local-network Android-to-laptop screen and system-audio casting system built with Android **MediaProjection**, **WebRTC**, **WebSocket signaling**, and a **React + TypeScript** dark cinema receiver.

## Architecture

```text
Android Phone
     │  MediaProjection + AudioPlaybackCapture
     ▼
WebRTC Sender (ScreenCapturerAndroid + RTCPeerConnection)
     │  Direct LAN P2P Media (VideoTrack + AudioTrack)
     ▼
Laptop Browser Receiver (React + TypeScript + WebRTC + Fullscreen API)
```

- **Signaling (`apps/signaling`)** only handles device registration, 6-digit pairing verification, SDP Offer/Answer exchange, and ICE candidate relay. Media streams never pass through the signaling server.
- **Shared Protocol (`packages/protocol`)** defines strongly typed signaling messages and runtime validators.
- **Laptop Receiver (`apps/receiver`)** renders the live `MediaStream` with `object-fit: contain`, Cinema Mode fullscreen, auto-hiding controls, quality presets, and live `RTCStatsReport` telemetry.
- **Android Sender (`app/`, `build.gradle.kts`, `settings.gradle.kts`)** uses official `MediaProjection`, a `foregroundServiceType="mediaProjection"` service (`CastService`), `NsdManager` + UDP LAN discovery, and `stream-webrtc-android`.

## Project Structure

```text
.
├── app/                         # Android Sender module (Kotlin + Jetpack Compose + WebRTC)
│   └── src/main/java/com/droidcast/
│       ├── MainActivity.kt
│       ├── CastService.kt
│       ├── CastSession.kt
│       ├── ScreenCapturer.kt
│       ├── AudioCapturer.kt
│       ├── WebRTCManager.kt
│       ├── SignalingClient.kt
│       ├── DeviceDiscovery.kt
│       ├── Protocol.kt
│       ├── ConnectionState.kt
│       └── DroidLog.kt
├── apps/
│   ├── receiver/                # React + TypeScript + Vite + Tailwind WebRTC receiver
│   └── signaling/               # Node.js + TypeScript WebSocket signaling + mDNS server
├── packages/
│   └── protocol/                # Shared TypeScript protocol types & runtime parser
├── docs/                        # Architecture, Android, Receiver, Signaling, Troubleshooting
├── build.gradle.kts             # Root Android Gradle configuration
├── settings.gradle.kts          # Gradle settings
└── package.json                 # npm workspaces & scripts
```

## Requirements

- **Node.js** >= 20 and **npm** >= 10
- **Android Studio** (or Gradle + JDK 17+ / Android SDK 36)
- **Modern browser** on Windows, macOS, or Linux (Chrome, Edge, or Firefox)
- Android phone and laptop on the **same local Wi-Fi network**

## Installation & Running Locally

### 1. Install and build Node.js packages (Protocol, Signaling, Receiver)

```bash
npm install
npm run build
```

### 2. Start the Signaling Server and Laptop Receiver

Single-port production mode (serves both the receiver web app and WebSocket signaling on port `3000`):

```bash
npm start
```

Or development mode with hot-reloading (`:5173` receiver + `:3000` signaling):

```bash
npm run dev
```

Or run them separately:

```bash
npm run signaling   # Starts WebSocket signaling on ws://0.0.0.0:3000
npm run receiver    # Starts Vite receiver on http://localhost:5173
```

Find your laptop's LAN IP address via `http://localhost:3000/info` or scan the **Quick Connect** QR code on the receiver home screen.

### Deploying the Receiver to Vercel

Import the repository into Vercel and set **Root Directory** to `.` (the repository root). The root `vercel.json` builds the shared protocol workspace before the receiver and publishes `apps/receiver/dist`. The signaling server is not deployed by this configuration and must be hosted separately.

### 3. Build and install the Android app

Open the repository root in **Android Studio** (or build via Gradle):

```bash
gradle :app:assembleDebug
```

## Pairing & Casting Flow

1. Open **DroidCast** in your laptop browser (`http://localhost:5173`). Ensure **Receiver ● Running** and **Network ● Connected**.
2. Open **DroidCast** on your Android phone. Select the discovered laptop receiver (or enter `192.168.x.x:3000`) and tap **Connect**.
3. Tap **Start Casting** -> Android displays a temporary 6-digit **PAIRING CODE**.
4. Enter that 6-digit pairing code in the laptop receiver's **Enter pairing code** dialog.
5. Allow **Record Audio** if prompted, then approve Android's **Screen Capture** prompt. When system audio is enabled, DroidCast streams eligible phone media audio to the receiver, temporarily mutes the phone's media output, and restores its volume when casting stops.
6. Your Android screen streams live to your laptop. Press **F** or click the fullscreen icon for **Cinema Mode**.

## Environment Variables

Copy `.env.example` to `.env` if customizing ports or STUN/TURN servers:

```env
PORT=3000
HOST=0.0.0.0
ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
VITE_SIGNALING_URL=ws://localhost:3000
VITE_STUN_SERVER=stun:stun.l.google.com:19302
```

## Testing

```bash
# Protocol & Signaling integration tests
npm test

# Android JVM & Robolectric unit tests
gradle :app:testDebugUnitTest
```

See `docs/troubleshooting.md` for Wi-Fi firewall, mDNS, and `AudioPlaybackCapture` details.
