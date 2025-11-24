package com.xuan25.heartratestreamer

import android.Manifest
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class HeartRatePermissionManager(
    private val activity: ComponentActivity,
    private val onStatus: (HeartRateStatus) -> Unit,
) {

    private var onAllGranted: (() -> Unit)? = null

    private var requestPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            handleRequestPermissionsResult(result)
        }

    private fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf<String>()

        // Notification
        // Apps don't need to request the POST_NOTIFICATIONS permission in order to launch a
        // foreground service.
        // However, apps must include a notification when they start a foreground service,
        // just as they do on previous versions of Android.
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             list += Manifest.permission.POST_NOTIFICATIONS
         }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // New granular health permissions
            list += HealthPermissions.READ_HEART_RATE
            list += HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND
        } else {
            // Legacy body sensor permissions
            list += Manifest.permission.BODY_SENSORS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.BODY_SENSORS_BACKGROUND
            }
        }

        return list.toTypedArray()
    }

    fun ensurePermissions(onAllGranted: () -> Unit) {
        this.onAllGranted = onAllGranted
        ensurePermissions()
    }

    fun ensurePermissions() {
        val required = getRequiredPermissions()
        val missing = required.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            onStatus(HeartRateStatus.RequestingPermissions)
        } else {
            onStatus(HeartRateStatus.PermissionsGranted)
            val callback = onAllGranted
            onAllGranted = null
            callback?.invoke()
        }
    }

    fun handleRequestPermissionsResult(result: Map<String, @JvmSuppressWildcards Boolean>) {

        // Did the user grant at least one permission in this dialog?
        val anyGranted = result.values.any { it }
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