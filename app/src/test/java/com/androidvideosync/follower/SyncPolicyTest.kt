package com.androidvideosync.follower

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun noSeekWithinTolerance() {
        val decision = SyncPolicy.evaluate(
            packet = SyncPacket(
                playbackMs = 10_000L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
            nowElapsedRealtimeMs = 1_040L,
            localPlaybackMs = 12_100L,
            durationMs = null,
        )

        assertThat(decision.desiredLocalPlaybackMs).isEqualTo(12_040L)
        assertThat(decision.seekTargetMs).isNull()
    }

    @Test
    fun seeksToMasterPlusBufferWhenOutOfTolerance() {
        val decision = SyncPolicy.evaluate(
            packet = SyncPacket(
                playbackMs = 10_000L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
            nowElapsedRealtimeMs = 1_500L,
            localPlaybackMs = 8_000L,
            durationMs = null,
        )

        assertThat(decision.estimatedMasterPlaybackMs).isEqualTo(10_500L)
        assertThat(decision.desiredLocalPlaybackMs).isEqualTo(12_500L)
        assertThat(decision.seekTargetMs).isEqualTo(12_500L)
    }

    @Test
    fun extrapolatesMasterClockBetweenPackets() {
        val estimated = SyncPolicy.estimateMasterPlaybackMs(
            packet = SyncPacket(
                playbackMs = 7_000L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
            nowElapsedRealtimeMs = 1_750L,
        )

        assertThat(estimated).isEqualTo(7_750L)
    }

    @Test
    fun wrapsSeekTargetWhenDurationIsKnown() {
        val target = SyncPolicy.computeDesiredLocalPlaybackMs(
            estimatedMasterPlaybackMs = 9_500L,
            durationMs = 10_000L,
        )

        assertThat(target).isEqualTo(1_500L)
    }

    @Test
    fun loopBoundaryDriftUsesShortestDistance() {
        val drift = SyncPolicy.computeDriftMs(
            masterPlaybackMs = 100L,
            localPlaybackMs = 9_900L,
            durationMs = 10_000L,
        )

        assertThat(drift).isEqualTo(200L)
    }

    @Test
    fun noSeekWhenLocalMatchesBufferedTarget() {
        val decision = SyncPolicy.evaluate(
            packet = SyncPacket(
                playbackMs = 10_000L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
            nowElapsedRealtimeMs = 1_500L,
            localPlaybackMs = 12_500L,
            durationMs = null,
        )

        assertThat(decision.desiredLocalPlaybackMs).isEqualTo(12_500L)
        assertThat(decision.seekTargetMs).isNull()
        assertThat(decision.driftMs).isEqualTo(0L)
    }

    @Test
    fun packetTimeoutDisablesSeeking() {
        val decision = SyncPolicy.evaluate(
            packet = SyncPacket(
                playbackMs = 10_000L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
            nowElapsedRealtimeMs = 7_000L,
            localPlaybackMs = 0L,
            durationMs = 10_000L,
        )

        assertThat(decision.seekTargetMs).isNull()
        assertThat(decision.estimatedMasterPlaybackMs).isNull()
        assertThat(decision.desiredLocalPlaybackMs).isNull()
    }

    @Test
    fun medianDriftUsesMiddleOfRecentSamples() {
        val median = SyncPolicy.medianDriftMs(listOf(900L, 350L, 400L, 1_200L, 375L))

        assertThat(median).isEqualTo(400L)
    }

    @Test
    fun recentCorrectionBlocksAnotherResync() {
        val shouldResync = SyncPolicy.shouldResync(
            medianDriftMs = 450L,
            estimatedMasterPlaybackMs = 15_000L,
            localPlaybackMs = 14_000L,
            nowElapsedRealtimeMs = 20_000L,
            lastCorrectionElapsedRealtimeMs = 15_500L,
        )

        assertThat(shouldResync).isFalse()
    }
}
