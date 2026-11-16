import {
  DeviceCapabilities,
  DeviceInfo,
  DevicePlatform,
  DeviceRole,
  ProtocolErrorCode,
  QualityPreset,
} from './types.js';

export interface DeviceRegisterMessage {
  type: 'device_register';
  deviceId: string;
  role: DeviceRole;
  name: string;
  platform: DevicePlatform;
  capabilities: DeviceCapabilities;
}

export interface DeviceRegisteredMessage {
  type: 'device_registered';
  deviceId: string;
  role: DeviceRole;
  peers: DeviceInfo[];
}

export interface DeviceDiscoveredMessage {
  type: 'device_discovered';
  action: 'joined' | 'updated' | 'left';
  device: DeviceInfo;
}

export interface CreateSessionMessage {
  type: 'create_session';
  targetDeviceId: string;
  quality?: QualityPreset;
}

export interface SessionCreatedMessage {
  type: 'session_created';
  sessionId: string;
  senderId: string;
  receiverId: string;
  senderName: string;
  receiverName: string;
  pairingCode?: string;
  pairingExpiresAt: number;
  authorized: boolean;
  quality: QualityPreset;
}

export interface PairingConfirmMessage {
  type: 'pairing_confirm';
  sessionId: string;
  pairingCode: string;
}

export interface PairingVerifiedMessage {
  type: 'pairing_verified';
  sessionId: string;
  authorized: boolean;
  quality: QualityPreset;
}

export interface OfferMessage {
  type: 'offer';
  sessionId: string;
  sdp: string;
}

export interface AnswerMessage {
  type: 'answer';
  sessionId: string;
  sdp: string;
}

export interface IceCandidateMessage {
  type: 'ice_candidate';
  sessionId: string;
  candidate: string;
  sdpMid: string | null;
  sdpMLineIndex: number;
}

export interface CastStartedMessage {
  type: 'cast_started';
  sessionId: string;
  videoAvailable: boolean;
  audioAvailable: boolean;
  audioReason?: string;
  width: number;
  height: number;
  fps: number;
  quality: QualityPreset;
}

export interface CastStoppedMessage {
  type: 'cast_stopped';
  sessionId: string;
  reason: string;
}

export interface QualityChangeMessage {
  type: 'quality_change';
  sessionId: string;
  quality: QualityPreset;
}

export interface PeerDisconnectedMessage {
  type: 'peer_disconnected';
  sessionId?: string;
  deviceId: string;
  reason: string;
}

export interface ErrorMessage {
  type: 'error';
  code: ProtocolErrorCode;
  message: string;
  sessionId?: string;
}

export interface PingMessage {
  type: 'ping';
  timestamp: number;
}

export interface PongMessage {
  type: 'pong';
  timestamp: number;
}

export type SignalingMessage =
  | DeviceRegisterMessage
  | DeviceRegisteredMessage
  | DeviceDiscoveredMessage
  | CreateSessionMessage
  | SessionCreatedMessage
  | PairingConfirmMessage
  | PairingVerifiedMessage
  | OfferMessage
  | AnswerMessage
  | IceCandidateMessage
  | CastStartedMessage
  | CastStoppedMessage
  | QualityChangeMessage
  | PeerDisconnectedMessage
  | ErrorMessage
  | PingMessage
  | PongMessage;

const VALID_ROLES = new Set<string>(['sender', 'receiver']);
const VALID_PLATFORMS = new Set<string>(['android', 'web']);
const VALID_QUALITIES = new Set<string>(['adaptive', 'native', '720p30', '1080p30', '1080p60', '1440p60']);

function isNonEmptyString(value: unknown, maxLen = 4096): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maxLen;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isValidQualityPreset(value: unknown): value is QualityPreset {
  return typeof value === 'string' && VALID_QUALITIES.has(value);
}

export function normalizePairingCode(code: string): string {
  return code.replace(/\s+/g, '').trim();
}

export function parseSignalingMessage(raw: string):
  | { ok: true; message: SignalingMessage }
  | { ok: false; error: string } {
  if (raw.length > 256 * 1024) {
    return { ok: false, error: 'Message exceeds maximum allowed payload size.' };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, error: 'Malformed JSON payload.' };
  }

  if (!isRecord(parsed) || !isNonEmptyString(parsed.type, 64)) {
    return { ok: false, error: 'Missing or invalid message type.' };
  }

  switch (parsed.type) {
    case 'device_register': {
      if (
        !isNonEmptyString(parsed.deviceId, 128) ||
        typeof parsed.role !== 'string' ||
        !VALID_ROLES.has(parsed.role) ||
        !isNonEmptyString(parsed.name, 128) ||
        typeof parsed.platform !== 'string' ||
        !VALID_PLATFORMS.has(parsed.platform) ||
        !isRecord(parsed.capabilities)
      ) {
        return { ok: false, error: 'Invalid device_register payload.' };
      }
      const cap = parsed.capabilities;
      if (
        typeof cap.video !== 'boolean' ||
        typeof cap.audio !== 'boolean' ||
        typeof cap.maxWidth !== 'number' ||
        typeof cap.maxHeight !== 'number' ||
        typeof cap.maxFps !== 'number'
      ) {
        return { ok: false, error: 'Invalid device capabilities.' };
      }
      return {
        ok: true,
        message: parsed as unknown as DeviceRegisterMessage,
      };
    }

    case 'create_session': {
      if (!isNonEmptyString(parsed.targetDeviceId, 128)) {
        return { ok: false, error: 'Invalid create_session targetDeviceId.' };
      }
      if (parsed.quality !== undefined && !isValidQualityPreset(parsed.quality)) {
        return { ok: false, error: 'Invalid create_session quality preset.' };
      }
      return { ok: true, message: parsed as unknown as CreateSessionMessage };
    }

    case 'pairing_confirm': {
      if (
        !isNonEmptyString(parsed.sessionId, 128) ||
        !isNonEmptyString(parsed.pairingCode, 16)
      ) {
        return { ok: false, error: 'Invalid pairing_confirm payload.' };
      }
      return { ok: true, message: parsed as unknown as PairingConfirmMessage };
    }

    case 'offer': {
      if (!isNonEmptyString(parsed.sessionId, 128) || !isNonEmptyString(parsed.sdp, 131072)) {
        return { ok: false, error: 'Invalid offer payload.' };
      }
      return { ok: true, message: parsed as unknown as OfferMessage };
    }

    case 'answer': {
      if (!isNonEmptyString(parsed.sessionId, 128) || !isNonEmptyString(parsed.sdp, 131072)) {
        return { ok: false, error: 'Invalid answer payload.' };
      }
      return { ok: true, message: parsed as unknown as AnswerMessage };
    }

    case 'ice_candidate': {
      if (
        !isNonEmptyString(parsed.sessionId, 128) ||
        typeof parsed.candidate !== 'string' ||
        parsed.candidate.length > 8192 ||
        (parsed.sdpMid !== null && typeof parsed.sdpMid !== 'string') ||
        typeof parsed.sdpMLineIndex !== 'number' ||
        !Number.isInteger(parsed.sdpMLineIndex) ||
        parsed.sdpMLineIndex < 0
      ) {
        return { ok: false, error: 'Invalid ice_candidate payload.' };
      }
      return { ok: true, message: parsed as unknown as IceCandidateMessage };
    }

    case 'cast_started': {
      if (
        !isNonEmptyString(parsed.sessionId, 128) ||
        typeof parsed.videoAvailable !== 'boolean' ||
        typeof parsed.audioAvailable !== 'boolean' ||
        typeof parsed.width !== 'number' ||
        typeof parsed.height !== 'number' ||
        typeof parsed.fps !== 'number' ||
        !isValidQualityPreset(parsed.quality)
      ) {
        return { ok: false, error: 'Invalid cast_started payload.' };
      }
      return { ok: true, message: parsed as unknown as CastStartedMessage };
    }

    case 'cast_stopped': {
      if (!isNonEmptyString(parsed.sessionId, 128) || !isNonEmptyString(parsed.reason, 256)) {
        return { ok: false, error: 'Invalid cast_stopped payload.' };
      }
      return { ok: true, message: parsed as unknown as CastStoppedMessage };
    }

    case 'quality_change': {
      if (!isNonEmptyString(parsed.sessionId, 128) || !isValidQualityPreset(parsed.quality)) {
        return { ok: false, error: 'Invalid quality_change payload.' };
      }
      return { ok: true, message: parsed as unknown as QualityChangeMessage };
    }

    case 'ping':
    case 'pong': {
      if (typeof parsed.timestamp !== 'number' || !Number.isFinite(parsed.timestamp)) {
        return { ok: false, error: 'Invalid heartbeat timestamp.' };
      }
      return { ok: true, message: parsed as unknown as PingMessage | PongMessage };
    }

    case 'device_registered':
    case 'device_discovered':
    case 'session_created':
    case 'pairing_verified':
    case 'peer_disconnected':
    case 'error': {
      return { ok: true, message: parsed as unknown as SignalingMessage };
    }

    default:
      return { ok: false, error: `Unsupported signaling message type: ${parsed.type}` };
  }
}
