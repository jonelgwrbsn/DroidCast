import { useCallback, useEffect, useRef, useState } from 'react';
import { WebRTCReceiver, type PlaybackMode } from '../lib/webrtc';
import type { SignalingMessage, WebRTCStatsSnapshot } from '../lib/protocol';

const EMPTY_STATS: WebRTCStatsSnapshot = {
  width: 0,
  height: 0,
  fps: 0,
  bitrateKbps: 0,
  packetLossPercent: 0,
  jitterMs: 0,
  framesDropped: 0,
  rttMs: null,
  iceState: 'new',
  peerState: 'new',
  codec: null,
  audioPacketsReceived: 0,
  stalled: false,
  adaptiveState: null,
};

export function useWebRTC(params: {
  sendSignalingMessage: (msg: SignalingMessage) => boolean;
  onLog: (level: 'INFO' | 'WARN' | 'ERROR', scope: string, message: string) => void;
  onWebRtcError: (code: string, message: string) => void;
}) {
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
  const [stats, setStats] = useState<WebRTCStatsSnapshot>(EMPTY_STATS);
  const [iceState, setIceState] = useState<RTCIceConnectionState>('new');
  const [peerState, setPeerState] = useState<RTCPeerConnectionState>('new');
  const [playbackMode, setPlaybackModeState] = useState<PlaybackMode>('low-latency');

  const receiverRef = useRef<WebRTCReceiver | null>(null);
  const activeSessionIdRef = useRef<string | null>(null);
  const playbackModeRef = useRef<PlaybackMode>('low-latency');
  const sendRef = useRef(params.sendSignalingMessage);
  sendRef.current = params.sendSignalingMessage;
  const logRef = useRef(params.onLog);
  logRef.current = params.onLog;
  const errRef = useRef(params.onWebRtcError);
  errRef.current = params.onWebRtcError;

  const setPlaybackMode = useCallback((mode: PlaybackMode) => {
    playbackModeRef.current = mode;
    setPlaybackModeState(mode);
    receiverRef.current?.setPlaybackMode(mode);
  }, []);

  const resetPeer = useCallback(() => {
    receiverRef.current?.close();
    receiverRef.current = null;
    activeSessionIdRef.current = null;
    setRemoteStream(null);
    setStats(EMPTY_STATS);
    setIceState('new');
    setPeerState('new');
  }, []);

  const ensureReceiver = useCallback(() => {
    if (receiverRef.current) return receiverRef.current;
    const instance = new WebRTCReceiver({
      onStream: (stream) => {
        setRemoteStream(stream);
      },
      onIceCandidate: (candidate) => {
        const sid = activeSessionIdRef.current;
        if (!sid || !candidate.candidate) return;
        sendRef.current({
          type: 'ice_candidate',
          sessionId: sid,
          candidate: candidate.candidate,
          sdpMid: candidate.sdpMid ?? null,
          sdpMLineIndex: candidate.sdpMLineIndex ?? 0,
        });
      },
      onConnectionStateChange: (nextIce, nextPeer) => {
        setIceState(nextIce);
        setPeerState(nextPeer);
      },
      onStats: (snapshot) => {
        setStats(snapshot);
      },
      onError: (code, message) => {
        errRef.current(code, message);
      },
      onLog: (level, scope, message) => {
        logRef.current(level, scope, message);
      },
    }, playbackModeRef.current);
    receiverRef.current = instance;
    return instance;
  }, []);

  const handleSignalingMessage = useCallback(
    async (msg: SignalingMessage) => {
      switch (msg.type) {
        case 'pairing_verified':
          activeSessionIdRef.current = msg.sessionId;
          ensureReceiver();
          break;

        case 'offer': {
          activeSessionIdRef.current = msg.sessionId;
          const receiver = ensureReceiver();
          try {
            const answerSdp = await receiver.handleOffer(msg.sdp);
            sendRef.current({
              type: 'answer',
              sessionId: msg.sessionId,
              sdp: answerSdp,
            });
          } catch (err) {
            const detail = err instanceof Error ? err.message : String(err);
            logRef.current('ERROR', 'WebRTC', `Offer negotiation failed: ${detail}`);
            errRef.current('WEBRTC_NEGOTIATION_FAILED', 'WebRTC negotiation failed.');
          }
          break;
        }

        case 'ice_candidate': {
          const receiver = ensureReceiver();
          await receiver.addIceCandidate({
            candidate: msg.candidate,
            sdpMid: msg.sdpMid,
            sdpMLineIndex: msg.sdpMLineIndex,
          });
          break;
        }

        case 'cast_stopped':
        case 'peer_disconnected':
          resetPeer();
          break;
      }
    },
    [ensureReceiver, resetPeer]
  );

  useEffect(() => {
    return () => {
      receiverRef.current?.close();
    };
  }, []);

  return {
    remoteStream,
    stats,
    iceState,
    peerState,
    playbackMode,
    setPlaybackMode,
    handleSignalingMessage,
    resetPeer,
  };
}
