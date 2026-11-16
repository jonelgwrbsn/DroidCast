import type { AdaptiveStreamState, NetworkQualityLevel } from './protocol';

export type PlaybackMode = 'smooth' | 'low-latency';

export function computeIntervalPacketLossPercent(
  deltaPacketsReceived: number,
  deltaPacketsLost: number
): number {
  const safeReceived = Math.max(0, deltaPacketsReceived);
  const safeLost = Math.max(0, deltaPacketsLost);
  const total = safeReceived + safeLost;
  if (total <= 0) return 0;
  return Number(((safeLost / total) * 100).toFixed(2));
}

export function computeAdaptivePlayoutDelaySec(params: {
  mode: PlaybackMode;
  packetLossPercent: number;
  jitterMs: number;
  currentDelaySec: number;
}): number {
  const baseSec = params.mode === 'smooth' ? 0.04 : 0.015;
  const maxSec = params.mode === 'smooth' ? 0.1 : 0.035;

  let desired = baseSec;
  if (params.packetLossPercent >= 3.0 || params.jitterMs >= 30) {
    desired = maxSec;
  } else if (params.packetLossPercent >= 1.0 || params.jitterMs >= 16) {
    desired = params.mode === 'smooth' ? 0.07 : 0.025;
  }

  // Ramp up quickly to absorb jitter bursts; decay smoothly back to baseline so latency never lingers
  if (desired > params.currentDelaySec) {
    return Number(desired.toFixed(3));
  }
  if (desired < params.currentDelaySec) {
    const stepDown = params.mode === 'low-latency' ? 0.02 : 0.025;
    const steppedDown = Math.max(desired, params.currentDelaySec - stepDown);
    return Number(steppedDown.toFixed(3));
  }
  return Number(params.currentDelaySec.toFixed(3));
}

const VALID_QUALITY_LEVELS = new Set<NetworkQualityLevel>(['excellent', 'good', 'fair', 'poor']);

export function parseAdaptiveStreamState(raw: string): AdaptiveStreamState | null {
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    if (!parsed || parsed.type !== 'adaptive_state') return null;
    if (
      typeof parsed.tierId !== 'string' ||
      typeof parsed.tierLabel !== 'string' ||
      typeof parsed.targetBitrateKbps !== 'number' ||
      typeof parsed.width !== 'number' ||
      typeof parsed.height !== 'number' ||
      typeof parsed.fps !== 'number'
    ) {
      return null;
    }
    const nq = typeof parsed.networkQuality === 'string' && VALID_QUALITY_LEVELS.has(parsed.networkQuality as NetworkQualityLevel)
      ? (parsed.networkQuality as NetworkQualityLevel)
      : 'good';

    const mode =
      parsed.mode === 'adaptive' ||
      parsed.mode === 'native' ||
      parsed.mode === '720p30' ||
      parsed.mode === '1080p30' ||
      parsed.mode === '1080p60' ||
      parsed.mode === '1440p60'
        ? parsed.mode
        : 'adaptive';

    return {
      type: 'adaptive_state',
      mode,
      tierId: parsed.tierId,
      tierLabel: parsed.tierLabel,
      targetBitrateKbps: parsed.targetBitrateKbps,
      width: parsed.width,
      height: parsed.height,
      fps: parsed.fps,
      networkQuality: nq,
      reason: typeof parsed.reason === 'string' ? parsed.reason : '',
    };
  } catch {
    return null;
  }
}
