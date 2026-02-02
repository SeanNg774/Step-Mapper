package com.example.stepcounter3

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.stepcounter3.ui.MapScreen
import com.example.stepcounter3.ui.StepCounterScreen
import com.google.maps.android.compose.MapType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {

    // -------- REAL-TIME FLOWS -------- //
    private val totalStepsFlow = MutableStateFlow(0)
    private val isSessionRunningFlow = MutableStateFlow(false)
    private val sessionStartTimeFlow = MutableStateFlow(0L)
    private val sessionStartStepsFlow = MutableStateFlow(0)

    // step reset baseline
    private var previousTotalSteps = 0f

    // Trail State (Passed between screens)
    private val trailState = mutableStateOf<List<TrailPoint>>(emptyList())

    // -------- SERVICE CONNECTION -------- //
    private var stepService: StepService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StepService.LocalBinder
            stepService = binder.getService()
            isBound = true

            // 🔥 Connect Service Data to UI Flow WITH FIX
            lifecycleScope.launch {
                stepService?.totalSteps?.collect { steps ->

                    // RETROACTIVE FIX:
                    // If the session is running, but the sensor just "woke up" (went from 0 to something),
                    // we need to update the baseline so the session doesn't start with phantom steps.
                    if (isSessionRunningFlow.value && steps > 0) {
                        val currentBaseline = sessionStartStepsFlow.value
                        val startTime = sessionStartTimeFlow.value

                        // If baseline is 0 (or we just started < 2 seconds ago), snap baseline to current steps
                        // This handles the case where you walked 23 steps while app was closed.
                        if (currentBaseline == 0 || (System.currentTimeMillis() - startTime < 2000)) {
                            // Only update if the jump is weird (e.g. we thought it was 0, now it's 23)
                            if (steps > currentBaseline) {
                                sessionStartStepsFlow.value = steps

                                // Save to disk so it persists
                                getSharedPreferences("myPrefs", MODE_PRIVATE).edit {
                                    putInt("sessionStartSteps", steps)
                                }
                            }
                        }
                    }

                    totalStepsFlow.value = steps
                }
            }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    // -------- LIFECYCLE -------- //
    override fun onStart() {
        super.onStart()
        // Bind to the service so we can receive step updates
        Intent(this, StepService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // -------- SESSION LOGIC -------- //
    private fun startSession(resumeSteps: Int = 0, resumeDurationMillis: Long = 0L) {
        val startIntent = Intent(this, StepService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }

        // 🔥 FIX: Get the most accurate current total
        // If the flow is 0 (Service hasn't replied yet), force-load from Prefs
        var currentTotal = totalStepsFlow.value
        if (currentTotal == 0) {
            val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
            val lastSeen = shared.getInt("lastSeenSensorValue", 0)
            val offset = shared.getInt("stepOffset", 0)
            currentTotal = lastSeen + offset
        }

        // Now calculate the baseline using the correct number
        val startTime = System.currentTimeMillis() - resumeDurationMillis
        val startSteps = currentTotal - resumeSteps

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

    private fun endSession(steps: Int, distanceKm: Double, speedKmh: Double, durationMillis: Long) {
        // 1. Stop the Service
        val stopIntent = Intent(this, StepService::class.java)
        stopIntent.action = "STOP_SERVICE"
        startService(stopIntent)

        // 2. Save Data
        val endTime = System.currentTimeMillis()
        isSessionRunningFlow.value = false

        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        shared.edit {
            putBoolean("isSessionRunning", false)
            putLong("sessionEndTime", endTime)
            putInt("sessionSteps", steps)
            putFloat("sessionDistance", distanceKm.toFloat())
            putFloat("sessionSpeed", speedKmh.toFloat())
            putLong("sessionDuration", durationMillis)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveTrailToPrefs(trail: List<TrailPoint>, steps: Int, durationMillis: Long) {
        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        shared.edit {
            putString("savedTrail", trailToString(trail))
            putInt("savedTrailSteps", steps)
            putLong("savedTrailDuration", durationMillis)
        }
    }

    // -------- HELPERS -------- //
    private fun saveBaseline(value: Float) {
        getSharedPreferences("prefs", MODE_PRIVATE)
            .edit { putFloat("baseline", value) }
    }

    private fun loadBaseline(): Float {
        return getSharedPreferences("prefs", MODE_PRIVATE)
            .getFloat("baseline", 0f)
    }

    // -------- ON CREATE -------- //
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load session state
        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        val wasRunning = shared.getBoolean("isSessionRunning", false)
        val savedTrailString = shared.getString("savedTrail", "") ?: ""
        val initialTrail = stringToTrail(savedTrailString)
        val savedTrailSteps = shared.getInt("savedTrailSteps", 0)
        val savedTrailDuration = shared.getLong("savedTrailDuration", 0L)
        val lastFinishedSteps = shared.getInt("sessionSteps", 0)
        val lastFinishedDuration = shared.getLong("sessionDuration", 0L)

        isSessionRunningFlow.value = wasRunning

        val savedStrideLength = shared.getFloat("strideLength", 0.7f).toDouble()
        val defaultLat = shared.getFloat("defaultLat", 2.9278f).toDouble()
        val defaultLon = shared.getFloat("defaultLon", 101.6419f).toDouble()

        if (wasRunning) {
            sessionStartTimeFlow.value = shared.getLong("sessionStartTime", 0)
            sessionStartStepsFlow.value = shared.getInt("sessionStartSteps", 0)
        }

        previousTotalSteps = loadBaseline()

        // Permissions
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                val navController = rememberNavController()

                NavHost(navController, startDestination = "stepCounter") {

                    composable("stepCounter") {
                        StepCounterScreen(
                            initialStrideLength = savedStrideLength,
                            lastFinishedSteps = lastFinishedSteps,
                            lastFinishedDuration = lastFinishedDuration,
                            initialDuration = savedTrailDuration,
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
                            onTrailUpdated = { updatedTrail, currentSteps, currentDuration ->
                                trailState.value = updatedTrail
                                saveTrailToPrefs(updatedTrail, currentSteps, currentDuration)
                            },
                            totalStepsFlow = totalStepsFlow,
                            previousTotalSteps = previousTotalSteps,
                            onReset = {
                                previousTotalSteps = totalStepsFlow.value.toFloat()
                                saveBaseline(previousTotalSteps)
                            },
                            onStartSession = { resumeSteps, resumeDuration ->
                                startSession(resumeSteps, resumeDuration)
                            },
                            onEndSession = { steps, distanceKm, speedKmh, durationMillis ->
                                isSessionRunningFlow.value = false
                                endSession(steps, distanceKm, speedKmh, durationMillis)
                            },
                            isSessionRunningFlow = isSessionRunningFlow,
                            sessionStartTimeFlow = sessionStartTimeFlow,
                            sessionStartStepsFlow = sessionStartStepsFlow,
                            onStrideLengthChanged = { newLength ->
                                shared.edit { putFloat("strideLength", newLength.toFloat()) }
                            },
                            onOpenMap = { navController.navigate("map") },
                            onTrailGenerated = { trail ->
                                trailState.value = trail
                            },
                            onClearSavedData = {
                                shared.edit {
                                    putString("savedTrail", "")
                                    putInt("savedTrailSteps", 0)
                                }
                            }
                        )
                    }

                    composable("map") {
                        var currentMapType by remember { mutableStateOf(MapType.NORMAL) }
                        MapScreen(
                            trail = trailState.value,
                            mapType = currentMapType,
                            onMapTypeToggle = {
                                currentMapType = if (currentMapType == MapType.NORMAL) MapType.HYBRID else MapType.NORMAL
                            }
                        )
                    }
                }
            }
        }
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
                    time = LocalDateTime.ofEpochSecond(parts[2].toLong(), 0, ZoneOffset.UTC)
                )
            } else null
        }
    }
}