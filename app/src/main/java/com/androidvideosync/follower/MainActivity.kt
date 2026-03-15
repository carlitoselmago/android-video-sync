package com.androidvideosync.follower

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private lateinit var settingsPanel: ScrollView
    private lateinit var playerView: PlayerView
    private lateinit var selectedVideoValue: TextView
    private lateinit var toleranceEditText: EditText
    private lateinit var graceEditText: EditText
    private lateinit var jumpAheadEditText: EditText
    private lateinit var kioskModeSwitch: SwitchCompat
    private lateinit var browseVideoButton: Button
    private lateinit var useDefaultVideoButton: Button
    private lateinit var startFollowerButton: Button

    private lateinit var playerController: PlayerController
    private lateinit var udpReceiver: UdpReceiver
    private lateinit var settingsStore: SyncSettingsStore

    private var syncManager: SyncManager? = null
    private var shouldResumeFollower = false
    private var activeSettings: SyncSettings = SyncSettings()
    private var selectedVideoUri: String? = null
    private var selectedVideoLabel: String = SyncSettings.DEFAULT_FILENAME
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            grantPersistentReadAccess(uri)
            selectedVideoUri = uri.toString()
            selectedVideoLabel = resolveDisplayName(uri) ?: SyncSettings.DEFAULT_FILENAME
            updateSelectedVideoView()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()

        playerView.apply {
            useController = false
            controllerAutoShow = false
            controllerHideOnTouch = false
            setOnTouchListener { _, _ -> true }
        }

        settingsStore = SyncSettingsStore(applicationContext)
        activeSettings = settingsStore.load()
        playerController = PlayerController(
            context = applicationContext,
            playerView = playerView,
            scope = lifecycleScope,
        )
        udpReceiver = UdpReceiver(applicationContext)

        browseVideoButton.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }
        useDefaultVideoButton.setOnClickListener {
            selectedVideoUri = null
            selectedVideoLabel = SyncSettings.DEFAULT_FILENAME
            updateSelectedVideoView()
        }
        startFollowerButton.setOnClickListener {
            startFollower()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activeSettings.kioskModeEnabled) {
                        return
                    }
                    if (shouldResumeFollower) {
                        showSettingsMenu()
                    } else {
                        finish()
                    }
                }
            },
        )

        populateSettingsForm(activeSettings)
        showSettingsMenu()
        hideSystemUi()
        warnIfExternalVideoPermissionMissing()
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        applyKioskMode(activeSettings)
        if (shouldResumeFollower) {
            syncManager?.start()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        syncManager?.stop()
        playerController.pause()
        super.onStop()
    }

    override fun onDestroy() {
        syncManager?.stop()
        playerController.release()
        super.onDestroy()
    }

    private fun bindViews() {
        settingsPanel = findViewById(R.id.settingsPanel)
        playerView = findViewById(R.id.playerView)
        selectedVideoValue = findViewById(R.id.selectedVideoValue)
        toleranceEditText = findViewById(R.id.toleranceEditText)
        graceEditText = findViewById(R.id.graceEditText)
        jumpAheadEditText = findViewById(R.id.jumpAheadEditText)
        kioskModeSwitch = findViewById(R.id.kioskModeSwitch)
        browseVideoButton = findViewById(R.id.browseVideoButton)
        useDefaultVideoButton = findViewById(R.id.useDefaultVideoButton)
        startFollowerButton = findViewById(R.id.startFollowerButton)
    }

    private fun startFollower() {
        val settings = currentSettingsFromForm()
        activeSettings = settings
        settingsStore.save(settings)

        syncManager?.stop()
        syncManager = SyncManager(
            playerController = playerController,
            udpReceiver = udpReceiver,
            syncSettings = settings,
        )
        shouldResumeFollower = true
        playerView.visibility = View.VISIBLE
        settingsPanel.visibility = View.GONE
        hideSystemUi()
        applyKioskMode(settings)
        syncManager?.start()
    }

    private fun showSettingsMenu() {
        shouldResumeFollower = false
        syncManager?.stop()
        playerController.pause()
        settingsPanel.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        hideSystemUi()
    }

    private fun populateSettingsForm(settings: SyncSettings) {
        selectedVideoUri = settings.selectedVideoUri
        selectedVideoLabel = settings.selectedFilename
        toleranceEditText.setText(settings.toleranceSecondsText())
        graceEditText.setText(settings.graceSecondsText())
        jumpAheadEditText.setText(settings.jumpAheadSecondsText())
        kioskModeSwitch.isChecked = settings.kioskModeEnabled
        updateSelectedVideoView()
    }

    private fun currentSettingsFromForm(): SyncSettings {
        return SyncSettings(
            selectedFilename = selectedVideoLabel,
            selectedVideoUri = selectedVideoUri,
            toleranceMs = SyncSettings.secondsTextToMillis(
                rawValue = toleranceEditText.text.toString(),
                fallbackMs = SyncSettings.DEFAULT_TOLERANCE_MS,
            ),
            graceTimeMs = SyncSettings.secondsTextToMillis(
                rawValue = graceEditText.text.toString(),
                fallbackMs = SyncSettings.DEFAULT_GRACE_TIME_MS,
            ),
            jumpAheadMs = SyncSettings.secondsTextToMillis(
                rawValue = jumpAheadEditText.text.toString(),
                fallbackMs = SyncSettings.DEFAULT_JUMP_AHEAD_MS,
            ),
            kioskModeEnabled = kioskModeSwitch.isChecked,
        )
    }

    private fun updateSelectedVideoView() {
        selectedVideoValue.text = if (selectedVideoUri.isNullOrBlank()) {
            selectedVideoLabel
        } else {
            "$selectedVideoLabel\n$selectedVideoUri"
        }
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun startLockTaskIfAllowed() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        ) {
            return
        }

        try {
            startLockTask()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Lock task mode is unavailable on this device", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "App is not allowlisted for lock task mode; running immersive-only", error)
        }
    }

    private fun stopLockTaskIfRunning() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
        ) {
            return
        }

        try {
            stopLockTask()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Lock task mode was not started by this activity", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to stop lock task mode on this device", error)
        }
    }

    private fun applyKioskMode(settings: SyncSettings) {
        if (settings.kioskModeEnabled) {
            startLockTaskIfAllowed()
        } else {
            stopLockTaskIfRunning()
        }
    }

    private fun grantPersistentReadAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to persist read access for selected video", error)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(columnIndex)
            } else {
                null
            }
        } ?: uri.lastPathSegment
    }

    private fun warnIfExternalVideoPermissionMissing() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(
                TAG,
                "Video read permission is not granted. External files in Movies/ will be unavailable until the permission is pre-granted.",
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
