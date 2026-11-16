import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export interface ResolvedApkArtifact {
  filePath: string;
  filename: string;
  sizeBytes: number;
  updatedAt: string;
}

export interface ApkMetadata {
  available: boolean;
  filename: string;
  sizeBytes: number | null;
  downloadPath: string;
  installPagePath: string;
  updatedAt: string | null;
}

const APK_FILENAME = 'DroidCast.apk';
const DOWNLOAD_PATH = '/download/droidcast.apk';
const INSTALL_PAGE_PATH = '/install';

export function resolveApkArtifact(overridePath?: string): ResolvedApkArtifact | null {
  const envPath = process.env.DROIDCAST_APK_PATH?.trim();
  const explicitPath = overridePath ?? envPath;

  const moduleDir = path.dirname(fileURLToPath(import.meta.url));
  const candidates = explicitPath
    ? [path.resolve(explicitPath)]
    : [
        path.resolve(moduleDir, '../../../.build-outputs/app-debug.apk'),
        path.resolve(moduleDir, '../../../app/build/outputs/apk/debug/app-debug.apk'),
      ];

  for (const candidate of candidates) {
    try {
      const stat = fs.statSync(candidate);
      if (stat.isFile() && stat.size > 0) {
        return {
          filePath: candidate,
          filename: APK_FILENAME,
          sizeBytes: stat.size,
          updatedAt: stat.mtime.toISOString(),
        };
      }
    } catch {
      // Try next candidate
    }
  }

  return null;
}

export function buildApkMetadata(apk: ResolvedApkArtifact | null): ApkMetadata {
  return {
    available: Boolean(apk),
    filename: APK_FILENAME,
    sizeBytes: apk ? apk.sizeBytes : null,
    downloadPath: DOWNLOAD_PATH,
    installPagePath: INSTALL_PAGE_PATH,
    updatedAt: apk ? apk.updatedAt : null,
  };
}

function formatSizeMb(sizeBytes: number | null): string {
  if (!sizeBytes || sizeBytes <= 0) return '--';
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function renderInstallPageHtml(params: {
  apk: ApkMetadata;
  wsUrls: string[];
  hostLabel: string;
}): string {
  const { apk, wsUrls, hostLabel } = params;
  const primaryWsUrl = wsUrls[0] ?? 'ws://localhost:3000';
  const sizeLabel = formatSizeMb(apk.sizeBytes);
  const safeHostLabel = escapeHtml(hostLabel);
  const safeWsUrl = escapeHtml(primaryWsUrl);

  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Install DroidCast for Android</title>
    <style>
      :root {
        color-scheme: dark;
        --bg: #090b10;
        --surface: #10141d;
        --surface-alt: #0d1119;
        --border: #1e293b;
        --text: #f1f5f9;
        --muted: #94a3b8;
        --teal: #14b8a6;
        --teal-hover: #2dd4bf;
        --amber: #f59e0b;
      }
      * { box-sizing: border-box; }
      body {
        margin: 0;
        min-height: 100vh;
        background: var(--bg);
        color: var(--text);
        font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        line-height: 1.5;
        padding: 20px 16px 36px;
        display: flex;
        justify-content: center;
      }
      .wrap {
        width: 100%;
        max-width: 480px;
      }
      .card {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 16px;
        padding: 22px 20px;
        box-shadow: 0 20px 45px rgba(0, 0, 0, 0.45);
      }
      .badge {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        padding: 4px 10px;
        border-radius: 999px;
        border: 1px solid rgba(20, 184, 166, 0.35);
        background: rgba(20, 184, 166, 0.12);
        color: #5eead4;
        font-size: 12px;
        font-weight: 600;
      }
      .dot {
        width: 7px;
        height: 7px;
        border-radius: 50%;
        background: var(--teal);
      }
      h1 {
        margin: 14px 0 6px;
        font-size: 24px;
        letter-spacing: -0.02em;
      }
      .subtitle {
        margin: 0;
        color: var(--muted);
        font-size: 14px;
      }
      .meta-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 10px;
        margin: 18px 0;
      }
      .meta-box {
        background: var(--surface-alt);
        border: 1px solid var(--border);
        border-radius: 10px;
        padding: 10px 12px;
      }
      .meta-label {
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: var(--muted);
      }
      .meta-value {
        margin-top: 4px;
        font-size: 13px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
      .btn-download {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        min-height: 48px;
        border-radius: 12px;
        background: var(--teal);
        color: #020617;
        font-size: 15px;
        font-weight: 700;
        text-decoration: none;
        transition: background 0.15s ease;
      }
      .btn-download:hover {
        background: var(--teal-hover);
      }
      .unavailable {
        border: 1px solid rgba(245, 158, 11, 0.4);
        background: rgba(245, 158, 11, 0.12);
        color: #fde68a;
        border-radius: 12px;
        padding: 14px;
        font-size: 13px;
      }
      .section {
        margin-top: 20px;
        padding-top: 18px;
        border-top: 1px solid var(--border);
      }
      h2 {
        margin: 0 0 10px;
        font-size: 14px;
        font-weight: 600;
        color: var(--text);
      }
      ol {
        margin: 0;
        padding-left: 18px;
        color: #cbd5e1;
        font-size: 13px;
      }
      li + li {
        margin-top: 8px;
      }
      .ws-row {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 10px;
      }
      code {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        background: var(--bg);
        border: 1px solid var(--border);
        border-radius: 8px;
        padding: 8px 10px;
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 12px;
        color: #5eead4;
      }
      .btn-copy {
        min-height: 36px;
        padding: 0 12px;
        border-radius: 8px;
        border: 1px solid var(--border);
        background: #161c28;
        color: var(--text);
        font-size: 12px;
        font-weight: 600;
        cursor: pointer;
      }
      .footer-link {
        display: inline-block;
        margin-top: 16px;
        font-size: 13px;
        color: #5eead4;
        text-decoration: none;
      }
    </style>
  </head>
  <body>
    <main class="wrap">
      <section class="card">
        <span class="badge"><span class="dot"></span>Local Wi-Fi Installer · ${safeHostLabel}</span>
        <h1>Install DroidCast</h1>
        <p class="subtitle">Download the Android sender directly over local Wi-Fi—no USB cable required.</p>

        <div class="meta-grid">
          <div class="meta-box">
            <div class="meta-label">Package</div>
            <div class="meta-value">${escapeHtml(apk.filename)}</div>
          </div>
          <div class="meta-box">
            <div class="meta-label">Size &amp; Android</div>
            <div class="meta-value">${escapeHtml(sizeLabel)} · Android 7.0+</div>
          </div>
        </div>

        ${
          apk.available
            ? `<a class="btn-download" href="${escapeHtml(apk.downloadPath)}" download="${escapeHtml(apk.filename)}">Download ${escapeHtml(apk.filename)} (${escapeHtml(sizeLabel)})</a>`
            : `<div class="unavailable">APK not built yet on this host. Run <strong>gradle assembleDebug</strong> on the host project, then reload this page.</div>`
        }

        <div class="section">
          <h2>Oppo, Realme, OnePlus &amp; Vivo Install Steps</h2>
          <ol>
            <li>Tap <strong>Download ${escapeHtml(apk.filename)}</strong> and wait until the full download finishes before opening it.</li>
            <li>When Android asks, tap <strong>Settings</strong> and turn on <strong>Allow from this source</strong> (<em>Install unknown apps</em>) for Chrome or My Files.</li>
            <li>If ColorOS, OxygenOS, or FuntouchOS shows a security scan or <strong>Google Play Protect</strong> warning, tap <strong>More details &rarr; Install anyway</strong>.</li>
            <li>Open <strong>DroidCast</strong> and enter or scan the receiver address below.</li>
          </ol>
        </div>

        <div class="section">
          <h2>Signaling Address</h2>
          <div class="ws-row">
            <code id="ws-url">${safeWsUrl}</code>
            <button class="btn-copy" type="button" onclick="copyWs()">Copy</button>
          </div>
          <a class="footer-link" href="/">Open Web Receiver &rarr;</a>
        </div>
      </section>
    </main>
    <script>
      function copyWs() {
        var el = document.getElementById('ws-url');
        var btn = document.querySelector('.btn-copy');
        if (!el || !navigator.clipboard) return;
        navigator.clipboard.writeText(el.textContent || '').then(function () {
          if (btn) {
            btn.textContent = 'Copied';
            setTimeout(function () { btn.textContent = 'Copy'; }, 1800);
          }
        }).catch(function () {});
      }
    </script>
  </body>
</html>`;
}
