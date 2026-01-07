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
import com.example.stepcounter3.shareGpxFile
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.google.android.gms.maps.CameraUpdateFactory

@Composable
fun LocationPickerScreen(
    startLat: Double,
    startLon: Double,
    onLocationSelected: (Double, Double) -> Unit,
    onCancel: () -> Unit


) {
    var selectedPos by remember { mutableStateOf(LatLng(startLat, startLon)) }
    var latText by remember { mutableStateOf(startLat.toString()) }
    var lonText by remember { mutableStateOf(startLon.toString()) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(startLat, startLon), 15f)
    }
    val markerState = rememberUpdatedMarkerState(position = selectedPos)

    LaunchedEffect(selectedPos) {
        markerState.position = selectedPos
    }
    fun updateFromMap(pos: LatLng) {
        selectedPos = pos
        // Update text fields to match the tap
        latText = "%.6f".format(pos.latitude)
        lonText = "%.6f".format(pos.longitude)
    }
    fun updateFromText() {
        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()
        if (lat != null && lon != null) {
            val newPos = LatLng(lat, lon)
            selectedPos = newPos
            // Move camera to show the new typed location
            cameraPositionState.move(CameraUpdateFactory.newLatLng(newPos))
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapClick = { latLng -> updateFromMap(latLng) }
        ) {
            Marker(
                state = markerState,
                title = "Selected Location"
            )
        }

        // --- TOP INPUT PANEL ---
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Enter Coordinates or Tap Map", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Latitude Input
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Lat") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        )
                    )
                    // Longitude Input
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Lon") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // "Move to Coordinates" Button
                Button(
                    onClick = { updateFromText() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Move Marker to Coordinates")
                }
            }
        }

        // --- BOTTOM ACTION BUTTONS ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cancel Button
            FilledTonalButton(onClick = onCancel) {
                Text("Cancel")
            }

            // Confirm Button (This actually saves it)
            Button(
                onClick = {
                    onLocationSelected(selectedPos.latitude, selectedPos.longitude)
                }
            ) {
                Text("Confirm Starting Point")
            }
        }
    }
}
@Composable
fun MapScreen(
    trail: List<TrailPoint>,
    onBack: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState()

    // Move camera to first point when screen opens
    LaunchedEffect(trail) {
        if (trail.isNotEmpty()) {
            val latestPoint = trail.last()

            // 1. Get the current zoom level (so we don't reset it)
            // If the map just started (zoom is near 0), default to 17f.
            // Otherwise, respect the user's current zoom.
            val currentZoom = cameraPositionState.position.zoom
            val targetZoom = if (currentZoom < 10f) 17f else currentZoom

            // 2. Move the camera instantly to the new location with the calculated zoom
            // This is "crash-proof" because it uses the State object directly
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(latestPoint.lat, latestPoint.lon),
                targetZoom
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
    defaultLat: Double,
    defaultLon: Double,
    onSaveStartLocation: (Double, Double) -> Unit,
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

    var homeLat by remember { mutableStateOf(defaultLat) }
    var homeLon by remember { mutableStateOf(defaultLon) }
    var isPickingLocation by remember { mutableStateOf(false) }
    var generatedTrail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var elapsedTime by remember { mutableStateOf(0L) }
    var lastSessionSteps by remember { mutableStateOf(0) }
    var lastSessionDistance by remember { mutableStateOf(0.0) }
    var lastSessionSpeed by remember { mutableStateOf(0.0) }
    var lastSessionTrail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var liveTrail by remember { mutableStateOf(initialTrail) }
    var currentLat by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lat ?: homeLat)
    }
    var currentLon by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lon ?: homeLon)
    }
    var lastUpdatedSteps by remember { mutableStateOf(0) }
    var lastStepCheckpoint by remember { mutableStateOf(initialSteps) }
    var lastCheckpointTime by remember { mutableStateOf(LocalDateTime.now()) }
    var walkingDirection by remember {
        mutableStateOf(Math.random() * 360)
    }
    var resumePoint by remember { mutableStateOf(initialTrail.lastOrNull()) }

    val mapTrail =
        if (isSessionRunning) liveTrail else lastSessionTrail

    if (isPickingLocation) {
        LocationPickerScreen(
            startLat = currentLat,
            startLon = currentLon,
            onLocationSelected = { lat, lon ->
                // Update local state
                homeLat = lat
                homeLon = lon

                currentLat = lat
                currentLon = lon

                // Save to persistence
                onSaveStartLocation(lat, lon)

                // Close picker
                isPickingLocation = false

                // Optional: Clear any old trail if you want a fresh start
                // liveTrail = emptyList()
            },
            onCancel = { isPickingLocation = false }
        )
        return // Stop rendering the rest of the UI behind the map
    }


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
                walkingDirection += (-10..10).random()
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
                        //.height(250.dp)
                        .weight(1f)
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

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        liveTrail = emptyList()
                        lastUpdatedSteps = 0

                        currentLat = homeLat
                        currentLon = homeLon

                        onStartSession()
                    },
                    enabled = !isSessionRunning
                ) { Text("Start") }

                Button(
                    onClick = {
                        if (liveTrail.isNotEmpty()) {
                            resumePoint = liveTrail.last()
                        }
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

            if (!isSessionRunning && resumePoint != null) {
                Button(
                    onClick = {
                        liveTrail = emptyList()
                        lastUpdatedSteps = 0

                        // SET START TO PREVIOUS END POINT
                        resumePoint?.let { point ->
                            currentLat = point.lat
                            currentLon = point.lon

                            // Optional: Update map to show where we are resuming
                            homeLat = point.lat // Update home so it doesn't snap back later? (Optional)
                            homeLon = point.lon
                        }

                        onStartSession()
                    }
                ) {
                    Text(" Resume from Last Point")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (!isSessionRunning) {
                Button(
                    onClick = { isPickingLocation = true }
                ) {
                    Text(" Set Start Location")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }



            Button(
                onClick = {
                    if (exportTrail.isNotEmpty()) {
                        val gpx = buildGpxXml(
                            points = exportTrail,
                            name = "Indoor Walk"
                        )

                        val savedUri = saveGpxToDownloads(
                            context = context,
                            fileName = "indoor_walk_${System.currentTimeMillis()}.gpx",
                            gpxData = gpx
                        )
                        if (savedUri != null) {
                            shareGpxFile(context, savedUri)
                        }

                    }
                }
            ) {
                Text("Download & Share GPX")
            }


        }
    }
}

