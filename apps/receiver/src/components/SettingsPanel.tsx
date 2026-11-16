import { useState, type FormEvent } from 'react';
import { X } from 'lucide-react';
import type {
  CastStartedMessage,
  LogEntry,
  WebRTCStatsSnapshot,
} from '../lib/protocol';

interface SettingsPanelProps {
  open: boolean;
  signalingUrl: string;
  receiverName: string;
  stats: WebRTCStatsSnapshot;
  castInfo: CastStartedMessage | null;
  logs: LogEntry[];
  isCasting: boolean;
  onSaveSettings: (url: string, name: string) => void;
  onClose: () => void;
}

export function SettingsPanel({
  open,
  signalingUrl,
  receiverName,
  stats,
  castInfo,
  logs,
  isCasting,
  onSaveSettings,
  onClose,
}: SettingsPanelProps) {
  const [urlDraft, setUrlDraft] = useState(signalingUrl);
  const [nameDraft, setNameDraft] = useState(receiverName);

  if (!open) return null;

  const handleSave = (e: FormEvent) => {
    e.preventDefault();
    onSaveSettings(urlDraft, nameDraft);
    onClose();
  };

  return (
    <aside
      className="fixed inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-slate-800 bg-[#0D1017] shadow-2xl"
      aria-label="Connection Details and Receiver Settings"
    >
      <div className="flex items-center justify-between border-b border-slate-800 px-5 py-4">
        <h2 className="text-sm font-semibold tracking-wide text-slate-100">
          {isCasting ? 'Connection Details' : 'Receiver Settings'}
        </h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close panel"
          className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-100"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 space-y-6 overflow-y-auto px-5 py-5">
        {isCasting && (
          <section className="space-y-3">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              WebRTC Telemetry
            </h3>
            <div className="grid grid-cols-2 gap-2.5">
              <StatItem
                label="Resolution"
                value={stats.width > 0 ? `${stats.width}×${stats.height}` : '--'}
              />
              <StatItem label="Frame Rate" value={`${stats.fps} FPS`} />
              <StatItem label="Rx Bitrate" value={`${stats.bitrateKbps} kbps`} />
              <StatItem
                label="Target Cap"
                value={
                  stats.adaptiveState ? `${stats.adaptiveState.targetBitrateKbps} kbps` : '--'
                }
              />
              <StatItem
                label="Round-Trip Time"
                value={stats.rttMs !== null ? `${stats.rttMs} ms` : '--'}
              />
              <StatItem label="Packet Loss" value={`${stats.packetLossPercent}%`} />
              <StatItem label="Jitter" value={`${stats.jitterMs} ms`} />
              <StatItem label="Dropped Frames" value={String(stats.framesDropped)} />
              <StatItem
                label="Adaptive Tier"
                value={stats.adaptiveState?.tierLabel ?? '--'}
              />
              <StatItem
                label="Network Quality"
                value={
                  stats.adaptiveState
                    ? stats.adaptiveState.networkQuality.toUpperCase()
                    : '--'
                }
              />
              <StatItem label="Codec" value={stats.codec ?? '--'} />
              <StatItem
                label="Stream Mode"
                value={castInfo?.audioAvailable ? 'Screen + System Audio' : 'Screen Only'}
              />
            </div>
            {castInfo?.audioReason && (
              <p className="rounded-lg border border-slate-800 bg-[#121722] px-3 py-2 text-xs text-slate-300">
                Audio: {castInfo.audioReason}
              </p>
            )}
            {stats.adaptiveState?.reason && (
              <p className="rounded-lg border border-slate-800 bg-[#121722] px-3 py-2 text-xs text-slate-300">
                Adaptive status: {stats.adaptiveState.reason}
              </p>
            )}
          </section>
        )}

        <section>
          <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
            Receiver Configuration
          </h3>
          <form onSubmit={handleSave} className="mt-3 space-y-3.5">
            <div>
              <label htmlFor="receiver-name" className="block text-xs font-medium text-slate-300">
                Receiver Name
              </label>
              <input
                id="receiver-name"
                type="text"
                value={nameDraft}
                onChange={(e) => setNameDraft(e.target.value)}
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-[#090B10] px-3 py-2 text-sm text-slate-100 focus:border-teal-500 focus:outline-none"
              />
            </div>

            <div>
              <label htmlFor="signaling-url" className="block text-xs font-medium text-slate-300">
                Signaling Server WebSocket URL
              </label>
              <input
                id="signaling-url"
                type="text"
                value={urlDraft}
                onChange={(e) => setUrlDraft(e.target.value)}
                placeholder="ws://192.168.1.100:3000"
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-[#090B10] px-3 py-2 font-mono text-xs text-slate-100 focus:border-teal-500 focus:outline-none"
              />
            </div>

            <button
              type="submit"
              className="w-full rounded-lg bg-slate-100 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-white"
            >
              Apply &amp; Reconnect
            </button>
          </form>
        </section>

        <section className="space-y-2.5">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
            Technical Log
          </h3>
          <div className="max-h-64 overflow-y-auto rounded-lg border border-slate-800 bg-[#090B10] p-3 font-mono text-[11px] leading-relaxed">
            {logs.length === 0 ? (
              <div className="text-slate-500">No log events yet.</div>
            ) : (
              logs.map((entry) => (
                <div key={entry.id} className="py-0.5">
                  <span className="text-slate-500">{entry.timestamp} </span>
                  <span
                    className={
                      entry.level === 'ERROR'
                        ? 'text-rose-400'
                        : entry.level === 'WARN'
                          ? 'text-amber-400'
                          : 'text-teal-400'
                    }
                  >
                    [{entry.level}]
                  </span>{' '}
                  <span className="text-slate-400">{entry.scope}:</span>{' '}
                  <span className="text-slate-200">{entry.message}</span>
                </div>
              ))
            )}
          </div>
        </section>
      </div>
    </aside>
  );
}

function StatItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-800/90 bg-[#121722] px-3 py-2.5">
      <div className="text-[11px] text-slate-400">{label}</div>
      <div className="mt-1 font-mono text-sm font-medium text-slate-100 tabular-nums">{value}</div>
    </div>
  );
}
