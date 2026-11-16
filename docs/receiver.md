# DroidCast Laptop Receiver Guide

The receiver is a zero-install React + TypeScript + Vite web application designed as a focused dark-cinema media viewport.

## Features

- **Real WebRTC Playback**: Creates an `RTCPeerConnection`, exchanges SDP offers/answers and ICE candidates, plays screen video and captured system audio, and preserves aspect ratio with `object-fit: contain`.
- **Autoplay Policy Handling**: Attempts to play captured audio automatically. If the browser blocks audio autoplay, the player shows an **Enable Audio** control that retries after a user gesture.
- **Cinema Mode**: Uses the native browser Fullscreen API (`F` shortcut or double-click or fullscreen button), hides controls automatically after inactivity, and reveals them on mouse movement.
- **Quality & Adaptive Telemetry**: Supports `Adaptive` (dynamic 5-tier resolution/FPS & bitrate scaling from 1080p60 down to 360p24 via WebRTC `droidcast-adaptive` DataChannel feedback and dynamic jitter-buffer sizing) as well as fixed `720p · 30 FPS`, `1080p · 30 FPS`, and `1080p · 60 FPS` presets, with live `RTCStatsReport` metrics (resolution, FPS, Rx/target bitrate, RTT, windowed packet loss, jitter, dropped frames, codec) in the **Connection Details** panel.
- **Auto-Reconnect**: Recovers cleanly from transient signaling WebSocket drops.
- **Local Wi-Fi APK Installer (`/install`)**: Dedicated `/install` route displaying a scannable QR code, live APK download button (`/download/droidcast.apk`), and Oppo/Realme/OnePlus/Vivo sideloading instructions.
