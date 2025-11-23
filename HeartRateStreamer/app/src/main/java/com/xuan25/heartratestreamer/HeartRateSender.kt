package com.xuan25.heartratestreamer

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

object HeartRateSender {

    private const val TAG = "HeartRateSender"

    // Coroutine scope for background network work
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastJob: Job? = null;

    // Endpoint the user can change
    @Volatile
    private var endpoint: String = ""

    fun setEndpoint(url: String) {
        endpoint = url
    }

    // Simple throttle: at most once per second
    private const val MIN_INTERVAL_MS = 1000L
    @Volatile
    private var lastSentAt: Long = 0L

    // Listener that MainActivity will set
    private var statusListener: ((ConnectionStatus) -> Unit)? = null

    fun setStatusListener(listener: (ConnectionStatus) -> Unit) {
        statusListener = listener
    }

    fun send(bpm: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_INTERVAL_MS) {
            return // too soon, skip this one
        }
        lastSentAt = now

        val json = """{
            "heart_rate": $bpm,
            "timestamp": $now
        }""".trimIndent()

        // Notify we're sending
        statusListener?.invoke(ConnectionStatus.Sending)

        lastJob = scope.launch {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                // Send JSON body
                connection.outputStream.use { output ->
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    output.write(bytes)
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "Sent HR $bpm, response: $code")
                    statusListener?.invoke(ConnectionStatus.Ok)
                } else {
                    Log.w(TAG, "Non-success HTTP code: $code")
                    statusListener?.invoke(ConnectionStatus.Error)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Sent HR Canceled")
                statusListener?.invoke(ConnectionStatus.Idle)
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to send heart rate", e)
                statusListener?.invoke(ConnectionStatus.Error)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun reset() {
        lastJob?.cancel()
        lastSentAt = 0L
        statusListener?.invoke(ConnectionStatus.Idle)
    }
}