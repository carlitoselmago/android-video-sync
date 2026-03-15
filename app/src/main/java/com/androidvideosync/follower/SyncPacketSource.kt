package com.androidvideosync.follower

import kotlinx.coroutines.flow.Flow

interface SyncPacketSource {
    fun packets(): Flow<SyncPacket>

    fun start()

    fun stop()
}
