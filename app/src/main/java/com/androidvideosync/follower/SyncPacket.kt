package com.androidvideosync.follower

data class SyncPacket(
    val playbackMs: Long,
    val filename: String,
    val receivedElapsedRealtimeMs: Long,
)
