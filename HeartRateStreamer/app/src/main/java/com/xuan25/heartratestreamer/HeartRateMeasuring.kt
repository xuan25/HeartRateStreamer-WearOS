package com.xuan25.heartratestreamer

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.startExercise
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class HeartRateMeasuring(
    context: Context,
    private val scope: CoroutineScope,
    private val onHeartRate: (Double) -> Unit,
    private val onStatus: (HeartRateStatus) -> Unit,
    private val mode: MeasureMode
) {

    enum class MeasureMode {
        MeasureClient,
        ExerciseClient,
    }

    private val tag = "HeartRateMeasuring"

    private val measureClient: MeasureClient = HealthServices.getClient(context).measureClient

    private val exerciseClient: ExerciseClient =  HealthServices.getClient(context).exerciseClient

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            if (dataType == DataType.HEART_RATE_BPM) {
                Log.d(tag, "Availability: $availability")
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val hrPoints = data.getData(DataType.HEART_RATE_BPM)
            for (point in hrPoints.reversed()) {
                val bpm = point.value
                Log.d(tag, "Heart rate: $bpm bpm")
                onHeartRate(bpm)
                onStatus(HeartRateStatus.Streaming)
            }
        }
    }
    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val latestMetrics = update.latestMetrics

            val hrPoints = latestMetrics.getData(DataType.HEART_RATE_BPM)
            for (point in hrPoints.reversed()) {
                val bpm = point.value
                Log.d(tag, "Heart rate: $bpm bpm")
                onHeartRate(bpm)
                onStatus(HeartRateStatus.Streaming)
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            // For ExerciseTypes that support laps, this is called when a lap is marked.
        }

        override fun onRegistered() {
            Log.d(tag, "Exercise Update Registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(tag, "Exercise Update Registration Failed")
        }

        override fun onAvailabilityChanged(
            dataType: DataType<*, *>,
            availability: Availability
        ) {
            if (dataType == DataType.HEART_RATE_BPM) {
                Log.d(tag, "Availability: $availability")
            }
        }
    }

    fun checkCapabilities() {
        onStatus(HeartRateStatus.CheckingCapabilities)

        when (mode) {
            MeasureMode.MeasureClient -> {
                scope.launch {
                    try {
                        val capabilities = measureClient.getCapabilities()
                        val supportsHeartRate =
                            DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure

                        if (supportsHeartRate) {
                            onStatus(HeartRateStatus.SensorSupported)
                        } else {
                            onStatus(HeartRateStatus.SensorNotSupported)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error getting capabilities", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }
            MeasureMode.ExerciseClient -> {
                scope.launch {
                    try {
                        val capabilities = exerciseClient.getCapabilities()
                        if (ExerciseType.RUNNING in capabilities.supportedExerciseTypes) {
                            val exerciseCapabilities =
                                capabilities.getExerciseTypeCapabilities(ExerciseType.STRETCHING)
                            val supportsHeartRate =
                                DataType.HEART_RATE_BPM in exerciseCapabilities.supportedDataTypes

                            if (supportsHeartRate) {
                                onStatus(HeartRateStatus.SensorSupported)
                            } else {
                                onStatus(HeartRateStatus.SensorNotSupported)
                            }
                        } else {
                            onStatus(HeartRateStatus.SensorNotSupported)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error getting capabilities", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }
        }
    }

    fun start() {
        onStatus(HeartRateStatus.Starting)

        when (mode) {
            MeasureMode.MeasureClient -> {
                scope.launch {
                    try {
                        measureClient.registerMeasureCallback(
                            DataType.HEART_RATE_BPM,
                            measureCallback
                        )

                        onStatus(HeartRateStatus.WaitingForData)
                    } catch (e: Exception) {
                        Log.e(tag, "Error registering callback", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }

            MeasureMode.ExerciseClient -> {
                scope.launch {
                    try {
                        val config = ExerciseConfig.Builder(ExerciseType.WORKOUT)
                            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                            .setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS))
                            .build()

                        exerciseClient.setUpdateCallback(exerciseCallback)
                        exerciseClient.startExercise(config)

                        onStatus(HeartRateStatus.WaitingForData)
                    } catch (e: Exception) {
                        Log.e(tag, "Error registering callback", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }
        }
    }

    fun stop() {
        onStatus(HeartRateStatus.Stopping)

        when (mode) {
            MeasureMode.MeasureClient -> {
                scope.launch {
                    try {
                        // Unregister measure callback
                        measureClient.unregisterMeasureCallback(
                            DataType.HEART_RATE_BPM,
                            measureCallback
                        )
                        onStatus(HeartRateStatus.Stopped)
                    } catch (_: CancellationException) {
                        // Scope was cancelled – ignore, we're shutting down anyway
                        Log.d("HeartRateMeasuring", "Scope cancelled during unregister, ignoring")
                        onStatus(HeartRateStatus.Stopped)
                    } catch (e: Exception) {
                        Log.e(tag, "Error unregistering callback", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }
            MeasureMode.ExerciseClient -> {
                scope.launch {
                    try {
                        exerciseClient.endExercise()
                        onStatus(HeartRateStatus.Stopped)
                    } catch (_: CancellationException) {
                        Log.d(tag, "Scope cancelled during stopExercise, ignoring")
                        onStatus(HeartRateStatus.Stopped)
                    } catch (e: Exception) {
                        Log.e(tag, "Error stopping exercise", e)
                        onStatus(HeartRateStatus.Error)
                    }
                }
            }
        }
    }

    fun release() {
        // For now same as stop; handy if you add more cleanup later
        stop()
    }

}