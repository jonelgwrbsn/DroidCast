import type {
  AdaptiveStreamState,
  ReceiverNetworkFeedback,
  WebRTCStatsSnapshot,
} from './protocol';
import {
  computeAdaptivePlayoutDelaySec,
  computeIntervalPacketLossPercent,
  parseAdaptiveStreamState,
  type PlaybackMode,
} from './adaptive';

export type { PlaybackMode };

export interface WebRTCReceiverCallbacks {
  onStream: (stream: MediaStream) => void;
  onIceCandidate: (candidate: RTCIceCandidateInit) => void;
  onConnectionStateChange: (
    iceState: RTCIceConnectionState,
    peerState: RTCPeerConnectionState
  ) => void;
  onStats: (stats: WebRTCStatsSnapshot) => void;
  onError: (code: string, message: string) => void;
  onLog: (level: 'INFO' | 'WARN' | 'ERROR', scope: string, message: string) => void;
}

export function buildIceServers(): RTCIceServer[] {
  const stunUrl =
    (import.meta.env.VITE_STUN_SERVER as string | undefined)?.trim() ||
    'stun:stun.l.google.com:19302';

  const servers: RTCIceServer[] = [{ urls: stunUrl }];

  const turnUrl = (import.meta.env.VITE_TURN_SERVER as string | undefined)?.trim();
  if (turnUrl) {
    servers.push({
      urls: turnUrl,
      username: (import.meta.env.VITE_TURN_USERNAME as string | undefined) || undefined,
      credential: (import.meta.env.VITE_TURN_CREDENTIAL as string | undefined) || undefined,
    });
  }

  return servers;
}

export const RECEIVER_VIDEO_CONSTRAINTS: MediaTrackConstraints = {
  width: { ideal: 2560, max: 3840 },
  height: { ideal: 1440, max: 2160 },
  frameRate: { ideal: 60, max: 120 },
};

export function tuneAnswerSdpForLowLatency(sdp: string): string {
  const lines = sdp.split('\r\n');
  const out: string[] = [];
  let currentSection: 'session' | 'video' | 'audio' | 'other' = 'session';
  let videoHasBandwidthLine = false;
  const videoPayloadTypes = new Set<string>();

  for (const rawLine of lines) {
    let line = rawLine;
    if (line.startsWith('m=')) {
      if (line.startsWith('m=video ')) {
        currentSection = 'video';
        videoHasBandwidthLine = false;
      } else if (line.startsWith('m=audio ')) {
        currentSection = 'audio';
      } else {
        currentSection = 'other';
      }
      out.push(line);
      continue;
    }

    if (currentSection === 'video') {
      if (line.startsWith('b=AS:')) {
        videoHasBandwidthLine = true;
        line = 'b=AS:24000';
      } else if (line.startsWith('a=rtpmap:')) {
        if (!videoHasBandwidthLine) {
          out.push('b=AS:24000');
          videoHasBandwidthLine = true;
        }
        const lower = line.toLowerCase();
        if (lower.includes('h264/90000') || lower.includes('vp8/90000') || lower.includes('vp9/90000')) {
          const pt = line.slice('a=rtpmap:'.length).split(' ')[0];
          if (pt) videoPayloadTypes.add(pt);
        }
      } else if (line.startsWith('a=fmtp:')) {
        const pt = line.slice('a=fmtp:'.length).split(' ')[0];
        if (pt && videoPayloadTypes.has(pt)) {
          const extras: string[] = [];
          if (!line.includes('x-google-min-bitrate=')) extras.push('x-google-min-bitrate=3000');
          if (!line.includes('x-google-start-bitrate=')) extras.push('x-google-start-bitrate=8000');
          if (!line.includes('x-google-max-bitrate=')) extras.push('x-google-max-bitrate=24000');
          if (extras.length > 0) {
            line = `${line};${extras.join(';')}`;
          }
        }
      }
    }

    out.push(line);
  }

  return out.join('\r\n');
}

export class WebRTCReceiver {
  private pc: RTCPeerConnection | null = null;
  private adaptiveChannel: RTCDataChannel | null = null;
  private remoteStream: MediaStream | null = null;
  private pendingCandidates: RTCIceCandidateInit[] = [];
  private remoteDescriptionSet = false;
  private statsTimer: number | null = null;
  private prevBytesReceived = 0;
  private prevStatsTimestamp = 0;
  private prevPacketsReceived = 0;
  private prevPacketsLost = 0;
  private prevFramesDropped = 0;
  private prevFramesDecoded = 0;
  private stalledTicks = 0;
  private playbackMode: PlaybackMode = 'low-latency';
  private currentPlayoutDelaySec = 0.015;
  private latestAdaptiveState: AdaptiveStreamState | null = null;
  private callbacks: WebRTCReceiverCallbacks;

  constructor(callbacks: WebRTCReceiverCallbacks, initialMode: PlaybackMode = 'low-latency') {
    this.callbacks = callbacks;
    this.playbackMode = initialMode;
    this.currentPlayoutDelaySec = initialMode === 'smooth' ? 0.04 : 0.015;
  }

  setPlaybackMode(mode: PlaybackMode): void {
    this.playbackMode = mode;
    this.currentPlayoutDelaySec = mode === 'smooth' ? 0.04 : 0.015;
    this.applyPlayoutDelay(this.currentPlayoutDelaySec);
  }

  private applyPlayoutDelay(delaySec = this.currentPlayoutDelaySec): void {
    const pc = this.pc;
    if (!pc) return;
    for (const receiver of pc.getReceivers()) {
      if (receiver.track && receiver.track.kind === 'audio') {
        continue;
      }
      const ext = receiver as RTCRtpReceiver & {
        playoutDelayHint?: number;
        jitterBufferTarget?: number;
      };
      try {
        if ('playoutDelayHint' in ext) {
          ext.playoutDelayHint = delaySec;
        }
        if ('jitterBufferTarget' in ext) {
          ext.jitterBufferTarget = Math.round(delaySec * 1000);
        }
      } catch {
        // Ignore unsupported browser property
      }
    }
  }

  private configureReceiveTransceivers(pc: RTCPeerConnection): void {
    const transceivers = pc.getTransceivers?.() ?? [];
    for (const transceiver of transceivers) {
      const kind = transceiver.receiver?.track?.kind || transceiver.sender?.track?.kind;
      if (kind === 'audio' || kind === 'video') {
        try {
          transceiver.direction = 'recvonly';
        } catch {
          // Ignore locked transceiver direction
        }
        if (kind === 'audio') {
          if (transceiver.receiver?.track) transceiver.receiver.track.enabled = true;
        } else {
          this.preferHardwareVideoReceiverCodecs(transceiver);
        }
      }
    }
  }

  private preferHardwareVideoReceiverCodecs(transceiver: RTCRtpTransceiver): void {
    try {
      if (typeof RTCRtpReceiver.getCapabilities !== 'function' || typeof transceiver.setCodecPreferences !== 'function') {
        return;
      }
      const caps = RTCRtpReceiver.getCapabilities('video');
      if (!caps?.codecs?.length) return;

      const order = ['video/H264', 'video/VP8', 'video/VP9'];
      const prioritized: RTCRtpCodec[] = [];
      for (const mime of order) {
        for (const codec of caps.codecs) {
          if (codec.mimeType.toLowerCase() === mime.toLowerCase()) {
            prioritized.push(codec);
          }
        }
      }
      for (const codec of caps.codecs) {
        if (order.every((mime) => codec.mimeType.toLowerCase() !== mime.toLowerCase())) {
          prioritized.push(codec);
        }
      }
      transceiver.setCodecPreferences(prioritized);
    } catch {
      // Ignore if browser restricts receiver codec preferences
    }
  }

  private attachAdaptiveChannel(channel: RTCDataChannel): void {
    this.adaptiveChannel = channel;
    channel.onopen = () => {
      this.callbacks.onLog('INFO', 'WebRTC', 'Adaptive control DataChannel connected');
    };
    channel.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      const next = parseAdaptiveStreamState(event.data);
      if (!next) return;
      const prevTier = this.latestAdaptiveState?.tierId;
      this.latestAdaptiveState = next;
      if (prevTier && prevTier !== next.tierId) {
        this.callbacks.onLog(
          'INFO',
          'Adaptive',
          `Sender switched to ${next.tierLabel} (${next.targetBitrateKbps} kbps) — ${next.reason}`
        );
      }
    };
    channel.onclose = () => {
      if (this.adaptiveChannel === channel) {
        this.adaptiveChannel = null;
      }
    };
  }

  private sendReceiverFeedback(feedback: ReceiverNetworkFeedback): void {
    const channel = this.adaptiveChannel;
    if (!channel || channel.readyState !== 'open') return;
    try {
      channel.send(JSON.stringify(feedback));
    } catch {
      // Ignore transient DataChannel send errors
    }
  }

  private ensurePeerConnection(): RTCPeerConnection {
    if (this.pc) return this.pc;

    const iceServers = buildIceServers();
    const pc = new RTCPeerConnection({
      iceServers,
      iceCandidatePoolSize: 4,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    });
    this.remoteStream = new MediaStream();
    this.remoteDescriptionSet = false;
    this.pendingCandidates = [];
    this.prevBytesReceived = 0;
    this.prevStatsTimestamp = 0;
    this.prevPacketsReceived = 0;
    this.prevPacketsLost = 0;
    this.prevFramesDropped = 0;
    this.prevFramesDecoded = 0;
    this.stalledTicks = 0;
    this.latestAdaptiveState = null;

    pc.ondatachannel = (event) => {
      if (event.channel.label === 'droidcast-adaptive') {
        this.attachAdaptiveChannel(event.channel);
      }
    };

    pc.ontrack = (event) => {
      if (event.track.kind !== 'video' && event.track.kind !== 'audio') {
        event.track.stop();
        this.callbacks.onLog('INFO', 'WebRTC', `Disabled unsupported incoming ${event.track.kind} track`);
        return;
      }

      if (event.track.kind === 'video') {
        this.callbacks.onLog('INFO', 'WebRTC', 'Received remote screen video track');
        this.applyPlayoutDelay();
        try {
          event.track.contentHint = 'motion';
        } catch {
          // Ignore read-only contentHint implementations
        }
        void event.track.applyConstraints(RECEIVER_VIDEO_CONSTRAINTS).catch(() => {});
      } else {
        this.callbacks.onLog('INFO', 'WebRTC', 'Received remote system audio track');
      }

      const currentStream = this.remoteStream ?? new MediaStream();
      if (!currentStream.getTracks().some((track) => track.id === event.track.id)) {
        currentStream.addTrack(event.track);
      }
      this.remoteStream = currentStream;
      this.callbacks.onStream(new MediaStream(currentStream.getTracks()));

      event.track.onended = () => {
        currentStream.removeTrack(event.track);
        this.callbacks.onStream(new MediaStream(currentStream.getTracks()));
      };
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.callbacks.onIceCandidate(event.candidate.toJSON());
      }
    };

    pc.oniceconnectionstatechange = () => {
      this.callbacks.onLog('INFO', 'WebRTC', `ICE state -> ${pc.iceConnectionState}`);
      this.callbacks.onConnectionStateChange(pc.iceConnectionState, pc.connectionState);
      if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
        this.applyPlayoutDelay();
      } else if (pc.iceConnectionState === 'failed') {
        this.callbacks.onError(
          'ICE_FAILED',
          'ICE connection failed. Ensure both devices are on the same Wi-Fi network without client isolation.'
        );
      }
    };

    pc.onconnectionstatechange = () => {
      this.callbacks.onLog('INFO', 'WebRTC', `Peer connection state -> ${pc.connectionState}`);
      this.callbacks.onConnectionStateChange(pc.iceConnectionState, pc.connectionState);
      if (pc.connectionState === 'connected') {
        this.applyPlayoutDelay();
        this.startStatsPolling();
      } else if (pc.connectionState === 'failed') {
        this.callbacks.onError(
          'WEBRTC_FAILED',
          'WebRTC peer connection failed during media negotiation.'
        );
      }
    };

    this.pc = pc;
    return pc;
  }

  async handleOffer(sdp: string): Promise<string> {
    const pc = this.ensurePeerConnection();
    this.callbacks.onLog('INFO', 'WebRTC', 'Applying remote SDP offer');
    await pc.setRemoteDescription({ type: 'offer', sdp });
    this.remoteDescriptionSet = true;
    this.configureReceiveTransceivers(pc);

    while (this.pendingCandidates.length > 0) {
      const candidate = this.pendingCandidates.shift();
      if (candidate) {
        await pc.addIceCandidate(candidate).catch((err) => {
          this.callbacks.onLog('WARN', 'WebRTC', `Failed to apply queued ICE candidate: ${err}`);
        });
      }
    }

    const answer = await pc.createAnswer();
    const tunedAnswerSdp = tuneAnswerSdpForLowLatency(answer.sdp ?? '');
    await pc.setLocalDescription({ type: 'answer', sdp: tunedAnswerSdp });
    this.applyPlayoutDelay();
    this.callbacks.onLog('INFO', 'WebRTC', 'Created and set local SDP answer');
    return pc.localDescription?.sdp ?? tunedAnswerSdp;
  }

  async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
    const pc = this.ensurePeerConnection();
    if (!this.remoteDescriptionSet) {
      this.pendingCandidates.push(candidate);
      return;
    }
    try {
      await pc.addIceCandidate(candidate);
    } catch (err) {
      this.callbacks.onLog(
        'WARN',
        'WebRTC',
        `Error adding ICE candidate: ${err instanceof Error ? err.message : String(err)}`
      );
    }
  }

  private startStatsPolling(): void {
    if (this.statsTimer !== null) return;
    this.statsTimer = window.setInterval(async () => {
      const pc = this.pc;
      if (!pc) return;
      try {
        const report = await pc.getStats();
        let width = 0;
        let height = 0;
        let fps = 0;
        let bytesReceived = 0;
        let timestamp = 0;
        let packetsReceived = 0;
        let packetsLost = 0;
        let jitterMs = 0;
        let framesDropped = 0;
        let framesDecoded = 0;
        let rttMs: number | null = null;
        let codecId: string | null = null;
        let codec: string | null = null;
        let audioPacketsReceived = 0;

        report.forEach((stat) => {
          if (stat.type === 'inbound-rtp' && stat.kind === 'video') {
            width = stat.frameWidth ?? width;
            height = stat.frameHeight ?? height;
            fps = stat.framesPerSecond ?? fps;
            bytesReceived = stat.bytesReceived ?? bytesReceived;
            timestamp = stat.timestamp ?? timestamp;
            packetsReceived = stat.packetsReceived ?? packetsReceived;
            packetsLost = stat.packetsLost ?? packetsLost;
            jitterMs = typeof stat.jitter === 'number' ? Number((stat.jitter * 1000).toFixed(1)) : jitterMs;
            framesDropped = stat.framesDropped ?? framesDropped;
            framesDecoded = stat.framesDecoded ?? framesDecoded;
            codecId = stat.codecId ?? null;
          } else if (stat.type === 'inbound-rtp' && stat.kind === 'audio') {
            audioPacketsReceived = stat.packetsReceived ?? 0;
          } else if (
            stat.type === 'candidate-pair' &&
            (stat.nominated || stat.state === 'succeeded') &&
            typeof stat.currentRoundTripTime === 'number'
          ) {
            rttMs = Math.round(stat.currentRoundTripTime * 1000);
          }
        });

        if (codecId) {
          const codecStat = report.get(codecId) as { mimeType?: string } | undefined;
          codec = codecStat?.mimeType?.replace('video/', '') ?? null;
        }

        let bitrateKbps = 0;
        let packetLossPercent = 0;
        let droppedDelta = 0;
        if (this.prevStatsTimestamp > 0 && timestamp > this.prevStatsTimestamp) {
          const deltaSec = (timestamp - this.prevStatsTimestamp) / 1000;
          const deltaBytes = Math.max(0, bytesReceived - this.prevBytesReceived);
          bitrateKbps = Math.round((deltaBytes * 8) / deltaSec / 1000);
          packetLossPercent = computeIntervalPacketLossPercent(
            packetsReceived - this.prevPacketsReceived,
            packetsLost - this.prevPacketsLost
          );
          droppedDelta = Math.max(0, framesDropped - this.prevFramesDropped);
          if (framesDecoded === this.prevFramesDecoded && pc.connectionState === 'connected') {
            this.stalledTicks += 1;
          } else {
            this.stalledTicks = 0;
          }
        }
        this.prevBytesReceived = bytesReceived;
        this.prevStatsTimestamp = timestamp;
        this.prevPacketsReceived = packetsReceived;
        this.prevPacketsLost = packetsLost;
        this.prevFramesDropped = framesDropped;
        this.prevFramesDecoded = framesDecoded;

        const stalled = this.stalledTicks >= 2;
        const nextDelaySec = computeAdaptivePlayoutDelaySec({
          mode: this.playbackMode,
          packetLossPercent,
          jitterMs,
          currentDelaySec: this.currentPlayoutDelaySec,
        });
        if (nextDelaySec !== this.currentPlayoutDelaySec) {
          this.currentPlayoutDelaySec = nextDelaySec;
          this.applyPlayoutDelay(nextDelaySec);
        }

        const roundedFps = Math.round(fps);
        this.sendReceiverFeedback({
          type: 'receiver_feedback',
          packetLossPercent,
          jitterMs,
          rttMs,
          fps: roundedFps,
          bitrateKbps,
          framesDropped: droppedDelta,
          stalled,
          timestampMs: Date.now(),
        });

        this.callbacks.onStats({
          width,
          height,
          fps: roundedFps,
          bitrateKbps,
          packetLossPercent,
          jitterMs,
          framesDropped,
          rttMs,
          iceState: pc.iceConnectionState,
          peerState: pc.connectionState,
          codec,
          audioPacketsReceived,
          stalled,
          adaptiveState: this.latestAdaptiveState,
        });
      } catch {
        // Ignore transient getStats errors on teardown
      }
    }, 1000);
  }

  close(): void {
    if (this.statsTimer !== null) {
      window.clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
    if (this.adaptiveChannel) {
      this.adaptiveChannel.onopen = null;
      this.adaptiveChannel.onmessage = null;
      this.adaptiveChannel.onclose = null;
      try {
        this.adaptiveChannel.close();
      } catch {
        // Ignore
      }
      this.adaptiveChannel = null;
    }
    if (this.pc) {
      this.pc.ondatachannel = null;
      this.pc.ontrack = null;
      this.pc.onicecandidate = null;
      this.pc.oniceconnectionstatechange = null;
      this.pc.onconnectionstatechange = null;
      try {
        this.pc.close();
      } catch {
        // Ignore
      }
      this.pc = null;
    }
    if (this.remoteStream) {
      for (const track of this.remoteStream.getTracks()) {
        track.stop();
      }
      this.remoteStream = null;
    }
    this.pendingCandidates = [];
    this.remoteDescriptionSet = false;
    this.latestAdaptiveState = null;
  }
}
