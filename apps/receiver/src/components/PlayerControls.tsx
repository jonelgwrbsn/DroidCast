import {
  Activity,
  Expand,
  Maximize2,
  Minimize2,
  Shrink,
  Square,
  Volume2,
  VolumeX,
} from 'lucide-react';
import {
  QUALITY_PROFILES,
  type CastStartedMessage,
  type QualityPreset,
  type WebRTCStatsSnapshot,
} from '../lib/protocol';
import type { PlaybackMode } from '../lib/webrtc';

interface PlayerControlsProps {
  visible: boolean;
  senderName: string;
  quality: QualityPreset;
  playbackMode: PlaybackMode;
  stats: WebRTCStatsSnapshot;
  castInfo: CastStartedMessage | null;
  fillScreen: boolean;
  isFullscreen: boolean;
  showDetails: boolean;
  audioAvailable: boolean;
  audioEnabled: boolean;
  audioPlaybackBlocked: boolean;
  onToggleFillScreen: () => void;
  onToggleAudio: () => void;
  onQualityChange: (quality: QualityPreset) => void;
  onPlaybackModeChange: (mode: PlaybackMode) => void;
  onToggleFullscreen: () => void;
  onToggleDetails: () => void;
  onStopCasting: () => void;
}

const QUALITY_ORDER: QualityPreset[] = ['adaptive', 'native', '720p30', '1080p30', '1080p60', '1440p60'];

export function PlayerControls({
  visible,
  senderName,
  quality,
  playbackMode,
  stats,
  castInfo,
  fillScreen,
  isFullscreen,
  showDetails,
  audioAvailable,
  audioEnabled,
  audioPlaybackBlocked,
  onToggleFillScreen,
  onToggleAudio,
  onQualityChange,
  onPlaybackModeChange,
  onToggleFullscreen,
  onToggleDetails,
  onStopCasting,
}: PlayerControlsProps) {
  const resolutionLabel =
    stats.height > 0
      ? `${stats.height}p`
      : castInfo?.height
        ? `${castInfo.height}p`
        : 'Live';
  const fpsLabel = stats.fps > 0 ? `${stats.fps} FPS` : castInfo?.fps ? `${castInfo.fps} FPS` : '-- FPS';
  const bitrateLabel = stats.bitrateKbps > 0 ? `${(stats.bitrateKbps / 1000).toFixed(1)} Mbps` : '-- Mbps';
  const rttLabel = stats.rttMs !== null ? `${stats.rttMs} ms` : '-- ms';
  const networkQuality = stats.adaptiveState?.networkQuality ?? (stats.packetLossPercent >= 3 ? 'poor' : stats.packetLossPercent >= 1 ? 'fair' : 'excellent');
  const dotClass =
    networkQuality === 'poor'
      ? 'bg-rose-400'
      : networkQuality === 'fair'
        ? 'bg-amber-400'
        : 'bg-teal-400';

  return (
    <div
      className={`pointer-events-none absolute inset-x-0 bottom-0 z-20 transition-opacity duration-200 ${
        visible ? 'opacity-100' : 'opacity-0'
      }`}
    >
      <div className="pointer-events-auto flex flex-wrap items-center justify-between gap-3 border-t border-slate-800/80 bg-[#090B10]/92 px-4 py-3 backdrop-blur-sm sm:px-6">
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex items-center gap-2">
            <span className={`inline-block h-2 w-2 rounded-full ${dotClass}`} />
            <span className="text-sm font-semibold tracking-tight text-slate-100">DroidCast</span>
          </div>
          <span className="hidden text-xs text-slate-500 sm:inline">·</span>
          <span className="hidden truncate text-xs text-slate-300 sm:inline">{senderName}</span>
          <span className="text-xs text-slate-600">|</span>
          <span className="font-mono text-xs text-slate-300 tabular-nums">
            {quality === 'adaptive' ? 'Auto ' : ''}
            {resolutionLabel} · {fpsLabel} · {bitrateLabel} · {rttLabel}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={onToggleAudio}
            disabled={!audioAvailable}
            aria-pressed={audioEnabled && !audioPlaybackBlocked}
            aria-label={
              !audioAvailable
                ? 'System audio unavailable'
                : audioPlaybackBlocked
                  ? 'Enable system audio'
                  : audioEnabled
                    ? 'Mute system audio'
                    : 'Unmute system audio'
            }
            title={
              !audioAvailable
                ? 'System audio unavailable'
                : audioPlaybackBlocked
                  ? 'Click to enable system audio'
                  : audioEnabled
                    ? 'Mute system audio'
                    : 'Unmute system audio'
            }
            className="flex items-center gap-1.5 rounded-lg border border-slate-800 bg-[#121722] px-2.5 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-700 disabled:cursor-not-allowed disabled:text-slate-500"
          >
            {audioAvailable && audioEnabled && !audioPlaybackBlocked ? (
              <Volume2 className="h-3.5 w-3.5" />
            ) : (
              <VolumeX className="h-3.5 w-3.5" />
            )}
            <span className="hidden sm:inline">
              {!audioAvailable
                ? 'No Audio'
                : audioPlaybackBlocked
                  ? 'Enable Audio'
                  : audioEnabled
                    ? 'Audio On'
                    : 'Audio Off'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => onPlaybackModeChange(playbackMode === 'smooth' ? 'low-latency' : 'smooth')}
            title={
              playbackMode === 'smooth'
                ? 'Smooth Mode (~40ms buffer)'
                : 'Low Latency Mode (~15ms buffer)'
            }
            className="rounded-lg border border-slate-800 bg-[#121722] px-2.5 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-700"
          >
            {playbackMode === 'smooth' ? 'Smooth' : 'Low Latency'}
          </button>

          <label className="sr-only" htmlFor="quality-select">
            Video quality
          </label>
          <select
            id="quality-select"
            value={quality}
            onChange={(e) => onQualityChange(e.target.value as QualityPreset)}
            className="rounded-lg border border-slate-800 bg-[#121722] px-2.5 py-1.5 text-xs font-medium text-slate-200 focus:border-teal-500 focus:outline-none"
          >
            {QUALITY_ORDER.map((preset) => (
              <option key={preset} value={preset}>
                {QUALITY_PROFILES[preset].label}
              </option>
            ))}
          </select>

          <button
            type="button"
            onClick={onToggleDetails}
            aria-pressed={showDetails}
            className={`flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition ${
              showDetails
                ? 'border-teal-500/50 bg-teal-500/15 text-teal-300'
                : 'border-slate-800 bg-[#121722] text-slate-300 hover:border-slate-700'
            }`}
          >
            <Activity className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">Connection Details</span>
          </button>

          <button
            type="button"
            onClick={onToggleFillScreen}
            aria-pressed={fillScreen}
            title={fillScreen ? 'Fit entire screen (Z)' : 'Fill viewport (Z)'}
            className={`flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition ${
              fillScreen
                ? 'border-teal-500/50 bg-teal-500/15 text-teal-300'
                : 'border-slate-800 bg-[#121722] text-slate-200 hover:border-slate-700'
            }`}
          >
            {fillScreen ? (
              <Shrink className="h-3.5 w-3.5" />
            ) : (
              <Expand className="h-3.5 w-3.5" />
            )}
            <span>{fillScreen ? 'Fill' : 'Fit'}</span>
          </button>

          <button
            type="button"
            onClick={onToggleFullscreen}
            aria-label={isFullscreen ? 'Exit Cinema Mode' : 'Enter Cinema Mode'}
            title={isFullscreen ? 'Exit Cinema Mode (F)' : 'Cinema Mode (F)'}
            className="flex items-center gap-1.5 rounded-lg border border-slate-800 bg-[#121722] px-2.5 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-700"
          >
            {isFullscreen ? (
              <Minimize2 className="h-3.5 w-3.5" />
            ) : (
              <Maximize2 className="h-3.5 w-3.5" />
            )}
            <span className="hidden lg:inline">{isFullscreen ? 'Exit' : 'Cinema Mode'}</span>
          </button>

          <button
            type="button"
            onClick={onStopCasting}
            className="flex items-center gap-1.5 rounded-lg border border-rose-500/30 bg-rose-500/15 px-2.5 py-1.5 text-xs font-medium text-rose-300 transition hover:bg-rose-500/25"
          >
            <Square className="h-3 w-3 fill-current" />
            <span>Stop</span>
          </button>
        </div>
      </div>
    </div>
  );
}
