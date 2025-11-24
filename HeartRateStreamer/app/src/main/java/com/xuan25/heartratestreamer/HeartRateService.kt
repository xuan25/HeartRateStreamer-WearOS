package com.xuan25.heartratestreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import com.xuan25.heartratestreamer.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class HeartRateService : Service() {

    companion object {

        private const val TAG = "HeartRateService"

        const val CHANNEL_ID = "heart_rate_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "HeartRateService.ACTION_START"
        const val ACTION_STOP = "HeartRateService.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, HeartRateService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HeartRateService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): HeartRateService = this@HeartRateService
    }

    private val binder = LocalBinder()

    // UI callbacks the Activity can set
    var uiHeartRateListener: ((Double) -> Unit)? = null
    var uiStatusListener: ((HeartRateStatus) -> Unit)? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var heartRateMeasuring: HeartRateMeasuring

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createNotificationChannel()

        // HeartRateMeasuring now lives in the service
        heartRateMeasuring = HeartRateMeasuring(
            context = this,
            scope = serviceScope,
            onHeartRate = { bpm ->
                // 1) Send to server
                HeartRateSender.send(bpm)
                // 2) notify UI if bound
                uiHeartRateListener?.invoke(bpm)
            },
            onStatus = { status ->
                Log.d(TAG, "Status: $status")
                uiStatusListener?.invoke(status)
            },
            mode = HeartRateMeasuring.MeasureMode.ExerciseClient
        )

        heartRateMeasuring.checkCapabilities()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "ACTION_START")

                val notification = buildNotification()
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // On Android 14+ we need to specify the type of foreground service
                    startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_HEALTH)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // Start measuring from the service
                heartRateMeasuring.start()
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")

                heartRateMeasuring.stop()

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // no-op
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // Cleanup measuring + scope
        heartRateMeasuring.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart rate streaming",
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Intent to open MainActivity when notification is tapped
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            // Reuse existing activity if it's already at the top
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)

        val notificationBuilder = builder
            .setContentTitle("Heart rate streaming")
            .setContentText("Heart rate streaming service is running")
            .setSmallIcon(R.drawable.ic_heart_rate_streamer_foreground)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivity =
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
                // Sets the icon that appears on the watch face in active mode.
                .setAnimatedIcon(R.drawable.animated_heart_rate_streamer_foreground)
                // Sets the icon that appears on the watch face in ambient mode.
                .setStaticIcon(R.drawable.ic_heart_rate_streamer_foreground)
                // Sets the tap target to bring the user back to the app.
                .setTouchIntent(pendingIntent)
                .build()

        ongoingActivity.apply(applicationContext)

        return notificationBuilder.build()
    }
}