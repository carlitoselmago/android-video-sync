package com.androidvideosync.follower

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun filenameChangeTriggersVideoReload() = runTest(mainDispatcherRule.dispatcher) {
        val player = FakePlaybackController()
        val packets = FakeSyncPacketSource()
        var now = 0L
        val manager = SyncManager(
            playerController = player,
            udpReceiver = packets,
            syncSettings = SyncSettings(selectedFilename = ""),
            clock = { now },
            workerDispatcher = mainDispatcherRule.dispatcher,
            mainDispatcher = mainDispatcherRule.dispatcher,
        )

        manager.handlePacket(SyncPacket(1_000L, "first.mp4", now))

        manager.handlePacket(SyncPacket(2_000L, "second.mp4", now))

        assertThat(player.loadRequests.map { it.filename }).containsExactly("first.mp4", "second.mp4").inOrder()
    }

    @Test
    fun missingFileKeepsCurrentPlaybackAndAvoidsReloadSpam() = runTest(mainDispatcherRule.dispatcher) {
        val player = FakePlaybackController(
            loadOutcomes = mutableMapOf(
                "good.mp4" to true,
                "missing.mp4" to false,
            ),
        )
        val packets = FakeSyncPacketSource()
        var now = 0L
        val manager = SyncManager(
            playerController = player,
            udpReceiver = packets,
            syncSettings = SyncSettings(selectedFilename = ""),
            clock = { now },
            workerDispatcher = mainDispatcherRule.dispatcher,
            mainDispatcher = mainDispatcherRule.dispatcher,
        )

        manager.handlePacket(SyncPacket(1_000L, "good.mp4", now))

        manager.handlePacket(SyncPacket(2_000L, "missing.mp4", now))

        now += 1_000L
        manager.handlePacket(SyncPacket(3_000L, "missing.mp4", now))

        assertThat(player.loadRequests.map { it.filename }).containsExactly("good.mp4", "missing.mp4").inOrder()
        assertThat(player.playCallCount).isEqualTo(1)
    }

    @Test
    fun driftPastToleranceTriggersSeek() = runTest(mainDispatcherRule.dispatcher) {
        val player = FakePlaybackController()
        val packets = FakeSyncPacketSource()
        var now = 0L
        val manager = SyncManager(
            playerController = player,
            udpReceiver = packets,
            syncSettings = SyncSettings(selectedFilename = ""),
            clock = { now },
            workerDispatcher = mainDispatcherRule.dispatcher,
            mainDispatcher = mainDispatcherRule.dispatcher,
        )

        manager.handlePacket(SyncPacket(12_000L, "sync.mp4", now))
        player.positionMs = 10_500L

        now += SyncPolicy.POLL_INTERVAL_MS
        manager.runSyncPass()

        assertThat(player.seekRequests).containsExactly(14_250L)
    }

    @Test
    fun correctionCooldownAvoidsImmediateRepeatedSeek() = runTest(mainDispatcherRule.dispatcher) {
        val player = FakePlaybackController()
        val packets = FakeSyncPacketSource()
        var now = 0L
        val manager = SyncManager(
            playerController = player,
            udpReceiver = packets,
            syncSettings = SyncSettings(selectedFilename = ""),
            clock = { now },
            workerDispatcher = mainDispatcherRule.dispatcher,
            mainDispatcher = mainDispatcherRule.dispatcher,
        )

        manager.handlePacket(SyncPacket(12_000L, "sync.mp4", now))
        player.positionMs = 10_500L

        now += SyncPolicy.POLL_INTERVAL_MS
        manager.runSyncPass()

        player.positionMs = 10_000L
        now += 1_000L
        manager.handlePacket(SyncPacket(13_000L, "sync.mp4", now))
        manager.runSyncPass()

        assertThat(player.seekRequests).containsExactly(14_250L)
    }
}

private class FakePlaybackController(
    val loadOutcomes: MutableMap<String, Boolean> = mutableMapOf(),
) : PlaybackController {
    val loadRequests = mutableListOf<LoadRequest>()
    val seekRequests = mutableListOf<Long>()
    var positionMs: Long = 0L
    var durationMs: Long? = null
    var playCallCount: Int = 0

    override suspend fun load(filename: String, startPositionMs: Long): LoadResult {
        loadRequests += LoadRequest(filename, startPositionMs)
        positionMs = startPositionMs
        val loaded = loadOutcomes[filename] ?: true
        return LoadResult(
            filename = filename,
            loaded = loaded,
            location = if (loaded) MediaLocation.ASSET else null,
        )
    }

    override fun play() {
        playCallCount += 1
    }

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) {
        seekRequests += positionMs
        this.positionMs = positionMs
    }

    override fun currentPositionMs(): Long = positionMs

    override fun currentDurationMs(): Long? = durationMs
}

private class FakeSyncPacketSource : SyncPacketSource {
    override fun packets(): Flow<SyncPacket> = error("Not used in direct SyncManager unit tests")

    override fun start() = Unit

    override fun stop() = Unit
}

private data class LoadRequest(
    val filename: String,
    val startPositionMs: Long,
)
