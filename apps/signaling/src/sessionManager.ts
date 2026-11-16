import crypto from 'node:crypto';
import { normalizePairingCode, type QualityPreset } from './protocol.js';

export interface CastSessionRecord {
  sessionId: string;
  senderId: string;
  receiverId: string;
  senderName: string;
  receiverName: string;
  pairingCode: string;
  pairingExpiresAt: number;
  failedAttempts: number;
  authorized: boolean;
  quality: QualityPreset;
  createdAt: number;
  lastActivityAt: number;
}

const PAIRING_TTL_MS = 5 * 60 * 1000; // 5 minutes
const MAX_PAIRING_ATTEMPTS = 5;
const ABANDONED_UNAUTHORIZED_MS = 10 * 60 * 1000;

export class SessionManager {
  private readonly sessions = new Map<string, CastSessionRecord>();

  createSession(params: {
    senderId: string;
    receiverId: string;
    senderName: string;
    receiverName: string;
    quality?: QualityPreset;
  }): CastSessionRecord {
    // Close any previous pending/active sessions involving either peer to prevent leaks
    for (const existing of this.sessions.values()) {
      if (
        existing.senderId === params.senderId ||
        existing.receiverId === params.receiverId
      ) {
        this.sessions.delete(existing.sessionId);
      }
    }

    const now = Date.now();
    const session: CastSessionRecord = {
      sessionId: crypto.randomUUID(),
      senderId: params.senderId,
      receiverId: params.receiverId,
      senderName: params.senderName,
      receiverName: params.receiverName,
      pairingCode: crypto.randomInt(100000, 1000000).toString(),
      pairingExpiresAt: now + PAIRING_TTL_MS,
      failedAttempts: 0,
      authorized: false,
      quality: params.quality ?? 'adaptive',
      createdAt: now,
      lastActivityAt: now,
    };

    this.sessions.set(session.sessionId, session);
    return session;
  }

  get(sessionId: string): CastSessionRecord | undefined {
    return this.sessions.get(sessionId);
  }

  isParticipant(session: CastSessionRecord, deviceId: string): boolean {
    return session.senderId === deviceId || session.receiverId === deviceId;
  }

  getPeerId(session: CastSessionRecord, deviceId: string): string | undefined {
    if (session.senderId === deviceId) return session.receiverId;
    if (session.receiverId === deviceId) return session.senderId;
    return undefined;
  }

  verifyPairing(
    sessionId: string,
    actorDeviceId: string,
    submittedCode: string
  ):
    | { ok: true; session: CastSessionRecord }
    | {
        ok: false;
        code: 'SESSION_NOT_FOUND' | 'UNAUTHORIZED' | 'PAIRING_EXPIRED' | 'TOO_MANY_ATTEMPTS' | 'INVALID_PAIRING_CODE';
        message: string;
      } {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return { ok: false, code: 'SESSION_NOT_FOUND', message: 'Casting session not found or closed.' };
    }

    if (session.receiverId !== actorDeviceId) {
      return {
        ok: false,
        code: 'UNAUTHORIZED',
        message: 'Only the target receiver may confirm pairing for this session.',
      };
    }

    if (Date.now() > session.pairingExpiresAt) {
      this.sessions.delete(sessionId);
      return {
        ok: false,
        code: 'PAIRING_EXPIRED',
        message: 'Pairing code expired. Start a new pairing request.',
      };
    }

    if (session.failedAttempts >= MAX_PAIRING_ATTEMPTS) {
      this.sessions.delete(sessionId);
      return {
        ok: false,
        code: 'TOO_MANY_ATTEMPTS',
        message: 'Too many invalid pairing code attempts. Session closed.',
      };
    }

    const normalized = normalizePairingCode(submittedCode);
    const expectedBuf = Buffer.from(session.pairingCode, 'utf8');
    const actualBuf = Buffer.from(normalized, 'utf8');

    const matches =
      expectedBuf.length === actualBuf.length &&
      crypto.timingSafeEqual(expectedBuf, actualBuf);

    if (!matches) {
      session.failedAttempts += 1;
      session.lastActivityAt = Date.now();
      if (session.failedAttempts >= MAX_PAIRING_ATTEMPTS) {
        this.sessions.delete(sessionId);
        return {
          ok: false,
          code: 'TOO_MANY_ATTEMPTS',
          message: 'Too many invalid pairing code attempts. Session closed.',
        };
      }
      return {
        ok: false,
        code: 'INVALID_PAIRING_CODE',
        message: `Invalid pairing code (${MAX_PAIRING_ATTEMPTS - session.failedAttempts} attempts remaining).`,
      };
    }

    session.authorized = true;
    session.lastActivityAt = Date.now();
    return { ok: true, session };
  }

  touch(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.lastActivityAt = Date.now();
    }
  }

  updateQuality(sessionId: string, quality: QualityPreset): void {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.quality = quality;
      session.lastActivityAt = Date.now();
    }
  }

  remove(sessionId: string): CastSessionRecord | undefined {
    const session = this.sessions.get(sessionId);
    if (session) {
      this.sessions.delete(sessionId);
    }
    return session;
  }

  removeByDevice(deviceId: string): CastSessionRecord[] {
    const removed: CastSessionRecord[] = [];
    for (const session of this.sessions.values()) {
      if (session.senderId === deviceId || session.receiverId === deviceId) {
        this.sessions.delete(session.sessionId);
        removed.push(session);
      }
    }
    return removed;
  }

  pruneExpired(now = Date.now()): CastSessionRecord[] {
    const expired: CastSessionRecord[] = [];
    for (const session of this.sessions.values()) {
      if (!session.authorized && now - session.createdAt > ABANDONED_UNAUTHORIZED_MS) {
        this.sessions.delete(session.sessionId);
        expired.push(session);
      }
    }
    return expired;
  }

  count(): number {
    return this.sessions.size;
  }
}
