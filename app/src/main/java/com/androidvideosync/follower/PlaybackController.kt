package com.androidvideosync.follower

interface PlaybackController {
    suspend fun load(filename: String, startPositionMs: Long): LoadResult

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun currentPositionMs(): Long

    fun currentDurationMs(): Long?
}
