package com.xuan25.heartratestreamer

import android.Manifest
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import androidx.activity.ComponentActivity

class HeartRatePermissionManager(
    private val activity: ComponentActivity,
    private val onStatus: (HeartRateStatus) -> Unit,
) {

    private var onAllGranted: (() -> Unit)? = null

    companion object {
        const val REQUEST_CODE = 100
    }

    private fun currentHeartRatePermissions(): Array<String> {
        val list = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // New granular health permissions
            list += HealthPermissions.READ_HEART_RATE
            list += HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND
        } else {
            // Legacy body sensor permissions
            list += Manifest.permission.BODY_SENSORS
            list += Manifest.permission.BODY_SENSORS_BACKGROUND
        }

        // For exercise sessions
        list += Manifest.permission.ACTIVITY_RECOGNITION

        return list.toTypedArray()
    }

    fun ensurePermissions(onAllGranted: () -> Unit) {
        this.onAllGranted = onAllGranted
        ensurePermissions()
    }

    fun ensurePermissions() {
        val required = currentHeartRatePermissions()
        val missing = required.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE)
            onStatus(HeartRateStatus.RequestingPermissions)
        } else {
            onStatus(HeartRateStatus.PermissionsGranted)
            val callback = onAllGranted;
            onAllGranted = null;
            callback?.invoke()
        }
    }

    fun handleRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != REQUEST_CODE) return

        // Did the user grant at least one permission in this dialog?
        val anyGranted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (!anyGranted) {
            onStatus(HeartRateStatus.PermissionDenied)
            onAllGranted = null
            return
        }

        // Run the same logic again:
        // - if anything still missing, it will request again
        // - otherwise it will call onAllGranted()
        ensurePermissions()
    }
}