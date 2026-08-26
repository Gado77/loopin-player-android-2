package com.loopin.player2

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger

class KioskController(
    private val activity: Activity,
    private val logger: PlayerLogger,
) {
    fun enter() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        startLockTaskWhenProvisioned()
    }

    fun reapplySystemUi() = applyImmersiveMode()

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun startLockTaskWhenProvisioned() {
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val alreadyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            activityManager.isInLockTaskMode
        }
        if (alreadyLocked) return
        val devicePolicyManager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!devicePolicyManager.isLockTaskPermitted(activity.packageName)) {
            logger.log(LogLevel.INFO, TAG, "Lock task unavailable; using HOME + immersive kiosk")
            return
        }
        runCatching { activity.startLockTask() }
            .onFailure { logger.log(LogLevel.WARN, TAG, "Unable to enter lock task", it) }
    }

    companion object {
        private const val TAG = "KioskController"
    }
}
