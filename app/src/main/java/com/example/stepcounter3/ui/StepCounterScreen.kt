package com.example.stepcounter3.ui

import android.os.Build
import com.example.stepcounter3.TrailPoint
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.stepcounter3.saveGpxToDownloads
import com.example.stepcounter3.haversineMeters
import java.time.Duration
import java.time.LocalDateTime
import java.lang.Math
import kotlin.math.cos
import kotlin.math.sin
import com.example.stepcounter3.shareGpxFile
import com.google.maps.android.compose.rememberUpdatedMarkerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.stepcounter3.PhotoTagger
import java.time.format.DateTimeFormatter
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.Alignment

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
    var currentMapType by remember { mutableStateOf(MapType.NORMAL) }

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
            onMapClick = { latLng -> updateFromMap(latLng) },
            properties = MapProperties(
                mapType = currentMapType,
                isMyLocationEnabled = false
            ),

        ) {
            Marker(
                state = markerState,
                title = "Selected Location"
            )
        }
        IconButton(
            onClick = {
                // Cycle: Normal -> Hybrid -> Normal
                currentMapType = if (currentMapType == MapType.NORMAL) {
                    MapType.HYBRID
                } else {
                    MapType.NORMAL
                }
            },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.CenterEnd)
                .background(Color.White.copy(alpha = 0.7f), shape = CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Layers, // Or Icons.Default.Layers if available
                contentDescription = "Switch Map Type",
                tint = Color.Black
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
    onMapTypeToggle: () -> Unit,
    mapType: MapType,
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
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = mapType,
                isMyLocationEnabled = false // Set true if you want blue dot (requires permission)
            )
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


        IconButton(
            onClick = onMapTypeToggle,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopEnd)
                .background(Color.White.copy(alpha = 0.7f), shape = CircleShape)
        ) {
            // Pick an icon (Layers is standard, but Info/Settings works too if Layers isn't available)
            Icon(
                // You can use Icons.Default.Layers if available, or just use Info/Settings
                imageVector = Icons.Default.Layers,
                contentDescription = "Switch Map Type",
                tint = Color.Black
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StepCounterScreen(
    initialStrideLength: Double,
    onStrideLengthChanged: (Double) -> Unit,
    lastFinishedSteps: Int,
    lastFinishedDuration: Long,
    initialDuration: Long,
    onClearSavedData: () -> Unit,
    defaultLat: Double,
    defaultLon: Double,
    onSaveStartLocation: (Double, Double) -> Unit,
    initialTrail: List<TrailPoint>,
    onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
    initialSteps: Int,
    onOpenMap: () -> Unit,
    totalStepsFlow: MutableStateFlow<Int>,
    previousTotalSteps: Float,
    onReset: () -> Unit,
    onStartSession: (Int, Long) -> Unit,
    onEndSession: (Int, Double, Double, Long) -> Unit,
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
    var showUnmatchedDialog by remember { mutableStateOf(false) }
    var unmatchedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var manualTagIndex by remember { mutableStateOf(0f) } // Float for slider
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
    // Revert this to the simple version
    var lastStepCheckpoint by remember { mutableStateOf(initialSteps) }
    var lastCheckpointTime by remember { mutableStateOf(LocalDateTime.now()) }
    var walkingDirection by remember {
        mutableStateOf(Math.random() * 360)
    }
    var strideLength by remember { mutableStateOf(initialStrideLength) }
    var showStrideDialog by remember { mutableStateOf(false) }
    var resumePoint by remember { mutableStateOf(initialTrail.lastOrNull()) }
    var currentMapType by remember { mutableStateOf(MapType.NORMAL) }
    var lastSessionDuration by remember {mutableStateOf(0L)}
    // ... variable declarations ...

    // 🔥 NEW: Sync checkpoint when sessionStartSteps loads from memory
    LaunchedEffect(sessionStartSteps, isSessionRunning) {
        // If we are resuming a running session that has no trail yet (just the start point),
        // we must align the checkpoint with the Session Start, otherwise we get a huge "lifetime" trail.
        if (isSessionRunning && initialTrail.size <= 1 && sessionStartSteps > 0) {
            // Only update if the checkpoint is currently "behind" (e.g., 0)
            if (lastStepCheckpoint < sessionStartSteps) {
                lastStepCheckpoint = sessionStartSteps
            }
        }
    }

    // ... existing LaunchedEffect(totalSteps) ...
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
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val trailToUse = if (lastSessionTrail.isNotEmpty()) lastSessionTrail else liveTrail

                // 1. Try Auto-Tagging
                val result = PhotoTagger.tagAuto(context, trailToUse, uris)

                // 2. Handle Results
                if (result.unmatchedUris.isNotEmpty()) {
                    // Trigger the prompt (FR6.2)
                    unmatchedUris = result.unmatchedUris
                    showUnmatchedDialog = true
                    Toast.makeText(context, "${result.taggedCount} matched. ${result.unmatchedUris.size}photo(s) need manual placement.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Done!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )


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
    LaunchedEffect(totalSteps, isSessionRunning) {
        if (!isSessionRunning) return@LaunchedEffect

        // 1. SENSOR WAKE-UP GUARD (The Fix)
        // If the checkpoint is 0 (just started) and the sensor reports real steps (e.g., 23),
        // we assume these are pre-existing steps. Snap to them without drawing.
        if (lastStepCheckpoint == 0 && totalSteps > 0) {
            lastStepCheckpoint = totalSteps
            return@LaunchedEffect
        }

        // 2. SENSOR RESET GUARD (Device reboot)
        if (totalSteps < lastStepCheckpoint) {
            lastStepCheckpoint = totalSteps
            return@LaunchedEffect
        }

        val sessionAge = System.currentTimeMillis() - sessionStartTime
        if (sessionAge < 1000) {
            lastStepCheckpoint = totalSteps
            return@LaunchedEffect
        }

        val stepsSinceCheckpoint = totalSteps - lastStepCheckpoint

        // 3. NORMAL WALKING LOGIC
        // Only draw if we have moved enough steps AND we are past the initialization phase
        if (stepsSinceCheckpoint >= 10) {

            // 1. CATCH UP MODE (App was killed/paused)
            if (stepsSinceCheckpoint > 50) {
                // ... (Keep your existing catch-up logic) ...
                val missedPath = extendTrail(
                    startLat = currentLat,
                    startLon = currentLon,
                    startTime = lastCheckpointTime,
                    steps = stepsSinceCheckpoint,
                    stepLengthMeters = strideLength
                )
                liveTrail = liveTrail + missedPath

                if (missedPath.isNotEmpty()) {
                    val last = missedPath.last()
                    currentLat = last.lat
                    currentLon = last.lon
                    lastCheckpointTime = last.time
                }
            }
            // 2. NORMAL MODE.
            else {
                // ... (Keep your existing normal logic) ...
                val now = LocalDateTime.now()
                val distanceMeters = stepsSinceCheckpoint * strideLength

                // Drift direction slightly
                walkingDirection += (-10..10).random()
                val rad = Math.toRadians(walkingDirection)

                val metersPerDegLat = 111_320.0
                val metersPerDegLon = 111_320.0 * cos(Math.toRadians(currentLat)) // Scale by Latitude

// 2. Apply to coordinates
                currentLat += (distanceMeters / metersPerDegLat) * cos(rad)
                currentLon += (distanceMeters / metersPerDegLon) * sin(rad)

                val newPoint = TrailPoint(currentLat, currentLon, now)
                liveTrail = liveTrail + newPoint
                lastCheckpointTime = now
            }

            // 3. SAVE STATE
            val currentDuration = System.currentTimeMillis() - sessionStartTime
            lastStepCheckpoint = totalSteps
            onTrailUpdated(liveTrail, totalSteps, currentDuration)
        }
    }




    val sessionEndTime = System.currentTimeMillis()
    val totalDurationMillis = sessionEndTime - sessionStartTimeFlow.collectAsState().value
    val totalDurationSeconds = totalDurationMillis / 1000

    // Calculate distance and speed
    val stepLengthMeters = 0.7 // average step length
    val sessionSteps = if (isSessionRunning) totalSteps - sessionStartSteps else 0
    val distanceMeters = sessionSteps * strideLength
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
    val rawTrail = when {
        isSessionRunning && liveTrail.isNotEmpty() -> liveTrail
        lastSessionTrail.isNotEmpty() -> lastSessionTrail
        else -> generatedTrail
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
                        .weight(1f)
                ) {
                    MapScreen(
                        trail = mapTrail,
                        mapType = currentMapType, // <--- Pass State
                        onMapTypeToggle = {
                            // Toggle Logic: Normal -> Satellite -> Hybrid -> Normal
                            currentMapType = when (currentMapType) {
                                MapType.NORMAL -> MapType.HYBRID
                                else -> MapType.NORMAL
                            }
                        },

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
                        // 1. CLEANUP FIRST
                        onClearSavedData()

                        // 2. SETUP VARIABLES
                        lastUpdatedSteps = 0
                        currentLat = homeLat
                        currentLon = homeLon

                        // 3. CREATE START POINT
                        val startPoint = TrailPoint(currentLat, currentLon, LocalDateTime.now())
                        liveTrail = listOf(startPoint)
                        onTrailUpdated(liveTrail, 0, 0L)

                        // 4. RESET CHECKPOINTS
                        lastStepCheckpoint = totalSteps
                        lastCheckpointTime = LocalDateTime.now()

                        // 5. SAVE NEW DATA (Must be after Clear!)


                        // 6. START
                        onStartSession(0, 0L)
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
                        val finalDuration = elapsedTime * 1000L
                        lastSessionDuration = finalDuration // Update local state too


                        onEndSession(sessionSteps, distanceKm, instantSpeedKmh, finalDuration )
                    },
                    enabled = isSessionRunning
                ) { Text("End") }
            }


            Spacer(modifier = Modifier.height(16.dp))

            if (!isSessionRunning && resumePoint != null) {
                Button(
                    onClick = {
                        // A. DETERMINE HISTORY
                        // Did we just click End? Or did we restart the app?
                        val historyTrail = if (lastSessionTrail.isNotEmpty()) lastSessionTrail else initialTrail
                        val historySteps = if (lastSessionSteps > 0) lastSessionSteps else lastFinishedSteps
                        val historyDuration = if (lastSessionDuration > 0) lastSessionDuration else lastFinishedDuration

                        // B. LOAD TRAIL
                        liveTrail = historyTrail

                        // C. SET START POINT
                        resumePoint?.let { point ->
                            currentLat = point.lat
                            currentLon = point.lon
                        }

                        // D. RESET COUNTERS
                        lastUpdatedSteps = 0
                        lastStepCheckpoint = totalSteps
                        lastCheckpointTime = LocalDateTime.now()

                        // E. SAVE STATE IMMEDIATELY
                        onClearSavedData()
                        onTrailUpdated(liveTrail, totalSteps, historyDuration)

                        // F. START WITH RESUME VALUES!
                        // This "backdates" the timer and step counter
                        onStartSession(historySteps, historyDuration)
                    }
                ) {
                    Text(" Resume from Last Trail")
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
            if (!isSessionRunning && (lastSessionTrail.isNotEmpty() || initialTrail.isNotEmpty())) {

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        // Open File Browser filtering for Images
                        photoPickerLauncher.launch(arrayOf("image/*"))
                    }
                ) {
                    Text("Select Photos to embed coordinates")
                }

            }

            if(!isSessionRunning) {
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
                        else {

                        }

                    }
                ) {
                    Text("Download & Share GPX")
                }
            }
            if (!isSessionRunning) {
                // ... Set Start Location Button ...

                // 🔥 NEW BUTTON
                Button(
                    onClick = { showStrideDialog = true }
                ) {
                    Text("Set Stride Length (default =0.7m)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (showStrideDialog) {
                var textValue by remember { mutableStateOf(strideLength.toString()) }

                AlertDialog(
                    onDismissRequest = { showStrideDialog = false },
                    title = { Text("Enter Stride Length (in meters)") },
                    text = {
                        Column {

                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                label = { Text("Meters") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val newValue = textValue.toDoubleOrNull()
                                if (newValue != null && newValue > 0.1 && newValue < 3.0) {
                                    strideLength = newValue
                                    onStrideLengthChanged(newValue) // Save to Prefs
                                    showStrideDialog = false
                                } else {
                                    Toast.makeText(context, "Invalid number", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStrideDialog = false }) { Text("Cancel") }
                    }
                )
            }


            // --- FR6.2 PROMPT DIALOG ---
            if (showUnmatchedDialog) {
                val trailToUse = if (lastSessionTrail.isNotEmpty()) lastSessionTrail else liveTrail
                val maxIndex = (trailToUse.size - 1).toFloat()

                // Helper to format time for the slider label
                val previewPoint = trailToUse.getOrNull(manualTagIndex.toInt())
                val timeLabel = previewPoint?.time?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "--:--"

                AlertDialog(
                    onDismissRequest = { showUnmatchedDialog = false },
                    title = { Text("Unmatched Photos detected") },
                    text = {
                        Column {

                            Text("Select a time/location to place them:", style = MaterialTheme.typography.titleSmall)

                            // SLIDER to scrub through the walk
                            Slider(
                                value = manualTagIndex,
                                onValueChange = { manualTagIndex = it },
                                valueRange = 0f..maxIndex,
                                steps = 0
                            )

                            Text(
                                text = "Selected Time: $timeLabel",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Text(
                                text = "(Coordinate: ${String.format("%.4f", previewPoint?.lat)}, ${String.format("%.4f", previewPoint?.lon)})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                // Apply the manual tag
                                val targetPoint = trailToUse.getOrElse(manualTagIndex.toInt()) { trailToUse.last() }
                                val count = PhotoTagger.tagManual(context, unmatchedUris, targetPoint)

                                Toast.makeText(context, "Done!", Toast.LENGTH_SHORT).show()
                                showUnmatchedDialog = false
                            }
                        ) {
                            Text("Apply to All")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUnmatchedDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
    }
}}

