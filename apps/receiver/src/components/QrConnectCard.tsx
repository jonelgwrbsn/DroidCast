import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { Check, Copy, Download, QrCode, Smartphone } from 'lucide-react';

interface QrConnectCardProps {
  signalingUrl: string;
  onOpenInstall?: () => void;
}

interface ApkInfoPayload {
  available: boolean;
  filename: string;
  sizeBytes: number | null;
  downloadPath: string;
  installPagePath: string;
  updatedAt: string | null;
}

interface ServerInfoResponse {
  port: number;
  addresses: string[];
  wsUrls: string[];
  apk?: ApkInfoPayload;
}

type QrMode = 'connect' | 'install';

function buildHttpBaseUrl(wsUrl: string): string {
  try {
    const parsed = new URL(wsUrl);
    const proto = parsed.protocol === 'wss:' ? 'https:' : 'http:';
    return `${proto}//${parsed.host}`;
  } catch {
    return '';
  }
}

function formatSizeMb(sizeBytes: number | null | undefined): string | null {
  if (!sizeBytes || sizeBytes <= 0) return null;
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function QrConnectCard({ signalingUrl, onOpenInstall }: QrConnectCardProps) {
  const [mode, setMode] = useState<QrMode>('connect');
  const [lanWsUrl, setLanWsUrl] = useState<string | null>(null);
  const [apkInfo, setApkInfo] = useState<ApkInfoPayload | null>(null);
  const [connectQrUrl, setConnectQrUrl] = useState<string | null>(null);
  const [installQrUrl, setInstallQrUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function loadInfo() {
      let targetWs = signalingUrl;
      let resolvedApk: ApkInfoPayload | null = null;
      const initialHttpBase = buildHttpBaseUrl(signalingUrl);

      try {
        const res = await fetch(`${initialHttpBase}/info`);
        if (res.ok) {
          const info = (await res.json()) as ServerInfoResponse;
          if (info.wsUrls && info.wsUrls.length > 0) {
            targetWs = info.wsUrls[0];
          }
          if (info.apk) {
            resolvedApk = info.apk;
          }
        }
      } catch {
        // Fallback to active signalingUrl if /info is unreachable
      }

      if (cancelled) return;
      setLanWsUrl(targetWs);
      setApkInfo(resolvedApk);

      const targetHttpBase = buildHttpBaseUrl(targetWs) || window.location.origin;
      const deepLink = `droidcast://connect?ws=${encodeURIComponent(targetWs)}`;
      const installUrl = `${targetHttpBase}${resolvedApk?.installPagePath || '/install'}`;

      try {
        const [connectDataUrl, installDataUrl] = await Promise.all([
          QRCode.toDataURL(deepLink, {
            width: 148,
            margin: 1,
            color: { dark: '#F1F5F9', light: '#090B10' },
          }),
          QRCode.toDataURL(installUrl, {
            width: 148,
            margin: 1,
            color: { dark: '#F1F5F9', light: '#090B10' },
          }),
        ]);
        if (!cancelled) {
          setConnectQrUrl(connectDataUrl);
          setInstallQrUrl(installDataUrl);
        }
      } catch {
        // Ignore QR generation error
      }
    }

    void loadInfo();
    return () => {
      cancelled = true;
    };
  }, [signalingUrl]);

  if (!lanWsUrl) return null;

  const httpBase = buildHttpBaseUrl(lanWsUrl) || window.location.origin;
  const installPageUrl = `${httpBase}${apkInfo?.installPagePath || '/install'}`;
  const apkDownloadUrl = `${httpBase}${apkInfo?.downloadPath || '/download/droidcast.apk'}`;
  const displayHostPort = lanWsUrl.replace(/^wss?:\/\//, '');
  const activeQrUrl = mode === 'connect' ? connectQrUrl : installQrUrl;
  const copyTarget = mode === 'connect' ? displayHostPort : installPageUrl;
  const apkSizeLabel = formatSizeMb(apkInfo?.sizeBytes);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(copyTarget);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignore clipboard error
    }
  };

  return (
    <div className="space-y-3 rounded-xl border border-slate-800/90 bg-[#0D1119] p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">
          {mode === 'connect' ? 'Quick Connect from Android' : 'Get DroidCast Android APK'}
        </div>

        <div
          role="tablist"
          aria-label="Phone setup mode"
          className="inline-flex rounded-lg border border-slate-800 bg-[#090B10] p-0.5 text-xs"
        >
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'connect'}
            onClick={() => {
              setCopied(false);
              setMode('connect');
            }}
            className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 font-medium transition ${
              mode === 'connect'
                ? 'bg-[#161C28] text-teal-300'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Smartphone className="h-3.5 w-3.5" />
            <span>Connect App</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'install'}
            onClick={() => {
              setCopied(false);
              setMode('install');
            }}
            className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 font-medium transition ${
              mode === 'install'
                ? 'bg-[#161C28] text-teal-300'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Download className="h-3.5 w-3.5" />
            <span>Get APK</span>
          </button>
        </div>
      </div>

      <div className="flex flex-col sm:flex-row items-center gap-4">
        {activeQrUrl ? (
          <img
            src={activeQrUrl}
            alt={
              mode === 'connect'
                ? 'Scan with Android camera to connect DroidCast'
                : 'Scan with Android camera to download DroidCast APK over local Wi-Fi'
            }
            className="h-28 w-28 shrink-0 rounded-lg border border-slate-800 bg-[#090B10] p-1"
          />
        ) : (
          <div className="flex h-28 w-28 shrink-0 items-center justify-center rounded-lg border border-slate-800 bg-[#090B10] text-slate-500">
            <QrCode className="h-8 w-8" />
          </div>
        )}

        <div className="min-w-0 flex-1 space-y-2 text-center sm:text-left">
          {mode === 'connect' ? (
            <p className="text-xs text-slate-400">
              Scan with your phone camera or enter this address in DroidCast:
            </p>
          ) : (
            <div className="space-y-1">
              <p className="text-xs text-slate-300">
                Scan with your phone camera to open the local Wi-Fi APK installer
                {apkSizeLabel ? ` (${apkSizeLabel})` : ''}.
              </p>
              <p className="text-[11px] text-slate-400">
                Oppo, Realme, OnePlus &amp; Vivo: enable{' '}
                <span className="text-slate-200">Install unknown apps</span> for your browser when prompted.
              </p>
            </div>
          )}

          <div className="flex flex-wrap items-center justify-center sm:justify-start gap-2">
            <code className="max-w-full truncate rounded-md border border-slate-800 bg-[#090B10] px-2.5 py-1 font-mono text-xs text-teal-300">
              {copyTarget}
            </code>
            <button
              type="button"
              onClick={handleCopy}
              aria-label={mode === 'connect' ? 'Copy receiver address' : 'Copy APK install link'}
              className="inline-flex items-center gap-1 rounded-md border border-slate-800 bg-[#141925] px-2 py-1 text-xs text-slate-300 hover:border-slate-700 hover:text-white"
            >
              {copied ? <Check className="h-3.5 w-3.5 text-teal-400" /> : <Copy className="h-3.5 w-3.5" />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>

            {mode === 'install' && (
              apkInfo?.available ? (
                <a
                  href={apkDownloadUrl}
                  download={apkInfo.filename}
                  className="inline-flex items-center gap-1.5 rounded-md bg-teal-500 px-2.5 py-1 text-xs font-semibold text-slate-950 transition hover:bg-teal-400"
                >
                  <Download className="h-3.5 w-3.5" />
                  <span>Download APK</span>
                </a>
              ) : (
                <span className="rounded-md border border-amber-500/30 bg-amber-500/10 px-2 py-1 text-[11px] font-medium text-amber-300">
                  APK not built on host
                </span>
              )
            )}
            {mode === 'install' && onOpenInstall && (
              <button
                type="button"
                onClick={onOpenInstall}
                className="rounded-md border border-slate-800 bg-[#141925] px-2 py-1 text-xs text-teal-300 hover:border-slate-700 hover:text-teal-200"
              >
                Open /install
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
