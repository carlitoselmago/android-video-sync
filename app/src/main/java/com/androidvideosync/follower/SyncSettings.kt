package com.androidvideosync.follower

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

data class SyncSettings(
    val selectedFilename: String = DEFAULT_FILENAME,
    val selectedVideoUri: String? = null,
    val toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    val graceTimeMs: Long = DEFAULT_GRACE_TIME_MS,
    val jumpAheadMs: Long = DEFAULT_JUMP_AHEAD_MS,
    val kioskModeEnabled: Boolean = false,
) {
    fun toleranceSecondsText(): String = millisToSecondsText(toleranceMs)

    fun graceSecondsText(): String = millisToSecondsText(graceTimeMs)

    fun jumpAheadSecondsText(): String = millisToSecondsText(jumpAheadMs)

    companion object {
        const val DEFAULT_FILENAME = "video.mp4"
        const val DEFAULT_TOLERANCE_MS = 300L
        const val DEFAULT_GRACE_TIME_MS = 10_000L
        const val DEFAULT_JUMP_AHEAD_MS = 2_000L

        fun secondsTextToMillis(
            rawValue: String,
            fallbackMs: Long,
        ): Long {
            return try {
                BigDecimal(rawValue.trim())
                    .movePointRight(3)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
                    .coerceAtLeast(0L)
            } catch (_: Exception) {
                fallbackMs
            }
        }

        fun millisToSecondsText(valueMs: Long): String {
            return BigDecimal(valueMs)
                .movePointLeft(3)
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}

class SyncSettingsStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): SyncSettings {
        return SyncSettings(
            selectedFilename = preferences.getString(KEY_SELECTED_FILENAME, SyncSettings.DEFAULT_FILENAME)
                ?.takeIf { it.isNotBlank() }
                ?: SyncSettings.DEFAULT_FILENAME,
            selectedVideoUri = preferences.getString(KEY_SELECTED_VIDEO_URI, null)
                ?.takeIf { it.isNotBlank() },
            toleranceMs = preferences.getLong(KEY_TOLERANCE_MS, SyncSettings.DEFAULT_TOLERANCE_MS),
            graceTimeMs = preferences.getLong(KEY_GRACE_TIME_MS, SyncSettings.DEFAULT_GRACE_TIME_MS),
            jumpAheadMs = preferences.getLong(KEY_JUMP_AHEAD_MS, SyncSettings.DEFAULT_JUMP_AHEAD_MS),
            kioskModeEnabled = preferences.getBoolean(KEY_KIOSK_MODE_ENABLED, false),
        )
    }

    fun save(settings: SyncSettings) {
        preferences.edit()
            .putString(KEY_SELECTED_FILENAME, settings.selectedFilename)
            .putString(KEY_SELECTED_VIDEO_URI, settings.selectedVideoUri)
            .putLong(KEY_TOLERANCE_MS, settings.toleranceMs)
            .putLong(KEY_GRACE_TIME_MS, settings.graceTimeMs)
            .putLong(KEY_JUMP_AHEAD_MS, settings.jumpAheadMs)
            .putBoolean(KEY_KIOSK_MODE_ENABLED, settings.kioskModeEnabled)
            .apply()
    }

    fun availableVideoFiles(): List<String> {
        val filenames = linkedSetOf<String>()
        filenames += SyncSettings.DEFAULT_FILENAME
        filenames += assetVideoFiles()
        filenames += externalVideoFiles()
        return filenames.toList()
    }

    private fun assetVideoFiles(): List<String> {
        return try {
            appContext.assets.list("")
                ?.filter { it.isVideoFilename() }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun externalVideoFiles(): List<String> {
        if (!hasExternalVideoPermission()) {
            return emptyList()
        }
        return MOVIES_DIRECTORY.listFiles()
            ?.filter { it.isFile && it.name.isVideoFilename() }
            ?.map { it.name }
            .orEmpty()
    }

    private fun hasExternalVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun String.isVideoFilename(): Boolean {
        val lowercaseName = lowercase()
        return lowercaseName.endsWith(".mp4") ||
            lowercaseName.endsWith(".m4v") ||
            lowercaseName.endsWith(".mov") ||
            lowercaseName.endsWith(".webm") ||
            lowercaseName.endsWith(".mkv")
    }

    companion object {
        private const val PREFERENCES_NAME = "sync_settings"
        private const val KEY_SELECTED_FILENAME = "selected_filename"
        private const val KEY_SELECTED_VIDEO_URI = "selected_video_uri"
        private const val KEY_TOLERANCE_MS = "tolerance_ms"
        private const val KEY_GRACE_TIME_MS = "grace_time_ms"
        private const val KEY_JUMP_AHEAD_MS = "jump_ahead_ms"
        private const val KEY_KIOSK_MODE_ENABLED = "kiosk_mode_enabled"
        private val MOVIES_DIRECTORY = File("/storage/emulated/0/Movies")
    }
}
