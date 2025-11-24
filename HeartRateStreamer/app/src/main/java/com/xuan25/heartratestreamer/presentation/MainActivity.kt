/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.xuan25.heartratestreamer.presentation

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices

import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.xuan25.heartratestreamer.ConnectionStatus
import com.xuan25.heartratestreamer.HeartRatePermissionManager
import com.xuan25.heartratestreamer.HeartRateSender
import com.xuan25.heartratestreamer.HeartRateService
import com.xuan25.heartratestreamer.HeartRateStatus
import com.xuan25.heartratestreamer.presentation.theme.HeartRateStreamerTheme
import androidx.core.content.edit


class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "heart_rate_prefs"
        private const val KEY_ENDPOINT = "endpoint"
    }

    private lateinit var permissionManager: HeartRatePermissionManager

    // UI state
    private var heartRateState by mutableStateOf<Double?>(null)
    private var statusState by mutableStateOf(HeartRateStatus.Init)
    private var connectionStatusState by mutableStateOf(ConnectionStatus.Idle)
    private var endpointState by mutableStateOf("")

    private var heartRateService: HeartRateService? = null
    private var isServiceBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? HeartRateService.LocalBinder ?: return
            heartRateService = binder.getService()
            isServiceBound = true

            // Register UI listeners
            heartRateService?.uiHeartRateListener = { bpm ->
                runOnUiThread {
                    heartRateState = bpm
                }
            }
            heartRateService?.uiStatusListener = { status ->
                runOnUiThread {
                    statusState = status

                    if (statusState == HeartRateStatus.Stopped) {
                        heartRateState = null
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            heartRateService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service so we can get live updates
        val intent = Intent(this, HeartRateService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            // Clear listeners to avoid leaking Activity
            heartRateService?.uiHeartRateListener = null
            heartRateService?.uiStatusListener = null
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun loadSavedEndpoint(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // default value if none saved yet
        return prefs.getString(KEY_ENDPOINT, "")!!
    }

    private fun saveEndpoint(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(KEY_ENDPOINT, url) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        endpointState = loadSavedEndpoint()
        HeartRateSender.setEndpoint(endpointState)

        permissionManager = HeartRatePermissionManager(
            activity = this,
            onStatus = { status ->
                statusState = status
            },
        )

        HeartRateSender.setStatusListener { status ->
            // Ensure UI update runs on main thread
            runOnUiThread {
                connectionStatusState = status
            }
        }

        // 3) Simple UI for now
        setContent {
            HeartRateScreen(
                heartRate = heartRateState,
                status = statusState,
                connectionStatus = connectionStatusState,
                endpoint = endpointState,
                onEndpointChanged = { newUrl ->
                    endpointState = newUrl
                    HeartRateSender.setEndpoint(newUrl)
                    saveEndpoint(newUrl)
                },
                onStartClick = { onStartClicked() },
                onStopClick = { onStopClicked() }
            )
        }
    }

    private fun onStartClicked() {
        permissionManager.ensurePermissions {
            statusState = HeartRateStatus.PermissionsGranted
            HeartRateService.start(this)  // start foreground service
        }
    }

    private fun onStopClicked() {
        HeartRateService.stop(this)       // ask service to stop
        connectionStatusState = ConnectionStatus.Idle
    }
}

private const val KEY_ENDPOINT_TEXT: String = "endpoint"

@Composable
fun HeartRateScreen(
    heartRate: Double?,
    status: HeartRateStatus,
    connectionStatus: ConnectionStatus,
    endpoint: String,
    onEndpointChanged: (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    // --- status text ---
    val statusText = when (status) {
        HeartRateStatus.Init -> "Init"
        HeartRateStatus.CheckingCapabilities -> "Check"
        HeartRateStatus.SensorNotSupported -> "No sensor"
        HeartRateStatus.SensorSupported -> "OK"
        HeartRateStatus.RequestingPermissions -> "Perm?"
        HeartRateStatus.PermissionDenied -> "No perm"
        HeartRateStatus.PermissionsGranted -> "Ready"
        HeartRateStatus.Starting -> "Start"
        HeartRateStatus.WaitingForData -> "Wait"
        HeartRateStatus.Streaming -> "Live"
        HeartRateStatus.Stopping -> "Stop..."
        HeartRateStatus.Stopped -> "Stopped"

        HeartRateStatus.Error -> "Error"
    }

    // --- status icon (single-color) ---
    val statusIcon = when (status) {
        HeartRateStatus.Streaming -> Icons.Rounded.RadioButtonChecked
        HeartRateStatus.Starting,
        HeartRateStatus.WaitingForData,
        HeartRateStatus.CheckingCapabilities -> Icons.Rounded.HourglassEmpty
        HeartRateStatus.PermissionDenied,
        HeartRateStatus.SensorNotSupported,
        HeartRateStatus.Error -> Icons.Rounded.Error
        else -> Icons.Rounded.RadioButtonUnchecked
    }

    // --- connection icon + text ---
    val connectionIcon = when (connectionStatus) {
        ConnectionStatus.Ok -> Icons.Rounded.Wifi
        ConnectionStatus.Sending -> Icons.Rounded.CloudUpload
        ConnectionStatus.Error -> Icons.Rounded.Error
        ConnectionStatus.Idle -> Icons.Rounded.Pause
    }

    val connectionText = when (connectionStatus) {
        ConnectionStatus.Idle -> "Idle"
        ConnectionStatus.Sending -> "Send"
        ConnectionStatus.Error -> "Err"
        ConnectionStatus.Ok -> "OK"
    }

    // --- Logic for conditional buttons ---
    val isMeasuringPhase = when (status) {
        HeartRateStatus.Starting,
        HeartRateStatus.WaitingForData,
        HeartRateStatus.Streaming,
        HeartRateStatus.Stopping -> true
        else -> false
    }

    val isBusyPermissionsOrCheck = when (status) {
        HeartRateStatus.RequestingPermissions,
        HeartRateStatus.CheckingCapabilities -> true
        else -> false
    }

    val canStart = !isMeasuringPhase && !isBusyPermissionsOrCheck
    @Suppress("UnnecessaryVariable", "RedundantSuppression") val canStop = isMeasuringPhase
    // -------------------------------------


    // --- endpoint input ---

    val onEndpointInputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        it.data?.let { data ->
            val results: Bundle = RemoteInput.getResultsFromIntent(data)
            val endpoint: CharSequence? = results.getCharSequence(KEY_ENDPOINT_TEXT)
            onEndpointChanged(endpoint.toString())
        }
    }

    val onEndpointInputClick = {
        val remoteInputs: List<RemoteInput> = listOf(
            RemoteInput.Builder(KEY_ENDPOINT_TEXT)
                .setLabel("Input Endpoint")
                .wearableExtender {
                    setEmojisAllowed(false)
                    setInputActionType(EditorInfo.IME_ACTION_DONE)
                }.build()
        )

        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)

        onEndpointInputLauncher.launch(intent)
    }

    HeartRateStreamerTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Heart rate
            Text(
                text = heartRate?.let { "${it.toInt()} bpm" } ?: "-- bpm",
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Status + connection row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(IntrinsicSize.Min)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = "Status",
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(14.dp)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 18.dp) // make sure text doesn't overlap the icon
                    )
                }

                // Optional spacing between the two blocks
                Spacer(modifier = Modifier.width(8.dp))

                // Connection block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(IntrinsicSize.Min)
                ) {
                    Icon(
                        imageVector = connectionIcon,
                        contentDescription = "Connection",
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(14.dp)
                    )
                    Text(
                        text = connectionText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Endpoint label
            Text(
                text = "Endpoint",
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                color = if (canStart) MaterialTheme.colors.onBackground else MaterialTheme.colors.onPrimary,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Endpoint input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .border(
                        width = 1.dp,
                        color = if (canStart) MaterialTheme.colors.primary else MaterialTheme.colors.onPrimary,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(
                        color = MaterialTheme.colors.background,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.CenterStart,

            ) {
                TextButton (
                    enabled = canStart,
                    onClick = onEndpointInputClick,
                    modifier = Modifier
                        .fillMaxSize()
                ) {

                }
                Text(
                    text = endpoint,
                    fontSize = 10.sp,
                    color = if (canStart) MaterialTheme.colors.onBackground else MaterialTheme.colors.onPrimary,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStartClick,
                    enabled = canStart,
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Start streaming",
                    )
                }
                Button(
                    onClick = onStopClick,
                    enabled = canStop,
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Stop streaming",
                    )
                }
            }
        }
    }
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    HeartRateScreen(
        126.0,
        HeartRateStatus.Init,
        ConnectionStatus.Idle,
        "http://192.168.8.2:9025/hr",
        { _ -> },
        {},
        {}
    )
}
