# DroidCast Signaling Server & Protocol

The signaling server (`apps/signaling`) coordinates device discovery, pairing authorization, and WebRTC SDP/ICE exchange over WebSockets (`ws`).

## Endpoints

- `ws://<host>:3000` — WebSocket signaling endpoint
- `GET /health` — Server status, connected device counts, and active session count
- `GET /info` — Detected LAN IPv4 addresses, WebSocket URLs, and local APK availability metadata
- `GET /install` — Mobile-friendly local Wi-Fi APK download page with Oppo/Realme/OnePlus/Vivo sideloading steps
- `GET /download/droidcast.apk` — Streams the compiled debug APK (`application/vnd.android.package-archive`)
- `_droidcast._tcp` (mDNS) & UDP port `39481` — Local network discovery beacons

## Security Controls

- Origin validation on browser WebSocket connections (allows localhost and private LAN ranges `192.168.*`, `10.*`, `172.16-31.*`, or configured `ALLOWED_ORIGINS`).
- Payload size limit (`256 KB`) and strict runtime schema validation (`parseSignalingMessage`).
- CSPRNG 6-digit pairing codes with 5-minute TTL, constant-time comparison (`crypto.timingSafeEqual`), and 5-attempt lockout.
- Strict session participant and authorization checks before forwarding `offer`, `answer`, `ice_candidate`, or `quality_change`.
- Automatic cleanup of expired sessions and stale/disconnected peers.
