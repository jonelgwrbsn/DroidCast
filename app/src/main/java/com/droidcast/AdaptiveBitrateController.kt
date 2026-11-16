package com.droidcast

import kotlin.math.abs
import kotlin.math.roundToInt

enum class NetworkQualityLevel(val wireValue: String, val label: String) {
    EXCELLENT("excellent", "Optimal"),
    GOOD("good", "Good"),
    FAIR("fair", "Fair"),
    POOR("poor", "Constrained");
}

enum class AdaptiveTier(
    val id: String,
    val label: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val fps: Int,
    val minBitrateBps: Int,
    val defaultBitrateBps: Int,
    val maxBitrateBps: Int,
    val stepDownThresholdBps: Int,
    val stepUpHeadroomBps: Int,
) {
    P360_24(
        id = "360p24",
        label = "360p · 24 FPS",
        maxWidth = 640,
        maxHeight = 360,
        fps = 24,
        minBitrateBps = 250_000,
        defaultBitrateBps = 600_000,
        maxBitrateBps = 850_000,
        stepDownThresholdBps = 0,
        stepUpHeadroomBps = 0,
    ),
    P540_30(
        id = "540p30",
        label = "540p · 30 FPS",
        maxWidth = 960,
        maxHeight = 540,
        fps = 30,
        minBitrateBps = 500_000,
        defaultBitrateBps = 1_200_000,
        maxBitrateBps = 1_600_000,
        stepDownThresholdBps = 600_000,
        stepUpHeadroomBps = 950_000,
    ),
    P720_30(
        id = "720p30",
        label = "720p · 30 FPS",
        maxWidth = 1280,
        maxHeight = 720,
        fps = 30,
        minBitrateBps = 1_500_000,
        defaultBitrateBps = 3_200_000,
        maxBitrateBps = 4_000_000,
        stepDownThresholdBps = 1_150_000,
        stepUpHeadroomBps = 1_800_000,
    ),
    P1080_30(
        id = "1080p30",
        label = "1080p · 30 FPS",
        maxWidth = 1920,
        maxHeight = 1080,
        fps = 30,
        minBitrateBps = 2_200_000,
        defaultBitrateBps = 7_000_000,
        maxBitrateBps = 10_000_000,
        stepDownThresholdBps = 1_800_000,
        stepUpHeadroomBps = 4_500_000,
    ),
    P1080_60(
        id = "1080p60",
        label = "1080p · 60 FPS",
        maxWidth = 1920,
        maxHeight = 1080,
        fps = 60,
        minBitrateBps = 3_200_000,
        defaultBitrateBps = 10_000_000,
        maxBitrateBps = 16_000_000,
        stepDownThresholdBps = 2_800_000,
        stepUpHeadroomBps = 6_500_000,
    ),
    P1440_60(
        id = "1440p60",
        label = "1440p · 60 FPS",
        maxWidth = 2560,
        maxHeight = 1440,
        fps = 60,
        minBitrateBps = 4_000_000,
        defaultBitrateBps = 14_000_000,
        maxBitrateBps = 20_000_000,
        stepDownThresholdBps = 3_800_000,
        stepUpHeadroomBps = 9_000_000,
    ),
    NATIVE_60(
        id = "native60",
        label = "Native · 60 FPS",
        maxWidth = 3840,
        maxHeight = 2160,
        fps = 60,
        minBitrateBps = 4_500_000,
        defaultBitrateBps = 16_000_000,
        maxBitrateBps = 24_000_000,
        stepDownThresholdBps = 4_200_000,
        stepUpHeadroomBps = 12_000_000,
    );

    fun stepDown(): AdaptiveTier? = when (this) {
        NATIVE_60 -> P1440_60
        P1440_60 -> P1080_60
        P1080_60 -> P1080_30
        P1080_30 -> P720_30
        P720_30 -> P540_30
        P540_30 -> P360_24
        P360_24 -> null
    }

    fun stepUp(): AdaptiveTier? = when (this) {
        P360_24 -> P540_30
        P540_30 -> P720_30
        P720_30 -> P1080_30
        P1080_30 -> P1080_60
        P1080_60 -> P1440_60
        P1440_60 -> NATIVE_60
        NATIVE_60 -> null
    }

    companion object {
        fun fromPreset(preset: QualityPreset): AdaptiveTier = when (preset) {
            QualityPreset.ADAPTIVE -> NATIVE_60
            QualityPreset.NATIVE -> NATIVE_60
            QualityPreset.P720_30 -> P720_30
            QualityPreset.P1080_30 -> P1080_30
            QualityPreset.P1080_60 -> P1080_60
            QualityPreset.P1440_60 -> P1440_60
        }
    }
}

data class NetworkMetricsSample(
    val timestampMs: Long,
    val availableOutgoingBitrateBps: Double = 0.0,
    val actualSendBitrateBps: Double = 0.0,
    val packetLossRatio: Double = 0.0,
    val rttMs: Double? = null,
    val receiverJitterMs: Double = 0.0,
    val qualityLimitationReason: String = "none",
    val retransmitRatio: Double = 0.0,
    val receiverStalled: Boolean = false,
)

data class AdaptiveDecision(
    val tier: AdaptiveTier,
    val targetMaxBitrateBps: Int,
    val targetMinBitrateBps: Int,
    val targetFps: Int,
    val resolutionChanged: Boolean,
    val bitrateChanged: Boolean,
    val networkQuality: NetworkQualityLevel,
    val reason: String,
)

class AdaptiveBitrateController(
    private val stepDownCooldownMs: Long = 5_000L,
    private val stepUpCooldownMs: Long = 8_000L,
    private val requiredPoorScoreForStepDown: Int = 4,
    private val requiredCleanIntervalsForStepUp: Int = 6,
) {
    private var requestedPreset: QualityPreset = QualityPreset.ADAPTIVE
    private var currentTier: AdaptiveTier = AdaptiveTier.NATIVE_60
    private var currentMaxBitrateBps: Int = currentTier.defaultBitrateBps
    private var lastAppliedBitrateBps: Int = currentMaxBitrateBps

    private var emaBandwidthBps: Double = 0.0
    private var emaLossRatio: Double = 0.0
    private var poorScore: Int = 0
    private var cleanIntervals: Int = 0
    private var lastTierChangeMs: Long = 0L

    fun currentTier(): AdaptiveTier = currentTier
    fun currentTargetBitrateBps(): Int = currentMaxBitrateBps

    fun reset(preset: QualityPreset, nowMs: Long = 0L): AdaptiveDecision {
        requestedPreset = preset
        currentTier = AdaptiveTier.fromPreset(preset)
        currentMaxBitrateBps = if (preset == QualityPreset.ADAPTIVE) {
            currentTier.defaultBitrateBps
        } else {
            currentTier.maxBitrateBps
        }
        lastAppliedBitrateBps = currentMaxBitrateBps
        emaBandwidthBps = 0.0
        emaLossRatio = 0.0
        poorScore = 0
        cleanIntervals = 0
        lastTierChangeMs = nowMs

        return AdaptiveDecision(
            tier = currentTier,
            targetMaxBitrateBps = currentMaxBitrateBps,
            targetMinBitrateBps = currentTier.minBitrateBps,
            targetFps = currentTier.fps,
            resolutionChanged = true,
            bitrateChanged = true,
            networkQuality = NetworkQualityLevel.EXCELLENT,
            reason = if (preset == QualityPreset.ADAPTIVE) "Adaptive baseline" else "Locked to ${preset.label}",
        )
    }

    fun setPreset(preset: QualityPreset, nowMs: Long = 0L): AdaptiveDecision {
        val prevTier = currentTier
        val prevBitrate = lastAppliedBitrateBps
        requestedPreset = preset
        if (preset != QualityPreset.ADAPTIVE) {
            currentTier = AdaptiveTier.fromPreset(preset)
            currentMaxBitrateBps = currentTier.maxBitrateBps
            poorScore = 0
            cleanIntervals = 0
            lastTierChangeMs = nowMs
        } else {
            // Keep current tier as starting point if transitioning into adaptive mid-stream
            poorScore = 0
            cleanIntervals = 0
            lastTierChangeMs = nowMs
            currentMaxBitrateBps = currentMaxBitrateBps.coerceIn(currentTier.minBitrateBps, currentTier.maxBitrateBps)
        }
        val resolutionChanged = currentTier != prevTier
        val bitrateChanged = resolutionChanged || hasSignificantBitrateDelta(prevBitrate, currentMaxBitrateBps)
        if (bitrateChanged) {
            lastAppliedBitrateBps = currentMaxBitrateBps
        }
        return AdaptiveDecision(
            tier = currentTier,
            targetMaxBitrateBps = currentMaxBitrateBps,
            targetMinBitrateBps = currentTier.minBitrateBps,
            targetFps = currentTier.fps,
            resolutionChanged = resolutionChanged,
            bitrateChanged = bitrateChanged,
            networkQuality = NetworkQualityLevel.GOOD,
            reason = if (preset == QualityPreset.ADAPTIVE) "Adaptive enabled" else "Locked to ${preset.label}",
        )
    }

    fun evaluate(sample: NetworkMetricsSample): AdaptiveDecision {
        val clampedLoss = sample.packetLossRatio.coerceIn(0.0, 1.0)
        emaLossRatio = if (emaLossRatio == 0.0) {
            clampedLoss
        } else {
            (0.45 * clampedLoss) + (0.55 * emaLossRatio)
        }

        if (sample.availableOutgoingBitrateBps > 0.0) {
            emaBandwidthBps = if (emaBandwidthBps <= 0.0) {
                sample.availableOutgoingBitrateBps
            } else {
                // React faster to drops than spikes to protect playback smoothness
                val alpha = if (sample.availableOutgoingBitrateBps < emaBandwidthBps) 0.50 else 0.25
                (alpha * sample.availableOutgoingBitrateBps) + ((1.0 - alpha) * emaBandwidthBps)
            }
        }

        val rttMs = sample.rttMs
        val effectiveLoss = maxOf(clampedLoss, emaLossRatio)
        val highLoss = effectiveLoss >= 0.03 || sample.retransmitRatio >= 0.06
        val severeLoss = clampedLoss >= 0.07 || emaLossRatio >= 0.06 || sample.retransmitRatio >= 0.12
        val highRtt = (rttMs != null && rttMs >= 180.0) || sample.receiverJitterMs >= 60.0
        val severeRtt = (rttMs != null && rttMs >= 300.0) || sample.receiverJitterMs >= 100.0
        val bwLimited = sample.qualityLimitationReason == "bandwidth" ||
            (emaBandwidthBps > 0.0 && emaBandwidthBps < currentTier.stepDownThresholdBps)
        val cpuLimited = sample.qualityLimitationReason == "cpu"

        val networkQuality = when {
            severeLoss || severeRtt || sample.receiverStalled -> NetworkQualityLevel.POOR
            highLoss || highRtt || bwLimited || cpuLimited -> NetworkQualityLevel.FAIR
            effectiveLoss >= 0.01 || (rttMs != null && rttMs >= 90.0) -> NetworkQualityLevel.GOOD
            else -> NetworkQualityLevel.EXCELLENT
        }

        if (requestedPreset != QualityPreset.ADAPTIVE) {
            return AdaptiveDecision(
                tier = currentTier,
                targetMaxBitrateBps = currentTier.maxBitrateBps,
                targetMinBitrateBps = currentTier.minBitrateBps,
                targetFps = currentTier.fps,
                resolutionChanged = false,
                bitrateChanged = false,
                networkQuality = networkQuality,
                reason = "Fixed ${requestedPreset.label}",
            )
        }

        val prevTier = currentTier
        var reason = when (networkQuality) {
            NetworkQualityLevel.EXCELLENT -> "Optimal Wi-Fi"
            NetworkQualityLevel.GOOD -> "Stable Wi-Fi"
            NetworkQualityLevel.FAIR -> buildConstraintReason(effectiveLoss, rttMs, bwLimited, cpuLimited, sample.receiverStalled)
            NetworkQualityLevel.POOR -> buildConstraintReason(effectiveLoss, rttMs, bwLimited, cpuLimited, sample.receiverStalled)
        }

        val constrained = highLoss || highRtt || bwLimited || cpuLimited || sample.receiverStalled

        if (constrained) {
            val weight = if (severeLoss || severeRtt || sample.receiverStalled) 2 else 1
            poorScore += weight
            cleanIntervals = 0

            // Continuous intra-tier bitrate reduction before/alongside resolution drop
            var nextBitrate = currentMaxBitrateBps.toDouble()
            if (emaBandwidthBps > 0.0) {
                nextBitrate = minOf(nextBitrate, emaBandwidthBps * 0.82)
            }
            if (severeLoss || sample.receiverStalled) {
                nextBitrate *= 0.72
            } else if (highLoss || bwLimited || highRtt) {
                nextBitrate *= 0.86
            }
            currentMaxBitrateBps = nextBitrate.roundToInt()
                .coerceIn(currentTier.minBitrateBps, currentTier.maxBitrateBps)

            val canStepDown = (sample.timestampMs - lastTierChangeMs) >= stepDownCooldownMs
            if (poorScore >= requiredPoorScoreForStepDown && canStepDown) {
                val lowerTier = currentTier.stepDown()
                if (lowerTier != null) {
                    currentTier = lowerTier
                    poorScore = 0
                    cleanIntervals = 0
                    lastTierChangeMs = sample.timestampMs

                    val safeCap = if (emaBandwidthBps > 0.0) {
                        minOf(lowerTier.defaultBitrateBps.toDouble(), emaBandwidthBps * 0.80)
                    } else {
                        lowerTier.defaultBitrateBps.toDouble()
                    }
                    currentMaxBitrateBps = safeCap.roundToInt()
                        .coerceIn(lowerTier.minBitrateBps, lowerTier.maxBitrateBps)
                    reason = "Stepped down to ${lowerTier.label} (${buildConstraintReason(effectiveLoss, rttMs, bwLimited, cpuLimited, sample.receiverStalled)})"
                }
            }
        } else {
            val clean = effectiveLoss < 0.008 &&
                (rttMs == null || rttMs < 110.0) &&
                sample.qualityLimitationReason == "none" &&
                !sample.receiverStalled

            if (clean) {
                cleanIntervals += 1
                poorScore = 0

                // Smoothly ramp bitrate up within current tier after 2 clean samples
                if (cleanIntervals >= 2) {
                    val bweCeiling = if (emaBandwidthBps > 0.0) {
                        (emaBandwidthBps * 0.85).roundToInt()
                    } else {
                        currentTier.maxBitrateBps
                    }
                    val desiredCeiling = minOf(currentTier.maxBitrateBps, bweCeiling)
                        .coerceAtLeast(currentTier.minBitrateBps)
                    if (currentMaxBitrateBps < desiredCeiling) {
                        val steppedUp = (currentMaxBitrateBps * 1.12).roundToInt()
                        currentMaxBitrateBps = minOf(desiredCeiling, steppedUp)
                    }
                }

                // Step up resolution/FPS tier only after sustained stability and upgrade cooldown
                val canStepUp = (sample.timestampMs - lastTierChangeMs) >= stepUpCooldownMs
                if (cleanIntervals >= requiredCleanIntervalsForStepUp && canStepUp) {
                    val higherTier = currentTier.stepUp()
                    if (higherTier != null &&
                        (emaBandwidthBps <= 0.0 || emaBandwidthBps >= higherTier.stepUpHeadroomBps)
                    ) {
                        currentTier = higherTier
                        cleanIntervals = 0
                        poorScore = 0
                        lastTierChangeMs = sample.timestampMs
                        val initialCap = if (emaBandwidthBps > 0.0) {
                            minOf(higherTier.defaultBitrateBps.toDouble(), emaBandwidthBps * 0.80)
                        } else {
                            higherTier.defaultBitrateBps.toDouble()
                        }
                        currentMaxBitrateBps = initialCap.roundToInt()
                            .coerceIn(higherTier.minBitrateBps, higherTier.maxBitrateBps)
                        reason = "Recovered up to ${higherTier.label}"
                    }
                }
            } else {
                // Borderline state: slowly decay counters without oscillating
                poorScore = (poorScore - 1).coerceAtLeast(0)
            }
        }

        val resolutionChanged = currentTier != prevTier
        val bitrateChanged = resolutionChanged || hasSignificantBitrateDelta(lastAppliedBitrateBps, currentMaxBitrateBps)
        if (bitrateChanged) {
            lastAppliedBitrateBps = currentMaxBitrateBps
        }

        return AdaptiveDecision(
            tier = currentTier,
            targetMaxBitrateBps = currentMaxBitrateBps,
            targetMinBitrateBps = currentTier.minBitrateBps,
            targetFps = currentTier.fps,
            resolutionChanged = resolutionChanged,
            bitrateChanged = bitrateChanged,
            networkQuality = networkQuality,
            reason = reason,
        )
    }

    private fun hasSignificantBitrateDelta(previousBps: Int, nextBps: Int): Boolean {
        if (previousBps <= 0) return true
        val diff = abs(nextBps - previousBps)
        return diff >= 150_000 || (diff.toDouble() / previousBps.toDouble()) >= 0.10
    }

    private fun buildConstraintReason(
        lossRatio: Double,
        rttMs: Double?,
        bwLimited: Boolean,
        cpuLimited: Boolean,
        receiverStalled: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (receiverStalled) parts.add("receiver stall")
        if (lossRatio >= 0.02) {
            val pct = String.format(java.util.Locale.US, "%.1f%%", lossRatio * 100.0)
            parts.add("packet loss $pct")
        }
        if (rttMs != null && rttMs >= 160.0) {
            parts.add("high RTT ${rttMs.roundToInt()}ms")
        }
        if (bwLimited) parts.add("bandwidth cap")
        if (cpuLimited) parts.add("encoder CPU")
        return if (parts.isEmpty()) "network jitter" else parts.joinToString(", ")
    }
}
