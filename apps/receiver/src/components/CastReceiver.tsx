import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertCircle, Download, HelpCircle, Settings, X } from 'lucide-react';
import { useFullscreen } from '../hooks/useFullscreen';
import { useSignaling } from '../hooks/useSignaling';
import { useWakeLock } from '../hooks/useWakeLock';
import { useWebRTC } from '../hooks/useWebRTC';
import { ConnectionStatus } from './ConnectionStatus';
import { DeviceCard } from './DeviceCard';
import { PairingDialog } from './PairingDialog';
import { QrConnectCard } from './QrConnectCard';
import { SettingsPanel } from './SettingsPanel';
import { VideoPlayer } from './VideoPlayer';
import type { SignalingMessage } from '../lib/protocol';

interface CastReceiverProps {
  onOpenInstall?: () => void;
}

export function CastReceiver({ onOpenInstall }: CastReceiverProps = {}) {
  const stageRef = useRef<HTMLDivElement | null>(null);
  const { isFullscreen, toggleFullscreen } = useFullscreen(stageRef);

  const [panelOpen, setPanelOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [webRtcError, setWebRtcError] = useState<string | null>(null);

  const webRtcForwarderRef = useRef<(msg: SignalingMessage) => void>(() => {});
  const signaling = useSignaling((msg) => {
    webRtcForwarderRef.current(msg);
  });

  const webrtc = useWebRTC({
    sendSignalingMessage: signaling.sendMessage,
    onLog: signaling.appendLog,
    onWebRtcError: (_code, message) => {
      setWebRtcError(message);
    },
  });

  useEffect(() => {
    webRtcForwarderRef.current = (msg) => {
      void webrtc.handleSignalingMessage(msg);
    };
  }, [webrtc]);

  const isAuthorizedSession = Boolean(signaling.session?.authorized);
  const isWaitingForPairing = Boolean(signaling.session && !signaling.session.authorized);

  useWakeLock(isAuthorizedSession);

  const handleStopCasting = useCallback(() => {
    setWebRtcError(null);
    signaling.cancelSession();
    webrtc.resetPeer();
  }, [signaling, webrtc]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (
        (e.key === 'f' || e.key === 'F') &&
        isAuthorizedSession &&
        document.activeElement?.tagName !== 'INPUT'
      ) {
        toggleFullscreen();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isAuthorizedSession, toggleFullscreen]);

  const activeErrorMessage = webRtcError || signaling.error?.message || null;

  return (
    <div className="relative flex min-h-dvh w-full flex-col bg-[#090B10] text-slate-100">
      {isAuthorizedSession && signaling.session ? (
        <div className="relative h-dvh w-full overflow-hidden bg-black">
          <VideoPlayer
            stream={webrtc.remoteStream}
            senderName={signaling.session.senderName}
            quality={signaling.session.quality}
            playbackMode={webrtc.playbackMode}
            stats={webrtc.stats}
            castInfo={signaling.castInfo}
            isFullscreen={isFullscreen}
            showDetails={panelOpen}
            containerRef={stageRef}
            onQualityChange={signaling.changeQuality}
            onPlaybackModeChange={webrtc.setPlaybackMode}
            onToggleFullscreen={toggleFullscreen}
            onToggleDetails={() => setPanelOpen((v) => !v)}
            onStopCasting={handleStopCasting}
          />

          {signaling.connectionState === 'reconnecting' && (
            <div className="pointer-events-none absolute top-4 left-1/2 z-30 -translate-x-1/2 rounded-lg border border-amber-500/40 bg-[#090B10]/95 px-4 py-2 text-xs font-medium text-amber-300 shadow-lg">
              Connection lost… Reconnecting…
            </div>
          )}

          {signaling.justReconnected && (
            <div className="pointer-events-none absolute top-4 left-1/2 z-30 -translate-x-1/2 rounded-lg border border-emerald-500/40 bg-[#090B10]/95 px-4 py-2 text-xs font-medium text-emerald-300 shadow-lg">
              ● Reconnected
            </div>
          )}
        </div>
      ) : (
        <main className="flex flex-1 flex-col items-center justify-center px-6 py-10">
          <div className="w-full max-w-lg space-y-8">
            <header className="flex items-start justify-between gap-4">
              <div>
                <h1 className="text-2xl font-semibold tracking-tight text-slate-100">DroidCast</h1>
                <p className="mt-1 text-sm text-slate-400">
                  {signaling.receiverName} · Local WebRTC Receiver
                </p>
              </div>
              <button
                type="button"
                onClick={() => setPanelOpen(true)}
                aria-label="Receiver settings"
                className="rounded-lg border border-slate-800 bg-[#10141D] p-2.5 text-slate-300 transition hover:border-slate-700 hover:text-white"
              >
                <Settings className="h-4 w-4" />
              </button>
            </header>

            {signaling.connectionState === 'reconnecting' && (
              <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-200">
                Connection lost… Reconnecting to signaling server…
              </div>
            )}

            {activeErrorMessage && (
              <div className="flex items-start justify-between gap-3 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200">
                <div className="flex items-start gap-2.5">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
                  <div>
                    <div className="font-medium">
                      {signaling.error?.title || 'Unable to establish connection'}
                    </div>
                    <p className="mt-0.5 text-xs text-rose-200/80">{activeErrorMessage}</p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setWebRtcError(null);
                    signaling.retryConnection();
                  }}
                  className="shrink-0 rounded-md border border-rose-400/30 px-2.5 py-1 text-xs font-medium text-rose-100 hover:bg-rose-500/20"
                >
                  Retry
                </button>
              </div>
            )}

            {signaling.senders.length > 0 ? (
              <section className="space-y-3">
                <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                  Available Android Devices
                </div>
                <div className="space-y-2.5">
                  {signaling.senders.map((device) => (
                    <DeviceCard
                      key={device.deviceId}
                      device={device}
                      onConnect={(id) => signaling.requestConnectToSender(id)}
                      disabled={signaling.connectionState !== 'connected'}
                    />
                  ))}
                </div>
              </section>
            ) : (
              <section className="space-y-4 rounded-xl border border-slate-800/90 bg-[#10141D] px-6 py-7">
                <div>
                  <h2 className="text-base font-medium text-slate-100">No device connected.</h2>
                  <p className="mt-1.5 text-sm text-slate-400">
                    Waiting for an Android device…
                  </p>
                </div>

                <QrConnectCard signalingUrl={signaling.signalingUrl} onOpenInstall={onOpenInstall} />

                <div className="flex flex-wrap items-center gap-3">
                  <button
                    type="button"
                    onClick={() => setGuideOpen(true)}
                    className="inline-flex items-center gap-2 rounded-lg border border-slate-700 bg-[#161C28] px-3.5 py-2 text-xs font-medium text-slate-200 transition hover:border-slate-600 hover:text-white"
                  >
                    <HelpCircle className="h-3.5 w-3.5 text-teal-400" />
                    <span>How to Connect</span>
                  </button>
                  {onOpenInstall && (
                    <button
                      type="button"
                      onClick={onOpenInstall}
                      className="inline-flex items-center gap-2 rounded-lg border border-slate-700 bg-[#161C28] px-3.5 py-2 text-xs font-medium text-slate-200 transition hover:border-slate-600 hover:text-white"
                    >
                      <Download className="h-3.5 w-3.5 text-teal-400" />
                      <span>Install Android APK</span>
                    </button>
                  )}
                </div>
              </section>
            )}

            <ConnectionStatus
              connectionState={signaling.connectionState}
              justReconnected={signaling.justReconnected}
              onRetry={signaling.retryConnection}
            />
          </div>
        </main>
      )}

      {isWaitingForPairing && signaling.session && (
        <PairingDialog
          senderName={signaling.session.senderName}
          errorMessage={signaling.pairingError}
          onSubmit={signaling.submitPairingCode}
          onCancel={handleStopCasting}
        />
      )}

      {guideOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 px-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="how-to-connect-title"
        >
          <div className="w-full max-w-md rounded-xl border border-slate-800 bg-[#10141D] p-6 shadow-2xl">
            <div className="flex items-center justify-between">
              <h2 id="how-to-connect-title" className="text-base font-semibold text-slate-100">
                How to Connect
              </h2>
              <button
                type="button"
                onClick={() => setGuideOpen(false)}
                aria-label="Close instructions"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <ol className="mt-4 space-y-2.5 text-sm text-slate-300 list-decimal list-inside">
              <li>Connect your phone and receiver host to the same Wi-Fi network.</li>
              <li>
                Need the app? Switch the card above to <span className="font-medium text-slate-100">Get APK</span>{' '}
                and scan to download directly in your phone browser (enable{' '}
                <span className="font-medium text-slate-100">Install unknown apps</span> on Oppo, Realme, OnePlus, or Vivo).
              </li>
              <li>Open DroidCast on your Android phone.</li>
              <li>
                Select <span className="font-medium text-slate-100">{signaling.receiverName}</span> or
                enter this signaling address:{' '}
                <code className="rounded bg-[#090B10] px-1.5 py-0.5 font-mono text-xs text-teal-300">
                  {signaling.signalingUrl}
                </code>
              </li>
              <li>Enter the 6-digit pairing code shown on your Android device.</li>
              <li>Approve screen capture on Android to stream in real time.</li>
            </ol>
          </div>
        </div>
      )}

      <SettingsPanel
        open={panelOpen}
        signalingUrl={signaling.signalingUrl}
        receiverName={signaling.receiverName}
        stats={webrtc.stats}
        castInfo={signaling.castInfo}
        logs={signaling.logs}
        isCasting={isAuthorizedSession}
        onSaveSettings={signaling.updateSettings}
        onClose={() => setPanelOpen(false)}
      />
    </div>
  );
}
