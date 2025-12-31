package com.example.stepcounter3.ui

import android.os.Build
import com.example.stepcounter3.TrailPoint
import com.example.stepcounter3.TrailGenerator
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.CameraPosition
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import com.google.maps.android.compose.Polyline
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.example.stepcounter3.buildGpxXml
import com.example.stepcounter3.extendTrail
import com.example.stepcounter3.saveGpxFile
import com.example.stepcounter3.saveGpxToDownloads
import com.example.stepcounter3.haversineMeters
import java.time.Duration
import java.time.LocalDateTime
import java.lang.Math
import kotlin.math.cos
import kotlin.math.sin


@Composable
fun MapScreen(
    trail: List<TrailPoint>,
    onBack: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState()

    // Move camera to first point when screen opens
    LaunchedEffect(trail) {
        if (trail.isNotEmpty()) {
            val first = trail.first()
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(first.lat, first.lon),
                17f
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState
        ) {

            // Draw polyline (the walking trail)
            if (trail.size > 1) {
                Polyline(
                    points = trail.map { LatLng(it.lat, it.lon) },
                    color = Color.Blue,
                    width = 10f
                )
            }

            // Mark starting point
            trail.firstOrNull()?.let { start ->
                Marker(
                    state = MarkerState(LatLng(start.lat, start.lon)),
                    title = "Start"
                )
            }

            // Mark ending point
            trail.lastOrNull()?.let { end ->
                Marker(
                    state = MarkerState(LatLng(end.lat, end.lon)),
                    title = "End"
                )
            }
        }

        // Back Button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.Black
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StepCounterScreen(
    initialTrail: List<TrailPoint>,
    onTrailUpdated: (List<TrailPoint>, Int) -> Unit,
    initialSteps: Int,
    onOpenMap: () -> Unit,
    totalStepsFlow: MutableStateFlow<Int>,
    previousTotalSteps: Float,
    onReset: () -> Unit,
    onStartSession: () -> Unit,
    onEndSession: (steps: Int, distanceKm: Double, speedKmh: Double) -> Unit,
    isSessionRunningFlow: MutableStateFlow<Boolean>,
    sessionStartTimeFlow: MutableStateFlow<Long>,
    sessionStartStepsFlow: MutableStateFlow<Int>,
    onTrailGenerated: (List<TrailPoint>) -> Unit

) {
    val totalSteps by totalStepsFlow.collectAsState()
    val currentSteps = (totalSteps - previousTotalSteps.toInt()).coerceAtLeast(0)
    val context = LocalContext.current
    val isSessionRunning by isSessionRunningFlow.collectAsState()
    val sessionStartTime by sessionStartTimeFlow.collectAsState()
    val sessionStartSteps by sessionStartStepsFlow.collectAsState()


    var generatedTrail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var elapsedTime by remember { mutableStateOf(0L) }
    var lastSessionSteps by remember { mutableStateOf(0) }
    var lastSessionDistance by remember { mutableStateOf(0.0) }
    var lastSessionSpeed by remember { mutableStateOf(0.0) }
    var lastSessionTrail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var liveTrail by remember { mutableStateOf(initialTrail) }
    var currentLat by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lat ?: 2.9278)
    }
    var currentLon by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lon ?: 101.6419)
    }
    var lastUpdatedSteps by remember { mutableStateOf(0) }
    var lastStepCheckpoint by remember { mutableStateOf(initialSteps) }
    var lastCheckpointTime by remember { mutableStateOf(LocalDateTime.now()) }
    var walkingDirection by remember {
        mutableStateOf(Math.random() * 360)
    }
    val mapTrail =
        if (isSessionRunning) liveTrail else lastSessionTrail



    // Tick every second while session is running
    LaunchedEffect(isSessionRunning) {
        while (isSessionRunning) {
            elapsedTime = (System.currentTimeMillis() - sessionStartTime) / 1000
            delay(1000)
        }
    }
    /*
    LaunchedEffect(isSessionRunning) {
        if (isSessionRunning) {
            lastUpdatedSteps = 0
            liveTrail = emptyList()
        }
    }
    */
    LaunchedEffect(isSessionRunning, totalSteps) {
        if (isSessionRunning && lastStepCheckpoint == 0 && totalSteps > 0) {
            lastStepCheckpoint = totalSteps
        }
    }

    LaunchedEffect(totalSteps, isSessionRunning) {
        if (!isSessionRunning) return@LaunchedEffect

        // Safety check
        if (lastStepCheckpoint == 0) return@LaunchedEffect

        // Handle sensor reset (device reboot)
        if (totalSteps < lastStepCheckpoint) {
            lastStepCheckpoint = totalSteps
            return@LaunchedEffect
        }

        val stepsSinceCheckpoint = totalSteps - lastStepCheckpoint

        // Threshold to update
        if (stepsSinceCheckpoint >= 10) {

            // 1. CATCH UP MODE (App was killed/paused)
            if (stepsSinceCheckpoint > 50) {
                val missedPath = extendTrail(
                    startLat = currentLat,
                    startLon = currentLon,
                    startTime = lastCheckpointTime,
                    steps = stepsSinceCheckpoint
                )
                liveTrail = liveTrail + missedPath

                // Update location to the end of the new path
                if (missedPath.isNotEmpty()) {
                    val last = missedPath.last()
                    currentLat = last.lat
                    currentLon = last.lon
                    lastCheckpointTime = last.time
                }
            }
            // 2. NORMAL WALKING MODE (Live updates)
            else {
                val now = LocalDateTime.now()
                val distanceMeters = stepsSinceCheckpoint * 0.7

                // Drift direction slightly
                walkingDirection += (-5..5).random()
                val rad = Math.toRadians(walkingDirection)

                currentLat += (distanceMeters / 111_320.0) * cos(rad)
                currentLon += (distanceMeters / 111_320.0) * sin(rad)

                val newPoint = TrailPoint(currentLat, currentLon, now)
                liveTrail = liveTrail + newPoint
                lastCheckpointTime = now
            }

            // 3. SAVE STATE
            lastStepCheckpoint = totalSteps
            onTrailUpdated(liveTrail, totalSteps)
        }
    }




    val sessionEndTime = System.currentTimeMillis()
    val totalDurationMillis = sessionEndTime - sessionStartTimeFlow.collectAsState().value
    val totalDurationSeconds = totalDurationMillis / 1000

    // Calculate distance and speed
    val stepLengthMeters = 0.7 // average step length
    val sessionSteps = if (isSessionRunning) totalSteps - sessionStartSteps else 0
    val distanceMeters = sessionSteps * stepLengthMeters
    val distanceKm = distanceMeters / 1000.0
    val speedKmh = if (elapsedTime > 0) distanceMeters / elapsedTime * 3.6 else 0.0
    val averageSpeedMps = if (totalDurationSeconds > 0) distanceMeters / totalDurationSeconds else 0.0
    val averageSpeedKmh = averageSpeedMps * 3.6
    val instantSpeedKmh = remember(liveTrail) {
        if (liveTrail.size < 2) 0.0
        else {
            val p1 = liveTrail[liveTrail.size - 2]
            val p2 = liveTrail.last()

            val dist = haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon)
            val time = Duration.between(p1.time, p2.time).seconds

            if (time > 0) (dist / time) * 3.6 else 0.0
        }
    }
    val exportTrail = when {
        isSessionRunning && liveTrail.isNotEmpty() -> liveTrail
        lastSessionTrail.isNotEmpty() -> lastSessionTrail
        else -> generatedTrail
    }



    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (mapTrail.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {
                    MapScreen(
                        trail = mapTrail,
                        onBack = {}
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }


            // ✅ Session info while running
            if (isSessionRunning) {
                Text("Session Steps: $sessionSteps")
                Text("Distance: %.3f km".format(distanceKm))
                Text("Duration: ${elapsedTime}s")
                Text("Speed: %.2f km/h".format(speedKmh))
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ✅ Last session info after ending
            if (!isSessionRunning && lastSessionSteps > 0) {
                Text("Last Session:")
                Text("Steps: $lastSessionSteps")
                Text("Duration: ${elapsedTime}s")
                Text("Distance: %.3f km".format(lastSessionDistance))
                Text("Average Speed: %.2f km/h".format(lastSessionDistance/(elapsedTime/3600.0)))
                Spacer(modifier = Modifier.height(16.dp))
            }
            /*
            // Current total steps
            Text(
                text = currentSteps.toString(),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        Toast.makeText(
                            context,
                            "Long press to reset",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onLongClick = onReset
                )
            )
            Text(
                text = "/10000",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            LinearProgressIndicator(
                progress = (currentSteps / 10000f).coerceIn(0f, 1f),
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
            )
            */
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        liveTrail = emptyList()
                        lastUpdatedSteps = 0

                        onStartSession()
                    },
                    enabled = !isSessionRunning
                ) { Text("Start") }

                Button(
                    onClick = {
                        // Save last session info
                        lastSessionSteps = sessionSteps
                        lastSessionDistance = distanceKm
                        lastSessionSpeed = instantSpeedKmh
                        lastSessionTrail = liveTrail.toList()

                        onEndSession(sessionSteps, distanceKm, instantSpeedKmh )
                    },
                    enabled = isSessionRunning
                ) { Text("End") }
            }
            Spacer(modifier = Modifier.height(16.dp))


            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (exportTrail.isNotEmpty()) {
                        val gpx = buildGpxXml(
                            points = exportTrail,
                            name = "Indoor Walk"
                        )

                        saveGpxToDownloads(
                            context = context,
                            fileName = "indoor_walk_${System.currentTimeMillis()}.gpx",
                            gpxData = gpx
                        )
                    }
                }
            ) {
                Text("Download GPX")
            }


        }
    }
}

