package com.androidvideosync.follower

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PacketParserTest {
    @Test
    fun parsesValidPacket() {
        val packet = PacketParser.parse("12.483%video.mp4", receivedElapsedRealtimeMs = 1_000L)

        assertThat(packet).isEqualTo(
            SyncPacket(
                playbackMs = 12_483L,
                filename = "video.mp4",
                receivedElapsedRealtimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun rejectsMalformedPacket() {
        val packet = PacketParser.parse("not-a-packet", receivedElapsedRealtimeMs = 1_000L)

        assertThat(packet).isNull()
    }

    @Test
    fun convertsDecimalSecondsToMilliseconds() {
        val packet = PacketParser.parse("1.2345%video.mp4", receivedElapsedRealtimeMs = 0L)

        assertThat(packet?.playbackMs).isEqualTo(1_235L)
    }
}
