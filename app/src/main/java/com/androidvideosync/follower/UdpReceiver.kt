package com.androidvideosync.follower

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UdpReceiver(
    context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : SyncPacketSource {
    private val appContext = context.applicationContext
    private val packetsFlow = MutableSharedFlow<SyncPacket>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null
    @Volatile
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun packets(): Flow<SyncPacket> = packetsFlow.asSharedFlow()

    override fun start() {
        if (receiveJob?.isActive == true) {
            return
        }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        receiveJob = newScope.launch {
            acquireMulticastLock()
            while (isActive) {
                try {
                    receivePackets()
                } catch (error: SocketException) {
                    if (!isActive) {
                        break
                    }
                    Log.w(TAG, "UDP socket failed, restarting listener", error)
                    delay(SOCKET_RESTART_DELAY_MS)
                } catch (error: Exception) {
                    if (!isActive) {
                        break
                    }
                    Log.w(TAG, "Unexpected UDP listener error, restarting listener", error)
                    delay(SOCKET_RESTART_DELAY_MS)
                } finally {
                    closeSocket()
                }
            }
            releaseMulticastLock()
        }
    }

    override fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        closeSocket()
        releaseMulticastLock()
        scope?.cancel()
        scope = null
    }

    private suspend fun receivePackets() {
        val newSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = SOCKET_TIMEOUT_MS.toInt()
            bind(InetSocketAddress(LISTEN_HOST, LISTEN_PORT))
        }
        socket = newSocket

        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (scope?.isActive == true) {
            val datagramPacket = DatagramPacket(buffer, buffer.size)
            try {
                newSocket.receive(datagramPacket)
                val message = String(
                    datagramPacket.data,
                    datagramPacket.offset,
                    datagramPacket.length,
                    StandardCharsets.UTF_8,
                )
                val syncPacket = PacketParser.parse(message, clock())
                if (syncPacket != null) {
                    Log.d(
                        TAG,
                        "Received sync packet playbackMs=${syncPacket.playbackMs} filename=${syncPacket.filename}",
                    )
                    packetsFlow.tryEmit(syncPacket)
                } else {
                    Log.w(TAG, "Ignoring malformed sync packet: $message")
                }
            } catch (_: SocketTimeoutException) {
                // Timeout keeps the loop responsive to cancellation and socket restarts.
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) {
            return
        }

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("$TAG.multicast").apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (error: SecurityException) {
                Log.w(TAG, "Failed to acquire multicast lock", error)
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
    }

    private fun closeSocket() {
        socket?.close()
        socket = null
    }

    companion object {
        private const val TAG = "UdpReceiver"
        private const val LISTEN_HOST = "0.0.0.0"
        private const val LISTEN_PORT = 1666
        private const val MAX_PACKET_BYTES = 1024
        private const val SOCKET_TIMEOUT_MS = 1_000L
        private const val SOCKET_RESTART_DELAY_MS = 1_000L
    }
}

object PacketParser {
    fun parse(message: String, receivedElapsedRealtimeMs: Long): SyncPacket? {
        val trimmed = message.trim()
        val delimiterIndex = trimmed.indexOf('%')
        if (delimiterIndex <= 0 || delimiterIndex == trimmed.lastIndex) {
            return null
        }

        val secondsPart = trimmed.substring(0, delimiterIndex).trim()
        val filenamePart = trimmed.substring(delimiterIndex + 1).trim()
        if (filenamePart.isEmpty()) {
            return null
        }

        val playbackMs = try {
            BigDecimal(secondsPart)
                .movePointRight(3)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: NumberFormatException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }

        if (playbackMs < 0L) {
            return null
        }

        return SyncPacket(
            playbackMs = playbackMs,
            filename = filenamePart,
            receivedElapsedRealtimeMs = receivedElapsedRealtimeMs,
        )
    }
}
