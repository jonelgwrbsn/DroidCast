import { Smartphone } from 'lucide-react';
import type { DeviceInfo } from '../lib/protocol';

interface DeviceCardProps {
  device: DeviceInfo;
  onConnect: (deviceId: string) => void;
  disabled?: boolean;
}

export function DeviceCard({ device, onConnect, disabled }: DeviceCardProps) {
  const isBusy = device.status === 'casting' || device.status === 'pairing';

  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-slate-800 bg-[#10141D] px-5 py-4 transition-colors hover:border-slate-700">
      <div className="flex items-center gap-3.5 min-w-0">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-slate-800 bg-[#161C28] text-slate-200">
          <Smartphone className="h-5 w-5" />
        </div>
        <div className="min-w-0">
          <div className="truncate text-base font-medium text-slate-100">{device.name}</div>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
            <span
              className={`inline-block h-2 w-2 rounded-full ${
                device.status === 'ready'
                  ? 'bg-teal-400'
                  : device.status === 'pairing'
                    ? 'bg-amber-400 animate-pulse'
                    : 'bg-sky-400'
              }`}
            />
            <span>
              {device.status === 'ready'
                ? 'Ready to Cast'
                : device.status === 'pairing'
                  ? 'Pairing…'
                  : 'Casting'}
            </span>
            {device.address && (
              <span className="font-mono text-slate-500">· {device.address}</span>
            )}
          </div>
        </div>
      </div>

      <button
        type="button"
        disabled={disabled || isBusy}
        onClick={() => onConnect(device.deviceId)}
        className="shrink-0 rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-slate-950 transition hover:bg-teal-400 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {device.status === 'pairing' ? 'Pairing…' : 'Connect'}
      </button>
    </div>
  );
}
