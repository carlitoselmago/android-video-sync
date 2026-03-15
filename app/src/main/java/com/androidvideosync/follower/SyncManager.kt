package com.androidvideosync.follower

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SyncManager(
    private val playerController: PlaybackController,
    private val udpReceiver: SyncPacketSource,
    private val syncSettings: SyncSettings = SyncSettings(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var scope: CoroutineScope? = null
    private var collectorJob: Job? = null
    private var syncJob: Job? = null
    private val loadMutex = Mutex()
    private val driftSamples = ArrayDeque<Long>()

    @Volatile
    private var latestPacket: SyncPacket? = null
    @Volatile
    private var activeFilename: String? = null
    @Volatile
    private var lastFailedFilename: String? = null
    @Volatile
    private var lastFailedAtMs: Long = Long.MIN_VALUE
    @Volatile
    private var lastDriftSamplePacketElapsedRealtimeMs: Long = Long.MIN_VALUE
    @Volatile
    private var lastCorrectionElapsedRealtimeMs: Long = Long.MIN_VALUE

    fun start() {
        if (collectorJob?.isActive == true || syncJob?.isActive == true) {
            return
        }

        udpReceiver.start()
        val newScope = CoroutineScope(SupervisorJob() + workerDispatcher)
        scope = newScope

        collectorJob = newScope.launch {
            udpReceiver.packets().collect { packet ->
                handlePacket(packet)
            }
        }

        syncJob = newScope.launch {
            while (isActive) {
                delay(SyncPolicy.POLL_INTERVAL_MS)
                runSyncPass()
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        syncJob?.cancel()
        collectorJob = null
        syncJob = null
        latestPacket = null
        activeFilename = null
        lastFailedFilename = null
        lastFailedAtMs = Long.MIN_VALUE
        driftSamples.clear()
        lastDriftSamplePacketElapsedRealtimeMs = Long.MIN_VALUE
        lastCorrectionElapsedRealtimeMs = Long.MIN_VALUE
        udpReceiver.stop()
        scope?.cancel()
        scope = null
    }

    internal suspend fun handlePacket(packet: SyncPacket) {
        latestPacket = packet
        maybeLoadRequestedVideo(packet)
    }

    internal suspend fun runSyncPass() {
        applySyncIfNeeded()
    }

    private suspend fun maybeLoadRequestedVideo(packet: SyncPacket) {
        val playbackFilename = resolvePlaybackFilename(packet)
        val currentActiveFilename = activeFilename
        if (playbackFilename == currentActiveFilename) {
            return
        }

        val now = clock()
        if (
            playbackFilename == lastFailedFilename &&
            now - lastFailedAtMs < MISSING_FILE_RETRY_INTERVAL_MS
        ) {
            return
        }

        loadMutex.withLock {
            if (playbackFilename == activeFilename) {
                return
            }

            val result = withContext(mainDispatcher) {
                playerController.load(
                    filename = playbackFilename,
                    startPositionMs = packet.playbackMs + syncSettings.jumpAheadMs,
                )
            }
            if (result.loaded) {
                activeFilename = playbackFilename
                lastFailedFilename = null
                lastFailedAtMs = Long.MIN_VALUE
                resetSyncWindow()
                withContext(mainDispatcher) {
                    playerController.play()
                }
                Log.i(
                    TAG,
                    "Loaded video ${result.filename} from ${result.location} startPositionMs=${packet.playbackMs + syncSettings.jumpAheadMs} leaderFile=${packet.filename}",
                )
            } else {
                lastFailedFilename = playbackFilename
                lastFailedAtMs = now
                Log.w(TAG, "Requested video $playbackFilename was not found for leaderFile=${packet.filename}")
            }
        }
    }

    private suspend fun applySyncIfNeeded() {
        val packet = latestPacket ?: return
        if (resolvePlaybackFilename(packet) != activeFilename) {
            maybeLoadRequestedVideo(packet)
            return
        }

        val durationMs = withContext(mainDispatcher) {
            playerController.currentDurationMs()
        }
        val localPlaybackMs = withContext(mainDispatcher) {
            playerController.currentPositionMs()
        }

        val decision = SyncPolicy.evaluate(
            packet = packet,
            nowElapsedRealtimeMs = clock(),
            localPlaybackMs = localPlaybackMs,
            durationMs = durationMs,
            settings = syncSettings,
        )
        val estimatedMasterPlaybackMs = decision.estimatedMasterPlaybackMs ?: return
        val desiredLocalPlaybackMs = decision.desiredLocalPlaybackMs ?: return
        val driftMs = decision.driftMs ?: return

        if (packet.receivedElapsedRealtimeMs != lastDriftSamplePacketElapsedRealtimeMs) {
            appendDriftSample(driftMs)
            lastDriftSamplePacketElapsedRealtimeMs = packet.receivedElapsedRealtimeMs
        }

        val medianDriftMs = SyncPolicy.medianDriftMs(driftSamples) ?: return
        val nowElapsedRealtimeMs = clock()
        if (
            !SyncPolicy.shouldResync(
                medianDriftMs = medianDriftMs,
                estimatedMasterPlaybackMs = estimatedMasterPlaybackMs,
                localPlaybackMs = localPlaybackMs,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                lastCorrectionElapsedRealtimeMs = lastCorrectionElapsedRealtimeMs,
                settings = syncSettings,
            )
        ) {
            return
        }

        Log.d(
            TAG,
            "Seek correction local=$localPlaybackMs desired=$desiredLocalPlaybackMs " +
                "estimatedMaster=$estimatedMasterPlaybackMs drift=$driftMs medianDrift=$medianDriftMs " +
                "target=$desiredLocalPlaybackMs duration=$durationMs",
        )
        withContext(mainDispatcher) {
            playerController.seekTo(desiredLocalPlaybackMs)
        }
        lastCorrectionElapsedRealtimeMs = nowElapsedRealtimeMs
        driftSamples.clear()
    }

    private fun appendDriftSample(driftMs: Long) {
        if (driftSamples.size == SyncPolicy.DRIFT_HISTORY_SIZE) {
            driftSamples.removeFirst()
        }
        driftSamples.addLast(driftMs)
    }

    private fun resetSyncWindow() {
        driftSamples.clear()
        lastDriftSamplePacketElapsedRealtimeMs = Long.MIN_VALUE
        lastCorrectionElapsedRealtimeMs = Long.MIN_VALUE
    }

    private fun resolvePlaybackFilename(packet: SyncPacket): String {
        return syncSettings.selectedVideoUri
            ?.takeIf { it.isNotBlank() }
            ?: syncSettings.selectedFilename.ifBlank { packet.filename }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MISSING_FILE_RETRY_INTERVAL_MS = 5_000L
    }
}

data class SyncDecision(
    val estimatedMasterPlaybackMs: Long?,
    val desiredLocalPlaybackMs: Long?,
    val seekTargetMs: Long?,
    val driftMs: Long?,
)

object SyncPolicy {
    const val POLL_INTERVAL_MS = 250L
    const val PACKET_TIMEOUT_MS = 5_000L
    const val DRIFT_HISTORY_SIZE = 10

    fun estimateMasterPlaybackMs(
        packet: SyncPacket,
        nowElapsedRealtimeMs: Long,
    ): Long {
        val elapsedSincePacket = (nowElapsedRealtimeMs - packet.receivedElapsedRealtimeMs).coerceAtLeast(0L)
        return packet.playbackMs + elapsedSincePacket
    }

    fun evaluate(
        packet: SyncPacket,
        nowElapsedRealtimeMs: Long,
        localPlaybackMs: Long,
        durationMs: Long?,
        settings: SyncSettings = SyncSettings(),
    ): SyncDecision {
        if (nowElapsedRealtimeMs - packet.receivedElapsedRealtimeMs > PACKET_TIMEOUT_MS) {
            return SyncDecision(
                estimatedMasterPlaybackMs = null,
                desiredLocalPlaybackMs = null,
                seekTargetMs = null,
                driftMs = null,
            )
        }

        val estimatedMasterPlaybackMs = estimateMasterPlaybackMs(packet, nowElapsedRealtimeMs)
        val desiredLocalPlaybackMs = computeDesiredLocalPlaybackMs(
            estimatedMasterPlaybackMs = estimatedMasterPlaybackMs,
            durationMs = durationMs,
            jumpAheadMs = settings.jumpAheadMs,
        )
        val driftMs = computeDriftMs(
            masterPlaybackMs = desiredLocalPlaybackMs,
            localPlaybackMs = localPlaybackMs,
            durationMs = durationMs,
        )
        if (kotlin.math.abs(driftMs) <= settings.toleranceMs) {
            return SyncDecision(
                estimatedMasterPlaybackMs = estimatedMasterPlaybackMs,
                desiredLocalPlaybackMs = desiredLocalPlaybackMs,
                seekTargetMs = null,
                driftMs = driftMs,
            )
        }

        return SyncDecision(
            estimatedMasterPlaybackMs = estimatedMasterPlaybackMs,
            desiredLocalPlaybackMs = desiredLocalPlaybackMs,
            seekTargetMs = desiredLocalPlaybackMs,
            driftMs = driftMs,
        )
    }

    fun computeDesiredLocalPlaybackMs(
        estimatedMasterPlaybackMs: Long,
        durationMs: Long?,
        jumpAheadMs: Long = SyncSettings.DEFAULT_JUMP_AHEAD_MS,
    ): Long {
        val targetMs = estimatedMasterPlaybackMs + jumpAheadMs
        if (durationMs == null || durationMs <= 0L) {
            return targetMs
        }

        return ((targetMs % durationMs) + durationMs) % durationMs
    }

    fun computeDriftMs(
        masterPlaybackMs: Long,
        localPlaybackMs: Long,
        durationMs: Long?,
    ): Long {
        if (durationMs == null || durationMs <= 0L) {
            return masterPlaybackMs - localPlaybackMs
        }

        val rawDifference = ((masterPlaybackMs - localPlaybackMs) % durationMs + durationMs) % durationMs
        return if (rawDifference > durationMs / 2L) {
            rawDifference - durationMs
        } else {
            rawDifference
        }
    }

    fun medianDriftMs(drifts: Collection<Long>): Long? {
        if (drifts.isEmpty()) {
            return null
        }
        val sorted = drifts.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2L
        }
    }

    fun shouldResync(
        medianDriftMs: Long,
        estimatedMasterPlaybackMs: Long,
        localPlaybackMs: Long,
        nowElapsedRealtimeMs: Long,
        lastCorrectionElapsedRealtimeMs: Long,
        settings: SyncSettings = SyncSettings(),
    ): Boolean {
        if (kotlin.math.abs(medianDriftMs) <= settings.toleranceMs) {
            return false
        }
        if (
            localPlaybackMs <= settings.graceTimeMs ||
            estimatedMasterPlaybackMs <= settings.graceTimeMs
        ) {
            return false
        }
        if (
            lastCorrectionElapsedRealtimeMs != Long.MIN_VALUE &&
            nowElapsedRealtimeMs - lastCorrectionElapsedRealtimeMs <= settings.graceTimeMs
        ) {
            return false
        }
        return true
    }
}
