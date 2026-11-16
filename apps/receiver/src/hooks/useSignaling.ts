import { useCallback, useEffect, useRef, useState } from 'react';
import { SignalingClient, type SignalingConnectionState } from '../lib/signaling';
import type {
  CastStartedMessage,
  DeviceInfo,
  LogEntry,
  QualityPreset,
  ReceiverError,
  SignalingMessage,
} from '../lib/protocol';

export interface ActiveSessionState {
  sessionId: string;
  senderId: string;
  senderName: string;
  authorized: boolean;
  pairingExpiresAt: number;
  quality: QualityPreset;
}

function getOrCreateDeviceId(): string {
  const key = 'droidcast_receiver_device_id';
  const existing = window.localStorage.getItem(key);
  if (existing) return existing;
  const created =
    typeof crypto.randomUUID === 'function'
      ? `rx-${crypto.randomUUID()}`
      : `rx-${Math.random().toString(36).slice(2, 10)}`;
  window.localStorage.setItem(key, created);
  return created;
}

export function getDefaultSignalingUrl(): string {
  const envUrl = (import.meta.env.VITE_SIGNALING_URL as string | undefined)?.trim();
  if (envUrl) return envUrl;
  const stored = window.localStorage.getItem('droidcast_signaling_url');
  if (stored) return stored;
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  // If served directly from the signaling server (not Vite :5173), reuse window.location.host
  if (window.location.port && window.location.port !== '5173') {
    return `${proto}//${window.location.host}`;
  }
  const host = window.location.hostname || 'localhost';
  return `${proto}//${host}:3000`;
}

export function getDefaultReceiverName(): string {
  return window.localStorage.getItem('droidcast_receiver_name') || 'My Laptop';
}

function mapErrorTitle(code: string): string {
  switch (code) {
    case 'INVALID_PAIRING_CODE':
      return 'Invalid pairing code';
    case 'PAIRING_EXPIRED':
      return 'Pairing code expired';
    case 'TOO_MANY_ATTEMPTS':
      return 'Too many pairing attempts';
    case 'DEVICE_NOT_FOUND':
      return 'Android device unavailable';
    case 'SESSION_EXPIRED':
      return 'Session timed out';
    default:
      return 'Connection error';
  }
}

export function useSignaling(onWebRtcMessage: (msg: SignalingMessage) => void) {
  const [signalingUrl, setSignalingUrlState] = useState<string>(() => getDefaultSignalingUrl());
  const [receiverName, setReceiverNameState] = useState<string>(() => getDefaultReceiverName());
  const [connectionState, setConnectionState] = useState<SignalingConnectionState>('connecting');
  const [justReconnected, setJustReconnected] = useState(false);
  const [senders, setSenders] = useState<DeviceInfo[]>([]);
  const [session, setSession] = useState<ActiveSessionState | null>(null);
  const [castInfo, setCastInfo] = useState<CastStartedMessage | null>(null);
  const [error, setError] = useState<ReceiverError | null>(null);
  const [pairingError, setPairingError] = useState<string | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const deviceIdRef = useRef<string>(getOrCreateDeviceId());
  const clientRef = useRef<SignalingClient | null>(null);
  const prevConnStateRef = useRef<SignalingConnectionState>('connecting');
  const webRtcHandlerRef = useRef(onWebRtcMessage);
  webRtcHandlerRef.current = onWebRtcMessage;

  const appendLog = useCallback(
    (level: 'INFO' | 'WARN' | 'ERROR', scope: string, message: string) => {
      const entry: LogEntry = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        timestamp: new Date().toLocaleTimeString([], { hour12: false }),
        level,
        scope,
        message,
      };
      setLogs((prev) => [entry, ...prev].slice(0, 100));
    },
    []
  );

  useEffect(() => {
    const client = new SignalingClient({
      url: signalingUrl,
      deviceId: deviceIdRef.current,
      deviceName: receiverName,
      callbacks: {
        onStateChange: (nextState) => {
          const previous = prevConnStateRef.current;
          prevConnStateRef.current = nextState;
          setConnectionState(nextState);

          if (previous === 'reconnecting' && nextState === 'connected') {
            setJustReconnected(true);
            setError(null);
            window.setTimeout(() => setJustReconnected(false), 3500);
          }
        },
        onLog: appendLog,
        onMessage: (msg) => {
          switch (msg.type) {
            case 'device_registered':
              setSenders(msg.peers);
              setError(null);
              break;

            case 'device_discovered':
              setSenders((prev) => {
                if (msg.action === 'left') {
                  return prev.filter((d) => d.deviceId !== msg.device.deviceId);
                }
                const without = prev.filter((d) => d.deviceId !== msg.device.deviceId);
                return [...without, msg.device];
              });
              break;

            case 'session_created':
              setPairingError(null);
              setError(null);
              setSession({
                sessionId: msg.sessionId,
                senderId: msg.senderId,
                senderName: msg.senderName,
                authorized: msg.authorized,
                pairingExpiresAt: msg.pairingExpiresAt,
                quality: msg.quality,
              });
              appendLog('INFO', 'Session', `Pairing requested with ${msg.senderName}`);
              break;

            case 'pairing_verified':
              setPairingError(null);
              setSession((prev) =>
                prev && prev.sessionId === msg.sessionId
                  ? { ...prev, authorized: true, quality: msg.quality }
                  : prev
              );
              appendLog('INFO', 'Session', `Session ${msg.sessionId} authorized`);
              webRtcHandlerRef.current(msg);
              break;

            case 'cast_started':
              setCastInfo(msg);
              setSession((prev) =>
                prev && prev.sessionId === msg.sessionId
                  ? { ...prev, quality: msg.quality }
                  : prev
              );
              appendLog(
                'INFO',
                'Media',
                `Stream active (${msg.width}x${msg.height} @ ${msg.fps}fps, audio=${msg.audioAvailable ? 'available' : 'unavailable'})`
              );
              break;

            case 'quality_change':
              setSession((prev) =>
                prev && prev.sessionId === msg.sessionId
                  ? { ...prev, quality: msg.quality }
                  : prev
              );
              break;

            case 'cast_stopped':
              appendLog('INFO', 'Session', `Casting stopped: ${msg.reason}`);
              setCastInfo(null);
              setSession(null);
              webRtcHandlerRef.current(msg);
              break;

            case 'peer_disconnected':
              appendLog('WARN', 'Network', msg.reason);
              setCastInfo(null);
              setSession(null);
              setError({
                code: 'PEER_DISCONNECTED',
                title: 'Android device disconnected',
                message: msg.reason,
                detail: 'The Android sender left the session or lost Wi-Fi connectivity.',
                recoverable: true,
              });
              webRtcHandlerRef.current(msg);
              break;

            case 'offer':
            case 'ice_candidate':
              webRtcHandlerRef.current(msg);
              break;

            case 'error':
              appendLog('ERROR', 'Protocol', `${msg.code}: ${msg.message}`);
              if (msg.code === 'INVALID_PAIRING_CODE') {
                setPairingError(msg.message);
              } else {
                if (
                  msg.code === 'PAIRING_EXPIRED' ||
                  msg.code === 'TOO_MANY_ATTEMPTS' ||
                  msg.code === 'SESSION_EXPIRED'
                ) {
                  setSession(null);
                  setCastInfo(null);
                }
                setError({
                  code: msg.code,
                  title: mapErrorTitle(msg.code),
                  message: msg.message,
                  detail: `Code: ${msg.code}`,
                  recoverable: true,
                });
              }
              break;
          }
        },
      },
    });

    clientRef.current = client;
    client.connect();

    return () => {
      client.disconnect();
      clientRef.current = null;
    };
  }, [appendLog]);

  const updateSettings = useCallback((nextUrl: string, nextName: string) => {
    const cleanUrl = nextUrl.trim();
    const cleanName = nextName.trim() || 'My Laptop';
    window.localStorage.setItem('droidcast_signaling_url', cleanUrl);
    window.localStorage.setItem('droidcast_receiver_name', cleanName);
    setSignalingUrlState(cleanUrl);
    setReceiverNameState(cleanName);
    clientRef.current?.updateConfig(cleanUrl, cleanName);
  }, []);

  const requestConnectToSender = useCallback((senderId: string, quality: QualityPreset = 'adaptive') => {
    setError(null);
    setPairingError(null);
    clientRef.current?.send({
      type: 'create_session',
      targetDeviceId: senderId,
      quality,
    });
  }, []);

  const submitPairingCode = useCallback(
    (code: string) => {
      if (!session) return;
      setPairingError(null);
      clientRef.current?.send({
        type: 'pairing_confirm',
        sessionId: session.sessionId,
        pairingCode: code,
      });
    },
    [session]
  );

  const cancelSession = useCallback(() => {
    if (session) {
      clientRef.current?.send({
        type: 'cast_stopped',
        sessionId: session.sessionId,
        reason: 'Cancelled by laptop receiver',
      });
    }
    setSession(null);
    setCastInfo(null);
    setPairingError(null);
  }, [session]);

  const changeQuality = useCallback(
    (quality: QualityPreset) => {
      if (!session) return;
      setSession((prev) => (prev ? { ...prev, quality } : prev));
      clientRef.current?.send({
        type: 'quality_change',
        sessionId: session.sessionId,
        quality,
      });
    },
    [session]
  );

  const sendMessage = useCallback((msg: SignalingMessage) => {
    return clientRef.current?.send(msg) ?? false;
  }, []);

  const retryConnection = useCallback(() => {
    setError(null);
    clientRef.current?.reconnectNow();
  }, []);

  const dismissError = useCallback(() => {
    setError(null);
  }, []);

  return {
    signalingUrl,
    receiverName,
    connectionState,
    justReconnected,
    senders,
    session,
    castInfo,
    error,
    pairingError,
    logs,
    appendLog,
    updateSettings,
    requestConnectToSender,
    submitPairingCode,
    cancelSession,
    changeQuality,
    sendMessage,
    retryConnection,
    dismissError,
  };
}
