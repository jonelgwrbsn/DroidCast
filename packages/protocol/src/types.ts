export type DeviceRole = 'sender' | 'receiver';
export type DevicePlatform = 'android' | 'web';
export type DeviceStatus = 'ready' | 'pairing' | 'casting' | 'offline';
export type QualityPreset = 'adaptive' | 'native' | '720p30' | '1080p30' | '1080p60' | '1440p60';

export interface DeviceCapabilities {
  video: boolean;
  audio: boolean;
  maxWidth: number;
  maxHeight: number;
  maxFps: number;
}

export interface DeviceInfo {
  deviceId: string;
  role: DeviceRole;
  name: string;
  platform: DevicePlatform;
  status: DeviceStatus;
  address?: string;
  capabilities: DeviceCapabilities;
  connectedAt: number;
}

export interface QualityProfile {
  preset: QualityPreset;
  label: string;
  width: number;
  height: number;
  fps: number;
  maxBitrateBps: number;
}

export const QUALITY_PROFILES: Record<QualityPreset, QualityProfile> = {
  adaptive: {
    preset: 'adaptive',
    label: 'Adaptive (Native)',
    width: 3840,
    height: 2160,
    fps: 60,
    maxBitrateBps: 24_000_000,
  },
  native: {
    preset: 'native',
    label: 'Native · Match Phone',
    width: 3840,
    height: 2160,
    fps: 60,
    maxBitrateBps: 24_000_000,
  },
  '720p30': {
    preset: '720p30',
    label: '720p · 30 FPS',
    width: 1280,
    height: 720,
    fps: 30,
    maxBitrateBps: 4_000_000,
  },
  '1080p30': {
    preset: '1080p30',
    label: '1080p · 30 FPS',
    width: 1920,
    height: 1080,
    fps: 30,
    maxBitrateBps: 10_000_000,
  },
  '1080p60': {
    preset: '1080p60',
    label: '1080p · 60 FPS',
    width: 1920,
    height: 1080,
    fps: 60,
    maxBitrateBps: 16_000_000,
  },
  '1440p60': {
    preset: '1440p60',
    label: '1440p · 60 FPS',
    width: 2560,
    height: 1440,
    fps: 60,
    maxBitrateBps: 20_000_000,
  },
};

export type NetworkQualityLevel = 'excellent' | 'good' | 'fair' | 'poor';

export interface AdaptiveStreamState {
  type: 'adaptive_state';
  mode: QualityPreset;
  tierId: string;
  tierLabel: string;
  targetBitrateKbps: number;
  width: number;
  height: number;
  fps: number;
  networkQuality: NetworkQualityLevel;
  reason: string;
}

export interface ReceiverNetworkFeedback {
  type: 'receiver_feedback';
  packetLossPercent: number;
  jitterMs: number;
  rttMs: number | null;
  fps: number;
  bitrateKbps: number;
  framesDropped: number;
  stalled: boolean;
  timestampMs: number;
}

export type ProtocolErrorCode =
  | 'INVALID_MESSAGE'
  | 'INVALID_PAIRING_CODE'
  | 'PAIRING_EXPIRED'
  | 'TOO_MANY_ATTEMPTS'
  | 'DEVICE_NOT_FOUND'
  | 'SESSION_NOT_FOUND'
  | 'UNAUTHORIZED'
  | 'SESSION_EXPIRED'
  | 'INTERNAL_ERROR';
