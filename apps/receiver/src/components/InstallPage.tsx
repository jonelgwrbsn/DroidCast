import { useCallback, useEffect, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  Copy,
  Download,
  RefreshCw,
  ShieldCheck,
  Smartphone,
} from 'lucide-react';
import { getDefaultSignalingUrl } from '../hooks/useSignaling';

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
  receiverUrls?: string[];
  apk?: ApkInfoPayload;
}

interface InstallPageProps {
  onNavigateHome: () => void;
}

function toHttpBase(wsUrl: string): string {
  try {
    const parsed = new URL(wsUrl);
    const proto = parsed.protocol === 'wss:' ? 'https:' : 'http:';
    return `${proto}//${parsed.host}`;
  } catch {
    return window.location.origin;
  }
}

function formatSizeMb(sizeBytes: number | null | undefined): string {
  if (!sizeBytes || sizeBytes <= 0) return '--';
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

type DeviceBrandGuide = 'oppo-realme' | 'oneplus' | 'vivo' | 'general';

interface BrandGuideContent {
  id: DeviceBrandGuide;
  label: string;
  osName: string;
  settingsPath: string;
  securityNote: string;
  fallbackTip: string;
}

const BRAND_GUIDES: BrandGuideContent[] = [
  {
    id: 'oppo-realme',
    label: 'Oppo / Realme',
    osName: 'ColorOS & realme UI',
    settingsPath:
      'Settings → Security & privacy → More security & privacy → Installation sources (or Apps → Special app access → Install unknown apps)',
    securityNote:
      'ColorOS and realme UI run an automatic Security Check screen and may ask for your lock-screen PIN/password before enabling the Install button.',
    fallbackTip:
      'If tapping from Chrome does nothing or Install is greyed out, open My Files → Downloads (or APKs) and install from there after enabling My Files as a trusted source.',
  },
  {
    id: 'oneplus',
    label: 'OnePlus',
    osName: 'OxygenOS',
    settingsPath:
      'Settings → Security & privacy → More security settings → Install unknown apps (enable for Chrome and Files)',
    securityNote:
      'When OxygenOS shows "Your phone currently isn\'t allowed to install unknown apps", tap Settings in the popup, turn on Allow from this source, and navigate back.',
    fallbackTip:
      'If Google Play Protect flags the locally built debug APK, tap More details → Install anyway to proceed.',
  },
  {
    id: 'vivo',
    label: 'Vivo / iQOO',
    osName: 'FuntouchOS & OriginOS',
    settingsPath:
      'Settings → Security (or Privacy) → More security settings → Install unknown apps (enable Chrome, Vivo Browser, and Files)',
    securityNote:
      'Vivo\'s built-in iManager scans external APKs and may ask for your lock-screen passcode or display a "Continue installing" confirmation.',
    fallbackTip:
      'If browser installation is blocked, open the Files app → Downloads or Installation packages and launch DroidCast.apk directly.',
  },
  {
    id: 'general',
    label: 'All Android',
    osName: 'Android 7.0+ (API 24+)',
    settingsPath:
      'Settings → Apps → Special app access → Install unknown apps → enable your browser or Files app',
    securityNote:
      'If Google Play Protect warns that the developer is unrecognized, tap More details → Install anyway.',
    fallbackTip:
      'Ensure the full APK finishes downloading before opening it to avoid package parsing errors.',
  },
];

export function InstallPage({ onNavigateHome }: InstallPageProps) {
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [httpBase, setHttpBase] = useState<string>(() => toHttpBase(getDefaultSignalingUrl()));
  const [wsUrl, setWsUrl] = useState<string>(() => getDefaultSignalingUrl());
  const [apkInfo, setApkInfo] = useState<ApkInfoPayload | null>(null);
  const [copiedKey, setCopiedKey] = useState<'download' | 'ws' | null>(null);
  const [selectedBrand, setSelectedBrand] = useState<DeviceBrandGuide>('oppo-realme');

  const loadInstallInfo = useCallback(async () => {
    setLoading(true);
    setLoadError(null);

    const defaultWs = getDefaultSignalingUrl();
    const initialHttpBase = toHttpBase(defaultWs);
    let resolvedWs = defaultWs;
    let resolvedHttpBase = initialHttpBase;
    let resolvedApk: ApkInfoPayload | null = null;

    try {
      const res = await fetch(`${initialHttpBase}/info`);
      if (!res.ok) {
        throw new Error(`Signaling server returned HTTP ${res.status}`);
      }
      const info = (await res.json()) as ServerInfoResponse;
      if (info.wsUrls && info.wsUrls.length > 0) {
        resolvedWs = info.wsUrls[0];
        resolvedHttpBase = toHttpBase(resolvedWs);
      }
      if (info.apk) {
        resolvedApk = info.apk;
      }
    } catch (err) {
      setLoadError(
        err instanceof Error
          ? `Could not reach signaling server at ${initialHttpBase}.`
          : 'Could not reach signaling server.'
      );
    } finally {
      setWsUrl(resolvedWs);
      setHttpBase(resolvedHttpBase);
      setApkInfo(resolvedApk);
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadInstallInfo();
  }, [loadInstallInfo]);

  const downloadUrl = `${httpBase}${apkInfo?.downloadPath || '/download/droidcast.apk'}`;
  const apkFilename = apkInfo?.filename || 'DroidCast.apk';
  const sizeLabel = formatSizeMb(apkInfo?.sizeBytes);

  const handleCopy = async (key: 'download' | 'ws', value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedKey(key);
      window.setTimeout(() => setCopiedKey(null), 2000);
    } catch {
      // Ignore clipboard error
    }
  };

  return (
    <div className="install-page bg-[#090B10] text-slate-100">
      <main className="install-shell flex flex-col gap-5 sm:gap-6">
        <div className="flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={onNavigateHome}
            className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-slate-800 bg-[#10141D] px-3.5 py-2 text-xs font-medium text-slate-200 transition hover:border-slate-700 hover:text-white"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Back to Receiver</span>
          </button>

          <button
            type="button"
            onClick={() => void loadInstallInfo()}
            disabled={loading}
            className="inline-flex min-h-11 items-center gap-1.5 rounded-lg border border-slate-800 bg-[#10141D] px-3.5 py-2 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:text-white disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            <span>Refresh</span>
          </button>
        </div>

        <section className="install-card rounded-2xl border border-slate-800/90 bg-[#10141D] shadow-2xl">
          <div className="grid gap-6 md:grid-cols-[1fr_auto] md:items-center md:gap-8">
            <div className="min-w-0 space-y-5">
              <div className="inline-flex items-center gap-2 rounded-full border border-teal-500/30 bg-teal-500/10 px-3 py-1 text-xs font-semibold text-teal-300">
                <Smartphone className="h-3.5 w-3.5" />
                <span>Android Sender · Local Wi-Fi Installer</span>
              </div>

              <div>
                <h1 className="install-hero-title font-semibold tracking-tight text-slate-100">
                  Install DroidCast on Android
                </h1>
                <p className="install-hero-subtitle mt-2 text-slate-400">
                  Download the Android APK directly to your phone over local Wi-Fi without a USB cable.
                </p>
              </div>

              <div className="install-meta-grid grid grid-cols-2 gap-3">
                <div className="rounded-xl border border-slate-800 bg-[#0D1119] px-3.5 py-2.5">
                  <div className="text-[11px] font-medium uppercase tracking-wider text-slate-400">
                    Package File
                  </div>
                  <div className="mt-1 text-sm font-semibold text-slate-100">{apkFilename}</div>
                </div>
                <div className="rounded-xl border border-slate-800 bg-[#0D1119] px-3.5 py-2.5">
                  <div className="text-[11px] font-medium uppercase tracking-wider text-slate-400">
                    Size &amp; Compatibility
                  </div>
                  <div className="mt-1 text-sm font-semibold text-slate-100 tabular-nums">
                    {sizeLabel} · Android 7.0+
                  </div>
                </div>
              </div>

              {loadError && (
                <div className="install-guide-detail rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-rose-200">
                  {loadError} Start the signaling host (<code className="font-mono">npm run signaling</code>) and tap Refresh.
                </div>
              )}

              {apkInfo?.available ? (
                <a
                  href={downloadUrl}
                  download={apkFilename}
                  className="inline-flex min-h-12 w-full items-center justify-center gap-2.5 rounded-xl bg-teal-500 px-5 py-3 text-sm font-semibold text-slate-950 shadow-lg shadow-teal-500/15 transition hover:bg-teal-400"
                >
                  <Download className="h-4 w-4 shrink-0" />
                  <span>
                    Download {apkFilename} ({sizeLabel})
                  </span>
                </a>
              ) : (
                <div className="install-guide-detail rounded-xl border border-amber-500/35 bg-amber-500/10 px-4 py-3 text-amber-200">
                  {loading
                    ? 'Checking local APK build availability…'
                    : 'APK not found on the signaling host yet. Build app-debug.apk (gradle assembleDebug) and refresh.'}
                </div>
              )}

              <div className="space-y-1.5">
                <div className="text-[11px] font-medium uppercase tracking-wider text-slate-400">
                  Direct APK Download URL
                </div>
                <div className="flex items-center gap-2">
                  <code className="install-code-pill min-w-0 flex-1 truncate rounded-lg border border-slate-800 bg-[#090B10] px-3 py-2 font-mono text-teal-300">
                    {downloadUrl}
                  </code>
                  <button
                    type="button"
                    onClick={() => void handleCopy('download', downloadUrl)}
                    className="inline-flex min-h-10 shrink-0 items-center gap-1.5 rounded-lg border border-slate-800 bg-[#141925] px-3 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-700 hover:text-white"
                  >
                    {copiedKey === 'download' ? (
                      <Check className="h-3.5 w-3.5 text-teal-400" />
                    ) : (
                      <Copy className="h-3.5 w-3.5" />
                    )}
                    <span>{copiedKey === 'download' ? 'Copied' : 'Copy'}</span>
                  </button>
                </div>
              </div>
            </div>

            <div className="install-qr-card flex flex-col items-center justify-center rounded-2xl border border-slate-800 bg-[#0D1119] text-center">
              <div
                aria-label="QR code for DroidCast Android APK download"
                className="install-qr-frame rounded-xl border border-slate-800 bg-[#090B10] p-2"
              >
                <QRCodeSVG
                  value={downloadUrl}
                  size={176}
                  bgColor="#090B10"
                  fgColor="#F1F5F9"
                  level="M"
                  marginSize={1}
                />
              </div>
              <div className="mt-3 text-xs font-semibold text-slate-200">Scan to Download APK</div>
              <p className="install-guide-detail mt-1 max-w-[210px] text-slate-400">
                Scan with your phone camera to download {apkFilename} directly over local Wi-Fi.
              </p>
            </div>
          </div>
        </section>

        <section className="install-card rounded-2xl border border-slate-800/90 bg-[#10141D] space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-100">
              <ShieldCheck className="h-4 w-4 shrink-0 text-teal-400" />
              <span>Step-by-Step Android Sideloading Guide</span>
            </div>
            <span className="rounded-md border border-slate-800 bg-[#0D1119] px-2.5 py-1 text-[11px] font-medium text-slate-400">
              Oppo · Realme · OnePlus · Vivo
            </span>
          </div>

          <ol className="space-y-3 text-slate-300">
            <li className="flex gap-3 rounded-xl border border-slate-800/80 bg-[#0D1119] p-3.5">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-500/15 font-mono text-xs font-bold text-teal-300">
                1
              </span>
              <div className="min-w-0">
                <div className="install-guide-text font-medium text-slate-100">Download the full APK first</div>
                <p className="install-guide-detail mt-0.5 text-slate-400">
                  Tap <span className="text-slate-200">Download {apkFilename}</span> above and wait until the entire{' '}
                  <span className="font-mono text-slate-200">{sizeLabel}</span> transfer completes before opening the file.
                </p>
              </div>
            </li>

            <li className="flex gap-3 rounded-xl border border-slate-800/80 bg-[#0D1119] p-3.5">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-500/15 font-mono text-xs font-bold text-teal-300">
                2
              </span>
              <div className="min-w-0">
                <div className="install-guide-text font-medium text-slate-100">Allow &ldquo;Install unknown apps&rdquo;</div>
                <p className="install-guide-detail mt-0.5 text-slate-400">
                  When Android blocks the install, tap <span className="text-slate-200">Settings</span> in the popup and enable{' '}
                  <span className="text-slate-200">Allow from this source</span> for Chrome or your File Manager.
                </p>
              </div>
            </li>

            <li className="flex gap-3 rounded-xl border border-slate-800/80 bg-[#0D1119] p-3.5">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-500/15 font-mono text-xs font-bold text-teal-300">
                3
              </span>
              <div className="min-w-0">
                <div className="install-guide-text font-medium text-slate-100">Confirm OEM security scan &amp; Play Protect</div>
                <p className="install-guide-detail mt-0.5 text-slate-400">
                  Because DroidCast is distributed directly over your LAN (not via Play Store), tap{' '}
                  <span className="text-slate-200">More details → Install anyway</span> if Google Play Protect prompts, or complete the device&apos;s security scan check.
                </p>
              </div>
            </li>

            <li className="flex gap-3 rounded-xl border border-slate-800/80 bg-[#0D1119] p-3.5">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-500/15 font-mono text-xs font-bold text-teal-300">
                4
              </span>
              <div className="min-w-0 flex-1">
                <div className="install-guide-text font-medium text-slate-100">Open DroidCast &amp; connect over Wi-Fi</div>
                <p className="install-guide-detail mt-0.5 text-slate-400">
                  Launch DroidCast on your phone, allow notification/audio permissions when asked, and connect to this receiver signaling URL:
                </p>
                <div className="mt-2 flex items-center gap-2">
                  <code className="install-code-pill min-w-0 flex-1 truncate rounded-lg border border-slate-800 bg-[#090B10] px-3 py-2 font-mono text-teal-300">
                    {wsUrl}
                  </code>
                  <button
                    type="button"
                    onClick={() => void handleCopy('ws', wsUrl)}
                    className="inline-flex min-h-10 shrink-0 items-center gap-1.5 rounded-lg border border-slate-800 bg-[#141925] px-3 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-700 hover:text-white"
                  >
                    {copiedKey === 'ws' ? (
                      <Check className="h-3.5 w-3.5 text-teal-400" />
                    ) : (
                      <Copy className="h-3.5 w-3.5" />
                    )}
                    <span>{copiedKey === 'ws' ? 'Copied' : 'Copy'}</span>
                  </button>
                </div>
              </div>
            </li>
          </ol>

          <div className="border-t border-slate-800 pt-5 space-y-3.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Device-Specific Security Settings
              </div>
              <div
                role="tablist"
                aria-label="Select Android manufacturer"
                className="flex flex-wrap gap-1.5 rounded-lg border border-slate-800 bg-[#090B10] p-1"
              >
                {BRAND_GUIDES.map((guide) => (
                  <button
                    key={guide.id}
                    type="button"
                    role="tab"
                    aria-selected={selectedBrand === guide.id}
                    onClick={() => setSelectedBrand(guide.id)}
                    className={`min-h-9 rounded-md px-2.5 py-1 text-xs font-medium transition ${
                      selectedBrand === guide.id
                        ? 'bg-[#161C28] text-teal-300'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {guide.label}
                  </button>
                ))}
              </div>
            </div>

            {(() => {
              const activeGuide =
                BRAND_GUIDES.find((g) => g.id === selectedBrand) ?? BRAND_GUIDES[0];
              return (
                <div className="install-guide-detail rounded-xl border border-slate-800 bg-[#0D1119] p-4 space-y-2.5">
                  <div className="font-semibold text-teal-300">
                    {activeGuide.label} · {activeGuide.osName}
                  </div>
                  <div>
                    <span className="font-medium text-slate-200">Settings Path: </span>
                    <span className="text-slate-300">{activeGuide.settingsPath}</span>
                  </div>
                  <div>
                    <span className="font-medium text-slate-200">Security Prompt: </span>
                    <span className="text-slate-400">{activeGuide.securityNote}</span>
                  </div>
                  <div>
                    <span className="font-medium text-slate-200">If Blocked: </span>
                    <span className="text-slate-400">{activeGuide.fallbackTip}</span>
                  </div>
                </div>
              );
            })()}
          </div>

          <div className="rounded-xl border border-amber-500/25 bg-amber-500/5 p-4 space-y-2">
            <div className="flex items-center gap-2 text-xs font-semibold text-amber-300">
              <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
              <span>Quick Fixes for Common Install Errors</span>
            </div>
            <ul className="install-guide-detail space-y-1.5 text-slate-300 list-disc list-inside">
              <li>
                <span className="font-medium text-slate-100">&ldquo;Problem parsing the package&rdquo;</span>: Re-download the APK completely and verify the device runs Android 7.0+.
              </li>
              <li>
                <span className="font-medium text-slate-100">&ldquo;App not installed / conflicts with existing package&rdquo;</span>: Uninstall any older DroidCast build first, then install this APK.
              </li>
              <li>
                <span className="font-medium text-slate-100">&ldquo;Install&rdquo; button won&apos;t tap</span>: Disable screen-dimming/floating overlay apps or open <span className="font-mono text-slate-200">{apkFilename}</span> from your phone&apos;s <span className="text-slate-100">Files</span> app.
              </li>
            </ul>
          </div>
        </section>
      </main>
    </div>
  );
}
