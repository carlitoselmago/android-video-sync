package com.androidvideosync.follower

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerController(
    private val context: Context,
    private val playerView: PlayerView,
    private val scope: CoroutineScope,
) : PlaybackController {
    private val appContext = context.applicationContext
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            scope.launch(Dispatchers.Main.immediate) {
                rebuildPlayerFromError()
            }
        }
    }
    private var playerGeneration = 0
    private var player: ExoPlayer = buildPlayer()
    private var lastRequestedMedia: RequestedMedia? = null
    private var isRecovering = false

    override suspend fun load(filename: String, startPositionMs: Long): LoadResult {
        return withContext(Dispatchers.Main.immediate) {
            val mediaDescriptor = resolveMediaDescriptor(filename)
                ?: return@withContext LoadResult(
                    filename = filename,
                    loaded = false,
                    location = null,
                )

            val requestedMedia = RequestedMedia(
                filename = filename,
                uri = mediaDescriptor.uri,
                location = mediaDescriptor.location,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
            )
            lastRequestedMedia = requestedMedia
            preparePlayer(requestedMedia)

            LoadResult(
                filename = filename,
                loaded = true,
                location = mediaDescriptor.location,
            )
        }
    }

    override fun play() {
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun currentDurationMs(): Long? {
        val durationMs = player.duration
        return if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
            null
        } else {
            durationMs
        }
    }

    fun release() {
        ensureMainThread()
        releasePlayer(player)
    }

    private fun preparePlayer(requestedMedia: RequestedMedia) {
        ensureMainThread()
        val mediaItem = MediaItem.fromUri(requestedMedia.uri)
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.seekTo(requestedMedia.startPositionMs)
        player.playWhenReady = true
        player.play()
        Log.d(
            TAG,
            "Prepared player#$playerGeneration media=${requestedMedia.filename} start=${requestedMedia.startPositionMs}",
        )
    }

    private fun buildPlayer(): ExoPlayer {
        ensureMainThread()
        playerGeneration += 1
        return ExoPlayer.Builder(appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            addListener(playerListener)
            playerView.player = this
            playerView.useController = false
            playerView.controllerAutoShow = false
            playerView.controllerHideOnTouch = false
            Log.d(TAG, "Created player#$playerGeneration")
        }
    }

    private fun rebuildPlayerFromError() {
        if (isRecovering) {
            return
        }

        val requestedMedia = lastRequestedMedia ?: return
        isRecovering = true
        try {
            val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
            Log.w(TAG, "Rebuilding player after playback error for ${requestedMedia.filename}")

            releasePlayer(player)
            player = buildPlayer()

            val resumeRequest = requestedMedia.copy(startPositionMs = resumePositionMs)
            lastRequestedMedia = resumeRequest
            preparePlayer(resumeRequest)
        } finally {
            isRecovering = false
        }
    }

    private fun resolveMediaDescriptor(filename: String): MediaDescriptor? {
        val directUri = resolveDirectUri(filename)
        if (directUri != null) {
            return MediaDescriptor(
                uri = directUri,
                location = MediaLocation.DOCUMENT_URI,
            )
        }

        val externalFile = File(MOVIES_DIRECTORY, filename)
        val hasPermission = hasExternalVideoPermission()
        if (hasPermission && externalFile.exists() && externalFile.isFile) {
            return MediaDescriptor(
                uri = Uri.fromFile(externalFile),
                location = MediaLocation.EXTERNAL_STORAGE,
            )
        }
        if (!hasPermission) {
            Log.w(TAG, "External video permission is missing; only asset fallback is available")
        }

        return if (assetExists(filename)) {
            MediaDescriptor(
                uri = Uri.parse("asset:///$filename"),
                location = MediaLocation.ASSET,
            )
        } else {
            null
        }
    }

    private fun resolveDirectUri(value: String): Uri? {
        val parsedUri = Uri.parse(value)
        return when (parsedUri.scheme?.lowercase()) {
            "content", "file" -> parsedUri
            else -> null
        }
    }

    private fun assetExists(filename: String): Boolean {
        return try {
            appContext.assets.open(filename).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasExternalVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PlayerController must be accessed from the main thread"
        }
    }

    private fun releasePlayer(playerToRelease: ExoPlayer) {
        ensureMainThread()
        try {
            if (playerView.player === playerToRelease) {
                playerView.player = null
            }
            playerToRelease.playWhenReady = false
            playerToRelease.pause()
            playerToRelease.stop()
            playerToRelease.clearMediaItems()
            playerToRelease.removeListener(playerListener)
        } finally {
            playerToRelease.release()
            Log.d(TAG, "Released player#$playerGeneration")
        }
    }

    private data class RequestedMedia(
        val filename: String,
        val uri: Uri,
        val location: MediaLocation,
        val startPositionMs: Long,
    )

    private data class MediaDescriptor(
        val uri: Uri,
        val location: MediaLocation,
    )

    companion object {
        private const val TAG = "PlayerController"
        private val MOVIES_DIRECTORY = File("/storage/emulated/0/Movies")
    }
}

data class LoadResult(
    val filename: String,
    val loaded: Boolean,
    val location: MediaLocation?,
)

enum class MediaLocation {
    EXTERNAL_STORAGE,
    ASSET,
    DOCUMENT_URI,
}
