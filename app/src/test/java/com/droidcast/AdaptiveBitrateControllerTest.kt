package com.droidcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateControllerTest {

    @Test
    fun `bandwidth drop scales bitrate immediately and steps down resolution tier after sustained constraint`() {
        val controller = AdaptiveBitrateController(
            stepDownCooldownMs = 4_000L,
            stepUpCooldownMs = 10_000L,
            requiredPoorScoreForStepDown = 3,
            requiredCleanIntervalsForStepUp = 8,
        )
        val initial = controller.reset(QualityPreset.ADAPTIVE, nowMs = 0L)
        assertEquals(AdaptiveTier.NATIVE_60, initial.tier)

        // Tick 1 at t=2000ms: cooldown not reached yet, bitrate drops continuously
        val d1 = controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 2_000L,
                availableOutgoingBitrateBps = 3_800_000.0,
                qualityLimitationReason = "bandwidth",
            )
        )
        assertEquals(AdaptiveTier.NATIVE_60, d1.tier)
        assertTrue(d1.targetMaxBitrateBps < initial.targetMaxBitrateBps)
        assertTrue(d1.bitrateChanged)

        // Tick 2 at t=3000ms
        controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 3_000L,
                availableOutgoingBitrateBps = 3_800_000.0,
                qualityLimitationReason = "bandwidth",
            )
        )

        // Tick 3 at t=4500ms: poorScore >= 3 and cooldown elapsed -> steps down to 1440p60
        val d3 = controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 4_500L,
                availableOutgoingBitrateBps = 3_800_000.0,
                qualityLimitationReason = "bandwidth",
            )
        )
        assertEquals(AdaptiveTier.P1440_60, d3.tier)
        assertTrue(d3.resolutionChanged)
        assertEquals(NetworkQualityLevel.FAIR, d3.networkQuality)
    }

    @Test
    fun `severe packet loss and receiver stall trigger rapid step down down to 540p and 360p`() {
        val controller = AdaptiveBitrateController()

        // Simulate sustained severe congestion stepping down through all 6 transitions
        var lastDecision = controller.reset(QualityPreset.ADAPTIVE, nowMs = 0L)
        for (sec in 1..32) {
            lastDecision = controller.evaluate(
                NetworkMetricsSample(
                    timestampMs = sec * 1_000L,
                    availableOutgoingBitrateBps = 550_000.0,
                    packetLossRatio = 0.08,
                    rttMs = 320.0,
                    qualityLimitationReason = "bandwidth",
                    receiverStalled = true,
                )
            )
        }

        assertEquals(AdaptiveTier.P360_24, lastDecision.tier)
        assertEquals(NetworkQualityLevel.POOR, lastDecision.networkQuality)
        assertTrue(lastDecision.targetMaxBitrateBps in AdaptiveTier.P360_24.minBitrateBps..AdaptiveTier.P360_24.maxBitrateBps)
    }

    @Test
    fun `clean network recovers bitrate and steps up tier only after hysteresis and cooldown`() {
        val controller = AdaptiveBitrateController(
            stepDownCooldownMs = 4_000L,
            stepUpCooldownMs = 10_000L,
            requiredPoorScoreForStepDown = 3,
            requiredCleanIntervalsForStepUp = 6,
        )
        controller.reset(QualityPreset.ADAPTIVE, nowMs = 0L)

        // Trigger one step down at t=5000ms -> P1440_60
        for (t in listOf(4_000L, 4_500L, 5_000L)) {
            controller.evaluate(
                NetworkMetricsSample(
                    timestampMs = t,
                    availableOutgoingBitrateBps = 3_800_000.0,
                    packetLossRatio = 0.04,
                    qualityLimitationReason = "bandwidth",
                )
            )
        }
        assertEquals(AdaptiveTier.P1440_60, controller.currentTier())

        // Clean intervals before 10s step-up cooldown expires (t=6s..12s) must NOT step up yet
        var midDecision: AdaptiveDecision? = null
        for (sec in 6..12) {
            midDecision = controller.evaluate(
                NetworkMetricsSample(
                    timestampMs = sec * 1_000L,
                    availableOutgoingBitrateBps = 16_000_000.0,
                    packetLossRatio = 0.0,
                    rttMs = 15.0,
                    qualityLimitationReason = "none",
                )
            )
        }
        assertEquals(AdaptiveTier.P1440_60, midDecision?.tier)

        // Once stepUpCooldownMs (10s since t=5000 -> t=15000+) is met, recovery steps up to NATIVE_60
        var recoveredDecision = midDecision!!
        var steppedUpToNative = false
        for (sec in 13..18) {
            recoveredDecision = controller.evaluate(
                NetworkMetricsSample(
                    timestampMs = sec * 1_000L,
                    availableOutgoingBitrateBps = 16_000_000.0,
                    packetLossRatio = 0.0,
                    rttMs = 15.0,
                    qualityLimitationReason = "none",
                )
            )
            if (recoveredDecision.resolutionChanged && recoveredDecision.tier == AdaptiveTier.NATIVE_60) {
                steppedUpToNative = true
            }
        }
        assertEquals(AdaptiveTier.NATIVE_60, recoveredDecision.tier)
        assertTrue(steppedUpToNative)
        assertEquals(NetworkQualityLevel.EXCELLENT, recoveredDecision.networkQuality)
    }

    @Test
    fun `fixed preset locks resolution and fps even under packet loss`() {
        val controller = AdaptiveBitrateController()
        val init = controller.reset(QualityPreset.P720_30, nowMs = 0L)
        assertEquals(AdaptiveTier.P720_30, init.tier)

        val decision = controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 10_000L,
                availableOutgoingBitrateBps = 500_000.0,
                packetLossRatio = 0.09,
                qualityLimitationReason = "bandwidth",
            )
        )
        assertEquals(AdaptiveTier.P720_30, decision.tier)
        assertFalse(decision.resolutionChanged)
        assertEquals(NetworkQualityLevel.POOR, decision.networkQuality)

        val nativeInit = controller.reset(QualityPreset.NATIVE, nowMs = 0L)
        assertEquals(AdaptiveTier.NATIVE_60, nativeInit.tier)
        val nativeDecision = controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 15_000L,
                availableOutgoingBitrateBps = 2_000_000.0,
                packetLossRatio = 0.05,
                qualityLimitationReason = "bandwidth",
            )
        )
        assertEquals(AdaptiveTier.NATIVE_60, nativeDecision.tier)
        assertFalse(nativeDecision.resolutionChanged)
    }

    @Test
    fun `fixed high resolution preset keeps its increased bitrate target`() {
        val controller = AdaptiveBitrateController()
        val initial = controller.reset(QualityPreset.P1440_60, nowMs = 0L)

        assertEquals(20_000_000, initial.targetMaxBitrateBps)
        assertEquals(4_000_000, initial.targetMinBitrateBps)

        val decision = controller.evaluate(
            NetworkMetricsSample(
                timestampMs = 10_000L,
                availableOutgoingBitrateBps = 30_000_000.0,
                packetLossRatio = 0.0,
                qualityLimitationReason = "none",
            )
        )

        assertEquals(AdaptiveTier.P1440_60, decision.tier)
        assertEquals(20_000_000, decision.targetMaxBitrateBps)
    }
}
