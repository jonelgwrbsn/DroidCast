import type { AdaptiveStreamState } from '@droidcast/protocol';

export * from '@droidcast/protocol';

export interface WebRTCStatsSnapshot {
  width: number;
  height: number;
  fps: number;
  bitrateKbps: number;
  packetLossPercent: number;
  jitterMs: number;
  framesDropped: number;
  rttMs: number | null;
  iceState: RTCIceConnectionState;
  peerState: RTCPeerConnectionState;
  codec: string | null;
  audioPacketsReceived: number;
  stalled: boolean;
  adaptiveState: AdaptiveStreamState | null;
}

export interface ReceiverError {
  code: string;
  title: string;
  message: string;
  detail: string;
  recoverable: boolean;
}

export interface LogEntry {
  id: string;
  timestamp: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  scope: string;
  message: string;
}
