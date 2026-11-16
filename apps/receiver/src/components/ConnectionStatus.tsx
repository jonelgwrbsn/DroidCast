import type { SignalingConnectionState } from '../lib/signaling';

interface ConnectionStatusProps {
  connectionState: SignalingConnectionState;
  justReconnected: boolean;
  onRetry: () => void;
}

export function ConnectionStatus({
  connectionState,
  justReconnected,
  onRetry,
}: ConnectionStatusProps) {
  const isOnline = connectionState === 'connected';
  const isReconnecting = connectionState === 'reconnecting' || connectionState === 'connecting';

  return (
    <div className="grid grid-cols-2 gap-3 sm:gap-4">
      <div className="rounded-lg border border-slate-800/90 bg-[#10141D] px-4 py-3">
        <div className="text-xs font-medium tracking-wide text-slate-400">Receiver</div>
        <div className="mt-1.5 flex items-center gap-2 text-sm font-medium text-slate-100">
          <span
            className={`inline-block h-2 w-2 rounded-full ${
              isOnline ? 'bg-teal-400' : 'bg-amber-400 animate-pulse'
            }`}
          />
          <span>{isOnline ? 'Running' : 'Starting…'}</span>
        </div>
      </div>

      <div className="rounded-lg border border-slate-800/90 bg-[#10141D] px-4 py-3">
        <div className="text-xs font-medium tracking-wide text-slate-400">Network</div>
        <div className="mt-1.5 flex items-center justify-between gap-2 text-sm font-medium">
          <div className="flex items-center gap-2">
            <span
              className={`inline-block h-2 w-2 rounded-full ${
                justReconnected
                  ? 'bg-emerald-400'
                  : isOnline
                    ? 'bg-teal-400'
                    : isReconnecting
                      ? 'bg-amber-400 animate-pulse'
                      : 'bg-rose-400'
              }`}
            />
            <span className="text-slate-100">
              {justReconnected
                ? 'Reconnected'
                : isOnline
                  ? 'Connected'
                  : isReconnecting
                    ? 'Reconnecting…'
                    : 'Disconnected'}
            </span>
          </div>
          {!isOnline && !isReconnecting && (
            <button
              type="button"
              onClick={onRetry}
              className="text-xs font-medium text-teal-400 hover:text-teal-300"
            >
              Retry
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
