import { useCallback, useEffect, useRef, useState } from 'react';
import { PlayerControls } from './PlayerControls';
import type {
  CastStartedMessage,
  QualityPreset,
  WebRTCStatsSnapshot,
} from '../lib/protocol';

import type { PlaybackMode } from '../lib/webrtc';

interface VideoPlayerProps {
  stream: MediaStream | null;
  senderName: string;
  quality: QualityPreset;
  playbackMode: PlaybackMode;
  stats: WebRTCStatsSnapshot;
  castInfo: CastStartedMessage | null;
  isFullscreen: boolean;
  showDetails: boolean;
  containerRef: React.RefObject<HTMLDivElement | null>;
  onQualityChange: (quality: QualityPreset) => void;
  onPlaybackModeChange: (mode: PlaybackMode) => void;
  onToggleFullscreen: () => void;
  onToggleDetails: () => void;
  onStopCasting: () => void;
}

export function VideoPlayer({
  stream,
  senderName,
  quality,
  playbackMode,
  stats,
  castInfo,
  isFullscreen,
  showDetails,
  containerRef,
  onQualityChange,
  onPlaybackModeChange,
  onToggleFullscreen,
  onToggleDetails,
  onStopCasting,
}: VideoPlayerProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const hideTimerRef = useRef<number | null>(null);
  const lastResizeAtRef = useRef<number>(0);

  const [controlsVisible, setControlsVisible] = useState(true);
  const [fillScreen, setFillScreen] = useState(false);
  const [videoDimensions, setVideoDimensions] = useState<{ w: number; h: number }>({ w: 0, h: 0 });
  const [stalled, setStalled] = useState(false);
  const [audioEnabled, setAudioEnabled] = useState(true);
  const [audioPlaybackBlocked, setAudioPlaybackBlocked] = useState(false);
  const [adaptiveBanner, setAdaptiveBanner] = useState<string | null>(null);
  const lastAdaptiveTierRef = useRef<string | null>(null);

  const scheduleHide = useCallback(() => {
    if (hideTimerRef.current !== null) {
      window.clearTimeout(hideTimerRef.current);
    }
    setControlsVisible(true);
    hideTimerRef.current = window.setTimeout(() => {
      setControlsVisible(false);
    }, 2000);
  }, []);

  useEffect(() => {
    scheduleHide();
    return () => {
      if (hideTimerRef.current !== null) {
        window.clearTimeout(hideTimerRef.current);
      }
    };
  }, [scheduleHide]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const tag = document.activeElement?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (e.key === 'z' || e.key === 'Z') {
        setFillScreen((prev) => !prev);
        scheduleHide();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [scheduleHide]);

  useEffect(() => {
    const tierId = stats.adaptiveState?.tierId;
    if (!tierId) return;
    if (lastAdaptiveTierRef.current && lastAdaptiveTierRef.current !== tierId) {
      setAdaptiveBanner(`${stats.adaptiveState?.tierLabel} · ${stats.adaptiveState?.reason}`);
      const timer = window.setTimeout(() => setAdaptiveBanner(null), 3200);
      lastAdaptiveTierRef.current = tierId;
      return () => window.clearTimeout(timer);
    }
    lastAdaptiveTierRef.current = tierId;
    return undefined;
  }, [stats.adaptiveState?.tierId, stats.adaptiveState?.tierLabel, stats.adaptiveState?.reason]);

  useEffect(() => {
    const recentlyResized = Date.now() - lastResizeAtRef.current < 1600;
    if (!stream || stats.peerState !== 'connected' || stats.fps > 0 || recentlyResized) {
      setStalled(false);
      return;
    }
    const timer = window.setTimeout(() => {
      if (Date.now() - lastResizeAtRef.current >= 1600) {
        setStalled(true);
      }
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [stream, stats.peerState, stats.fps]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    if (video.srcObject !== stream) {
      video.srcObject = stream;
    }

    if (!stream) return;

    video.muted = true;
    const playPromise = video.play();
    if (playPromise && typeof playPromise.catch === 'function') {
      void playPromise.catch(() => {});
    }
  }, [stream]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const audioTracks = stream?.getAudioTracks() ?? [];
    audio.srcObject = audioTracks.length > 0 ? new MediaStream(audioTracks) : null;
    if (audioTracks.length === 0) {
      setAudioPlaybackBlocked(false);
      return;
    }

    audio.muted = !audioEnabled;
    if (!audioEnabled) return;

    const playPromise = audio.play();
    if (playPromise && typeof playPromise.then === 'function') {
      void playPromise.then(
        () => setAudioPlaybackBlocked(false),
        () => setAudioPlaybackBlocked(true)
      );
    }

    return () => {
      audio.pause();
      audio.srcObject = null;
    };
  }, [stream, audioEnabled]);

  const handleAudioToggle = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audioPlaybackBlocked || !audioEnabled) {
      audio.muted = false;
      setAudioEnabled(true);
      void audio.play().then(
        () => setAudioPlaybackBlocked(false),
        () => setAudioPlaybackBlocked(true)
      );
      return;
    }
    audio.muted = true;
    audio.pause();
    setAudioEnabled(false);
  }, [audioEnabled, audioPlaybackBlocked]);

  const audioAvailable = Boolean(
    stream?.getAudioTracks().some((track) => track.readyState === 'live')
  );

  return (
    <div
      ref={containerRef}
      onMouseMove={scheduleHide}
      onMouseEnter={scheduleHide}
      onTouchStart={scheduleHide}
      onDoubleClick={onToggleFullscreen}
      className={`relative flex h-full w-full items-center justify-center overflow-hidden bg-black select-none ${
        controlsVisible ? 'cursor-default' : 'cursor-none'
      }`}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted
        onLoadedMetadata={(e) => {
          const el = e.currentTarget;
          setVideoDimensions({ w: el.videoWidth, h: el.videoHeight });
        }}
        onResize={(e) => {
          lastResizeAtRef.current = Date.now();
          setStalled(false);
          const el = e.currentTarget;
          setVideoDimensions({ w: el.videoWidth, h: el.videoHeight });
        }}
        style={{ objectFit: fillScreen ? 'cover' : 'contain' }}
        className="h-full w-full transition-[width,height] duration-150 ease-out"
      />
      <audio ref={audioRef} autoPlay className="hidden" />

      {!stream && (
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/80 px-6 text-center">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-slate-700 border-t-teal-400" />
          <div className="text-sm font-medium text-slate-200">
            Waiting for video stream from {senderName}…
          </div>
          <div className="text-xs text-slate-400">
            Approve screen capture on your Android device to begin streaming.
          </div>
        </div>
      )}

      {stream && stalled && (
        <div className="pointer-events-none absolute top-4 left-1/2 z-30 flex -translate-x-1/2 items-center gap-2 rounded-lg border border-slate-800 bg-[#090B10]/90 px-3 py-1.5 text-xs text-slate-300">
          <span className="h-3 w-3 animate-spin rounded-full border-2 border-slate-600 border-t-teal-400" />
          <span>Buffering stream…</span>
        </div>
      )}

      {stream && adaptiveBanner && !stalled && (
        <div className="pointer-events-none absolute top-4 left-1/2 z-20 -translate-x-1/2 rounded-lg border border-teal-500/30 bg-[#090B10]/90 px-3 py-1.5 text-xs font-medium text-teal-200 shadow-lg">
          Auto-adjusted: {adaptiveBanner}
        </div>
      )}

      {videoDimensions.w > 0 && controlsVisible && showDetails && (
        <div className="pointer-events-none absolute top-4 right-4 z-10 hidden rounded-md border border-slate-800/80 bg-[#090B10]/80 px-2.5 py-1 font-mono text-[11px] text-slate-400 sm:block">
          {videoDimensions.w} × {videoDimensions.h}
          {stats.adaptiveState ? ` · ${stats.adaptiveState.targetBitrateKbps} kbps` : ''}
        </div>
      )}

      <PlayerControls
        visible={controlsVisible}
        senderName={senderName}
        quality={quality}
        playbackMode={playbackMode}
        stats={stats}
        castInfo={castInfo}
        fillScreen={fillScreen}
        isFullscreen={isFullscreen}
        showDetails={showDetails}
        audioAvailable={audioAvailable}
        audioEnabled={audioEnabled}
        audioPlaybackBlocked={audioPlaybackBlocked}
        onToggleFillScreen={() => setFillScreen((prev) => !prev)}
        onToggleAudio={handleAudioToggle}
        onQualityChange={onQualityChange}
        onPlaybackModeChange={onPlaybackModeChange}
        onToggleFullscreen={onToggleFullscreen}
        onToggleDetails={onToggleDetails}
        onStopCasting={onStopCasting}
      />
    </div>
  );
}
