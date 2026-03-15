package com.androidvideosync.follower

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled for kiosk provisioning")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling kiosk administration will allow users to exit the installation player."
    }

    companion object {
        private const val TAG = "KioskDeviceAdmin"
    }
}
