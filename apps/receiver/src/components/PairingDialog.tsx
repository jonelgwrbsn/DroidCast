import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ShieldCheck, X } from 'lucide-react';

interface PairingDialogProps {
  senderName: string;
  errorMessage: string | null;
  onSubmit: (code: string) => void;
  onCancel: () => void;
}

export function PairingDialog({
  senderName,
  errorMessage,
  onSubmit,
  onCancel,
}: PairingDialogProps) {
  const [code, setCode] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const digitsOnly = code.replace(/\D/g, '').slice(0, 6);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (digitsOnly.length === 6) {
      onSubmit(digitsOnly);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="pairing-dialog-title"
    >
      <div className="w-full max-w-md rounded-xl border border-slate-800 bg-[#10141D] p-6 shadow-2xl">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg border border-teal-500/30 bg-teal-500/10 text-teal-400">
              <ShieldCheck className="h-5 w-5" />
            </div>
            <div>
              <h2 id="pairing-dialog-title" className="text-base font-semibold text-slate-100">
                Enter pairing code
              </h2>
              <p className="text-xs text-slate-400">
                Shown on <span className="text-slate-200 font-medium">{senderName}</span>
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onCancel}
            aria-label="Cancel pairing"
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="mt-5 space-y-4">
          <div>
            <label htmlFor="pairing-code-input" className="sr-only">
              6-digit pairing code
            </label>
            <input
              ref={inputRef}
              id="pairing-code-input"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={7}
              placeholder="482 913"
              value={
                digitsOnly.length > 3
                  ? `${digitsOnly.slice(0, 3)} ${digitsOnly.slice(3)}`
                  : digitsOnly
              }
              onChange={(e) => setCode(e.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-[#090B10] px-4 py-3 text-center font-mono text-2xl font-semibold tracking-[0.25em] text-slate-100 placeholder:text-slate-600 focus:border-teal-500 focus:outline-none"
            />
          </div>

          {errorMessage && (
            <p className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
              {errorMessage}
            </p>
          )}

          <div className="flex items-center justify-end gap-2.5 pt-1">
            <button
              type="button"
              onClick={onCancel}
              className="rounded-lg border border-slate-700 px-4 py-2 text-sm font-medium text-slate-300 hover:bg-slate-800"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={digitsOnly.length !== 6}
              className="rounded-lg bg-teal-500 px-5 py-2 text-sm font-semibold text-slate-950 transition hover:bg-teal-400 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Pair &amp; Cast
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
