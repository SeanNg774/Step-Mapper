package com.example.stepcounter3

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.stepcounter3.ui.StepCounterScreen
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.stepcounter3.ui.MapScreen
import java.time.ZoneOffset
import com.example.stepcounter3.TrailPoint

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    // -------- REAL-TIME FLOWS -------- //
    private val totalStepsFlow = MutableStateFlow(0)
    private val isSessionRunningFlow = MutableStateFlow(false)
    private val sessionStartTimeFlow = MutableStateFlow(0L)
    private val sessionStartStepsFlow = MutableStateFlow(0)

    // step reset baseline
    private var previousTotalSteps = 0f

    // --------------------------------------------------

    private fun startSession() {
        val startTime = System.currentTimeMillis()
        val startSteps = totalStepsFlow.value

        sessionStartTimeFlow.value = startTime
        sessionStartStepsFlow.value = startSteps
        isSessionRunningFlow.value = true

        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        shared.edit {
            putBoolean("isSessionRunning", true)
            putLong("sessionStartTime", startTime)
            putInt("sessionStartSteps", startSteps)
        }
    }

    private fun endSession(steps: Int, distanceKm: Double, speedKmh: Double) {
        val endTime = System.currentTimeMillis()

        isSessionRunningFlow.value = false

        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        shared.edit {
            putBoolean("isSessionRunning", false)
            putLong("sessionEndTime", endTime)
            putInt("sessionSteps", steps)
            putFloat("sessionDistance", distanceKm.toFloat())
            putFloat("sessionSpeed", speedKmh.toFloat())
        }
    }

    private val trailState = mutableStateOf<List<TrailPoint>>(emptyList())
    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveTrailToPrefs(trail: List<TrailPoint>, steps: Int) {
        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        shared.edit {
            putString("savedTrail", trailToString(trail))
            putInt("savedTrailSteps", steps) // <--- Save the step count!
        }
    }
    // --------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load session state if app was killed
        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        val wasRunning = shared.getBoolean("isSessionRunning", false)
        val savedTrailString = shared.getString("savedTrail", "") ?: ""
        val initialTrail = stringToTrail(savedTrailString)
        val savedTrailSteps = shared.getInt("savedTrailSteps", 0)
        isSessionRunningFlow.value = wasRunning

        val defaultLat = shared.getFloat("defaultLat", 2.9278f).toDouble()
        val defaultLon = shared.getFloat("defaultLon", 101.6419f).toDouble()

        if (wasRunning) {
            sessionStartTimeFlow.value = shared.getLong("sessionStartTime", 0)
            sessionStartStepsFlow.value = shared.getInt("sessionStartSteps", 0)
        }

        previousTotalSteps = loadBaseline()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        if (
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        setContent {
            MaterialTheme {

                val navController = rememberNavController()

                NavHost(navController, startDestination = "stepCounter") {

                    composable("stepCounter") {
                        StepCounterScreen(
                            initialTrail = initialTrail,
                            initialSteps = savedTrailSteps,
                            defaultLat = defaultLat,
                            defaultLon = defaultLon,
                            onSaveStartLocation = { lat, lon ->
                                shared.edit {
                                    putFloat("defaultLat", lat.toFloat())
                                    putFloat("defaultLon", lon.toFloat())
                                }
                            },
                            onTrailUpdated = { updatedTrail, currentSteps->
                                trailState.value = updatedTrail
                                saveTrailToPrefs(updatedTrail, currentSteps)
                            },
                            totalStepsFlow = totalStepsFlow,
                            previousTotalSteps = previousTotalSteps,
                            onReset = {
                                previousTotalSteps = totalStepsFlow.value.toFloat()
                                saveBaseline(previousTotalSteps)
                            },
                            onStartSession = { startSession() },
                            onEndSession = { steps, distanceKm, speedKmh ->

                                isSessionRunningFlow.value = false
                                endSession(steps, distanceKm, speedKmh)
                            },
                            isSessionRunningFlow = isSessionRunningFlow,
                            sessionStartTimeFlow = sessionStartTimeFlow,
                            sessionStartStepsFlow = sessionStartStepsFlow,

                            onOpenMap = { navController.navigate("map") },

                            // 🔥 ADD THIS
                            onTrailGenerated = { trail ->
                                trailState.value = trail // save trail
                            }
                        )
                    }

                    composable("map") {
                        // 🔥 Pass the trail to MapScreen
                        MapScreen(
                            trail = trailState.value,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }


        override fun onResume() {
        super.onResume()
        stepSensor?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorListener)
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            totalStepsFlow.value = event.values[0].toInt()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun saveBaseline(value: Float) {
        getSharedPreferences("prefs", MODE_PRIVATE)
            .edit { putFloat("baseline", value) }
    }

    private fun loadBaseline(): Float {
        return getSharedPreferences("prefs", MODE_PRIVATE)
            .getFloat("baseline", 0f)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun trailToString(trail: List<TrailPoint>): String {
        return trail.joinToString(";") { point ->
            "${point.lat},${point.lon},${point.time.toEpochSecond(ZoneOffset.UTC)}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stringToTrail(data: String): List<TrailPoint> {
        if (data.isBlank()) return emptyList()
        return data.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size == 3) {
                TrailPoint(
                    lat = parts[0].toDouble(),
                    lon = parts[1].toDouble(),
                    time = java.time.LocalDateTime.ofEpochSecond(parts[2].toLong(), 0, ZoneOffset.UTC)
                )
            } else null
        }
    }
}
