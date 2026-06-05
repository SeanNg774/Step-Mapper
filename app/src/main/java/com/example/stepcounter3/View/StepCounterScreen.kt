@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package com.example.stepcounter3.View

import android.annotation.SuppressLint
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
import com.example.stepcounter3.buildGpxXml
import com.example.stepcounter3.saveGpxToDownloads
import com.example.stepcounter3.haversineMeters
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.Alignment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.example.stepcounter3.R
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import androidx.compose.ui.geometry.Offset
import com.example.stepcounter3.parseGpxFile
import com.example.stepcounter3.saveRouteToInternalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.stepcounter3.StepCounterViewModel
import fetchRoadGraph
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.graphics.createBitmap


fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
    val vectorDrawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
    vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val bitmap = createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val canvas = Canvas(bitmap)
    vectorDrawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

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
                mapType =currentMapType,
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

            // Confirm Button
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
    importedRoute: List<TrailPoint> = emptyList(),
    previewMarkerPoint: TrailPoint? = null,
) {
    val cameraPositionState = rememberCameraPositionState()
    var isMapLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(previewMarkerPoint) {
        previewMarkerPoint?.let { point ->
            // We use .move() instead of .animate() so it tracks the slider 1:1 without lagging
            cameraPositionState.move(
                CameraUpdateFactory.newLatLng(
                    LatLng(point.lat, point.lon)
                )
            )
        }
    }

    // Move camera to first point when screen opens
    LaunchedEffect(trail) {
        if (trail.isNotEmpty()) {
            val latestPoint = trail.last()

            // 1. Get the current zoom level
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
    // NEW: Auto-pan to the start of an imported route when it loads
    LaunchedEffect(importedRoute, isMapLoaded) {
        if (importedRoute.isNotEmpty()) {
            val routePrefs = context.getSharedPreferences("RouteSettings", Context.MODE_PRIVATE)
            val sessionStart = routePrefs.getInt("sessionStartIndex", 0)


            val safeIndex = sessionStart.coerceIn(0, importedRoute.size - 1)
            val startPoint = importedRoute[safeIndex]

            if (isMapLoaded) {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngZoom(
                        LatLng(startPoint.lat, startPoint.lon),
                        18f // 18f is a nice tight zoom level
                    ),
                    durationMs = 1500 // Smooth 1.5 second panning animation
                )
            } else {
                cameraPositionState.position =
                    CameraPosition.fromLatLngZoom(
                        LatLng(startPoint.lat, startPoint.lon),
                        18f
                    )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = mapType,
                isMyLocationEnabled = false
            ),
            onMapLoaded = {
                isMapLoaded = true // <--- NEW: Tells our effects the map is ready!
            }
        ) {

            if (importedRoute.size > 1) {
                Polyline(
                    points = importedRoute.map { LatLng(it.lat, it.lon) },
                    // Use a faded gray color so it doesn't distract from the main path
                    color = Color.LightGray.copy(alpha = 0.8f),
                    width = 10f
                )
            }

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
                    title = "End",
                    icon = bitmapDescriptorFromVector(context, R.drawable.outline_flag_24),
                    anchor = Offset(0.2f, 1.0f)
                )
            }

            previewMarkerPoint?.let { point ->
                Marker(
                    state = MarkerState(LatLng(point.lat, point.lon)),
                    title = "Selected Photo Location",
                    // Use a distinct color (like Magenta or Orange) so it stands out from Start/End pins
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA),
                    zIndex = 100f // Forces this pin to draw on top of everything else
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

@SuppressLint("DefaultLocale")
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StepCounterScreen(
    viewModel: StepCounterViewModel,
    initialStrideLength: Double,
    onStrideLengthChanged: (Double) -> Unit,
    initialDuration: Long,
    onClearSavedData: () -> Unit,
    defaultLat: Double,
    defaultLon: Double,
    onSaveStartLocation: (Double, Double) -> Unit,
    initialTrail: List<TrailPoint>,
    onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
    initialSteps: Int,
    onSyncStepBaseline: (Int) -> Unit
) {
    val context = LocalContext.current
    val routePrefs = context.getSharedPreferences("RouteSettings", Context.MODE_PRIVATE)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.initialize(
            context = context,
            defaultLat = defaultLat,
            defaultLon = defaultLon,
            initialTrail = initialTrail,
            initialSteps = initialSteps,
            initialDuration = initialDuration,
            initialStride = initialStrideLength,
            sessionStartSteps = viewModel.sessionStartSteps
        )
    }

// Listen for when the session starts or resumes
    LaunchedEffect(viewModel.isSessionRunning) {
        if (viewModel.isSessionRunning) {
            viewModel.sessionResumedTimestamp = System.currentTimeMillis()
        }
    }


    // Sync checkpoint when sessionStartSteps loads from memory
    LaunchedEffect(viewModel.sessionStartSteps, viewModel.isSessionRunning) {
        // If we are resuming a running session that has no trail yet (just the start point),
        // we must align the checkpoint with the Session Start, otherwise we get a huge "lifetime" trail.
        if (viewModel.isSessionRunning && initialTrail.size <= 1 && viewModel.sessionStartSteps > 0) {
            // Only update if the checkpoint is currently "behind" (e.g., 0)
            if (viewModel.lastStepCheckpoint < viewModel.sessionStartSteps) {
                viewModel.lastStepCheckpoint = viewModel.sessionStartSteps
            }
        }
    }

    val mapTrail =
        if (viewModel.isSessionRunning) viewModel.liveTrail else viewModel.lastSessionTrail

    if (viewModel.isPickingLocation) {
        LocationPickerScreen(
            startLat = viewModel.currentLat,
            startLon = viewModel.currentLon,
            onLocationSelected = { lat, lon ->
                // Update local state
               viewModel.homeLat = lat
               viewModel.homeLon = lon

                viewModel.currentLat = lat
                viewModel.currentLon = lon

                // Save to persistence
                onSaveStartLocation(lat, lon)

                // Close picker
                viewModel.isPickingLocation = false

                // Optional: Clear any old trail if you want a fresh start
                // viewModel.liveTrail = emptyList()
            },
            onCancel = { viewModel.isPickingLocation = false }
        )
        return // Stop rendering the rest of the UI behind the map
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                if (viewModel.isManualTagMode) {
                    // Send everything straight to the slider
                    viewModel.unmatchedUris = uris
                    viewModel.currentQueueIndex = 0 // Reset queue
                    viewModel.showUnmatchedDialog = true
                } else {
                    val trailToUse = viewModel.lastSessionTrail.ifEmpty { viewModel.liveTrail }

                    // Auto-Tagging
                    val result = PhotoTagger.tagAuto(context, trailToUse, uris)

                    //  Handle Results
                    if (result.unmatchedUris.isNotEmpty()) {
                        viewModel.unmatchedUris = result.unmatchedUris
                        viewModel.currentQueueIndex = 0 // NEW: Reset queue
                        viewModel.showUnmatchedDialog = true
                        Toast.makeText(context, "${result.taggedCount} matched. ${result.unmatchedUris.size} photo(s) need manual placement.", Toast.LENGTH_SHORT).show()
                    } else if (result.taggedCount == 0) {
                        Toast.makeText(context, "No photos matched the trail time.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Done! ${result.taggedCount} photos tagged.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    )

    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->

            if (uri != null) {
                try {
                    //  Open the file the user selected
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {

                        //  Pass it to the parser we just built
                        val parsedPoints = parseGpxFile(inputStream)
                        inputStream.close()

                        if (parsedPoints.isNotEmpty()) {
                            viewModel.importedRoute = parsedPoints
                            viewModel.routeTargetIndex = 0
                            viewModel.routeDirection = 1

                            // Save it permanently to the hard drive
                            saveRouteToInternalStorage(context, uri)

                        }

                        // 3. Save it to state and notify the user
                        if (parsedPoints.isNotEmpty()) {
                            viewModel.importedRoute = parsedPoints
                            viewModel.routeTargetIndex = 0
                            viewModel.routeDirection = 1

                            // NEW: Reset memory for the new route!
                            routePrefs.edit {
                                putInt("savedRouteIndex", 0)
                                putInt("savedRouteDirection", 1)
                                putBoolean("hasSelectedRouteBehavior", false)
                                putBoolean("isFollowRoadMode", false)
                            }

                            viewModel.isFollowRoadMode = false // Clear Compose state
                            viewModel.activeRoadGraph = null   // Wipe the RAM map

                            Toast.makeText(context, "Success! Loaded existing trail.", Toast.LENGTH_SHORT).show()

                            // Optional: Instantly snap the map camera to the start of the imported route
                            viewModel.currentLat = parsedPoints.first().lat
                            viewModel.currentLon = parsedPoints.first().lon
                        } else {
                            Toast.makeText(context, "Could not find any GPS points in that file.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error reading GPX file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )


    // If the phone's RAM clears the graph while walking, silently re-download it
    LaunchedEffect(viewModel.isSessionRunning, viewModel.isFollowRoadMode, viewModel.activeRoadGraph) {
        if (viewModel.isSessionRunning && viewModel.isFollowRoadMode && viewModel.activeRoadGraph == null) {
            coroutineScope.launch {
                viewModel.activeRoadGraph = fetchRoadGraph(viewModel.currentLat, viewModel.currentLon, 2000)
            }
        }
    }

    // Tick every second while session is running
    LaunchedEffect(viewModel.isSessionRunning) {
        while (viewModel.isSessionRunning) {
           viewModel.elapsedTime = (System.currentTimeMillis() - viewModel.sessionStartTime) / 1000
            delay(1000)
        }
    }

    LaunchedEffect(viewModel.totalSteps, viewModel.isSessionRunning, viewModel.activeRoadGraph) {
        viewModel.onStepTick(
            totalSteps = viewModel.totalSteps,
            sessionStartSteps = viewModel.sessionStartSteps,
            isSessionRunning = viewModel.isSessionRunning,
            onSyncStepBaseline = onSyncStepBaseline,
            onTrailUpdated = onTrailUpdated,
            sessionStartTime = viewModel.sessionStartTime
        )
    }




    val sessionEndTime = System.currentTimeMillis()
    val totalDurationMillis = sessionEndTime - viewModel.sessionStartTime
    val totalDurationSeconds = totalDurationMillis / 1000

    // Calculate distance and speed
    val sessionSteps = if (viewModel.isSessionRunning && viewModel.totalSteps > 0) {
        (viewModel.totalSteps - viewModel.sessionStartSteps).coerceAtLeast(0)
    } else {
        0
    }
    val distanceMeters = sessionSteps *viewModel.strideLength
    val distanceKm = distanceMeters / 1000.0
    val averageSpeedMps = if (totalDurationSeconds > 0) distanceMeters / totalDurationSeconds else 0.0
    averageSpeedMps * 3.6
    when {
        viewModel.isSessionRunning && viewModel.liveTrail.isNotEmpty() -> viewModel.liveTrail
        viewModel.lastSessionTrail.isNotEmpty() -> viewModel.lastSessionTrail
        else -> viewModel.generatedTrail
    }

    val exportTrail = when {
        viewModel.isSessionRunning && viewModel.liveTrail.isNotEmpty() -> viewModel.liveTrail
        viewModel.lastSessionTrail.isNotEmpty() -> viewModel.lastSessionTrail
        else -> viewModel.generatedTrail
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


                val trailToUseForPreview = if (viewModel.lastSessionTrail.isNotEmpty()) viewModel.lastSessionTrail else viewModel.liveTrail
                val currentPreviewPoint = if (viewModel.showUnmatchedDialog && trailToUseForPreview.isNotEmpty()) {
                    // Grab the exact coordinate matching the current slider index
                    trailToUseForPreview.getOrNull(viewModel.manualTagIndex.toInt())
                } else {
                    null
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    MapScreen(
                        trail = mapTrail,
                        mapType =viewModel.currentMapType, // <--- Pass State
                        onMapTypeToggle = {
                            // Toggle Logic: Normal -> Satellite -> Hybrid -> Normal
                           viewModel.currentMapType = when (viewModel.currentMapType) {
                                MapType.NORMAL -> MapType.HYBRID
                                else -> MapType.NORMAL
                            }
                        },
                        importedRoute = viewModel.importedRoute,
                        previewMarkerPoint = currentPreviewPoint // LINKED

                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
            if (!viewModel.showUnmatchedDialog) {


                //  Session info while running
                if (viewModel.isSessionRunning) {
                    Text("Steps: $sessionSteps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Distance: %.3f km".format(distanceKm),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                //  Last session info after ending
                // Last session info after ending
                if (!viewModel.isSessionRunning && viewModel.lastSessionSteps > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Last Session",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Steps: ${viewModel.lastSessionSteps}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Distance: %.3f km".format(viewModel.lastSessionDistance),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                }


                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        enabled = !viewModel.isSessionRunning,
                        onClick = {
                            // Package all the Start logic into a reusable block
                            val startLogic: () -> Unit = {
                                viewModel.startSession(
                                    context = context,
                                    onClearSavedData = onClearSavedData,
                                    onTrailUpdated = onTrailUpdated)
                            }

                            val hasSelectedBehavior = routePrefs.getBoolean("hasSelectedRouteBehavior", false)
                            if (viewModel.importedRoute.isNotEmpty() && !hasSelectedBehavior) {
                                viewModel.pendingSessionAction = startLogic
                                viewModel.showRouteModeDialog = true
                            } else if (viewModel.importedRoute.isEmpty()) {
                                viewModel.pendingSessionAction = startLogic
                                viewModel.showFreeWalkModeDialog = true
                            } else {
                                startLogic()
                            }
                        }
                    ) { Text("Start") }



                    Button(
                        // Ensure button is only clickable if session is running AND we aren't already generating
                        enabled = viewModel.isSessionRunning && !viewModel.isGeneratingTrail,
                        onClick = {
                            viewModel.endSession(
                                context = context,
                                onTrailUpdated = onTrailUpdated
                            )
                        }
                    ) {
                        if (viewModel.isGeneratingTrail) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                // MaterialTheme ensures it stays visible whether in dark or light mode
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Finalizing...")
                        } else {
                            Text("End")
                        }
                    }
                }

                if (viewModel.isSessionRunning) {
                    Button(
                        onClick = {
                            // 1. URL-Encode the label to prevent strict apps (like Earth) from crashing
                            val label = Uri.encode("Current Location")

                            // 2. Use the official Android standard for dropping a pin.
                            // Google explicitly recommends using "geo:0,0" and putting the actual coordinates in the "q" parameter.
                            val gmmIntentUri =
                                "geo:${viewModel.currentLat},${viewModel.currentLon}?q=${viewModel.currentLat},${viewModel.currentLon}".toUri()
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)

                            // 3. Launch the intent
                            if (mapIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(mapIntent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "No map application installed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text("Look up current location")
                    }



                    if (viewModel.importedRoute.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                // 1. Ensure we don't accidentally restart the session
                                viewModel.pendingSessionAction = null
                                // 2. Pop the dialog open!
                                viewModel.showRouteModeDialog = true
                            }
                        ) {
                            Text("Change Route Behavior")
                        }
                    }



                }






                if (!viewModel.isSessionRunning && viewModel.resumePoint != null && viewModel.totalSteps > 0) {
                    Button(
                        onClick = {
                            viewModel.resumeSession(
                                context = context,
                                totalSteps = viewModel.totalSteps,
                                initialTrail = initialTrail,
                                initialSteps = initialSteps,
                                initialDuration = initialDuration,
                                onClearSavedData = onClearSavedData,
                                onTrailUpdated = onTrailUpdated,
                                onShowToast = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            )
                        }
                    ) { Text(" Resume from Last Trail") }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!viewModel.isSessionRunning) {
                    Button(
                        onClick = { viewModel.isPickingLocation = true }
                    ) {
                        Text(" Set Start Location")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!viewModel.isSessionRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Button 1: Load Route
                        Button(
                            onClick = {
                                // Launch the Android file explorer
                                gpxPickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Load Route")
                        }

                        // Button 2: Only show if a route is currently active
                        if (viewModel.importedRoute.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    // 1. Wipe the UI state
                                    viewModel.clearRoute(context)
                                    Toast.makeText(context, "Route cleared!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Clear Route")
                            }
                        }
                    }

                }


                if (!viewModel.isSessionRunning && (viewModel.lastSessionTrail.isNotEmpty() || initialTrail.isNotEmpty())) {

                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Hold on photo to select multiple",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.isManualTagMode = false // Use EXIF
                                photoPickerLauncher.launch(arrayOf("image/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Auto-Tag Photos")
                        }

                        Button(
                            onClick = {
                                viewModel.isManualTagMode = true // Bypass EXIF, go straight to slider
                                photoPickerLauncher.launch(arrayOf("image/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manual Placement")
                        }
                    }
                    // ---------------------------------
                }


                if (!viewModel.isSessionRunning) {
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
                    Button(
                        onClick = {
                            if (exportTrail.isNotEmpty()) {
                                val gpx = buildGpxXml(points = exportTrail, name = "Indoor Walk")
                                val savedUri = saveGpxToDownloads(context = context, fileName = "indoor_walk_${System.currentTimeMillis()}.gpx", gpxData = gpx)

                                if (savedUri != null) {
                                    Toast.makeText(context, "GPX saved to Downloads!", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                // 2. Prevent opening the browser if there's nothing to upload
                                Toast.makeText(context, "No trail to upload!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val intent = Intent(Intent.ACTION_VIEW,
                                "https://www.strava.com/upload/select".toUri())
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFC4C02))
                    ) {
                        Text("Upload to Strava")
                    }
                }


                if (!viewModel.isSessionRunning) {
                    Button(
                        onClick = { viewModel.showStrideDialog = true }
                    ) {
                        Text("Set Stride Length (default =0.7m)")
                    }

                }
                if (viewModel.showStrideDialog) {
                    var textValue by remember { mutableStateOf(viewModel.strideLength.toString()) }

                    AlertDialog(
                        onDismissRequest = { viewModel.showStrideDialog = false },
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
                                       viewModel.strideLength = newValue
                                        onStrideLengthChanged(newValue) // Save to Prefs
                                        viewModel.showStrideDialog = false
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Invalid number",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.showStrideDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            // --- FR6.2 PROMPT DIALOG ---
            // --- FR6.2 PROMPT DIALOG ---
            if (viewModel.showUnmatchedDialog && viewModel.unmatchedUris.isNotEmpty()) {
                val trailToUse = if (viewModel.lastSessionTrail.isNotEmpty()) viewModel.lastSessionTrail else viewModel.liveTrail
                val maxIndex = (trailToUse.size - 1).coerceAtLeast(0).toFloat()

                // NEW: Get the exact photo we are currently tagging
                val currentPhotoUri = viewModel.unmatchedUris.getOrNull(viewModel.currentQueueIndex)
                val isLastPhoto = viewModel.currentQueueIndex == viewModel.unmatchedUris.size - 1

                // Helper to format time for the slider label
                val previewPoint = trailToUse.getOrNull(viewModel.manualTagIndex.toInt())
                val timeLabel = previewPoint?.time?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "--:--"

                @OptIn(ExperimentalMaterial3Api::class)
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                ModalBottomSheet(
                    onDismissRequest = {
                        viewModel.showUnmatchedDialog = false
                        viewModel.unmatchedUris = emptyList() // Clear memory on dismiss
                    },
                    sheetState = sheetState,
                    scrimColor = Color.Transparent
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        currentPhotoUri?.let { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "Photo to be tagged",
                                contentScale = ContentScale.Crop, // Fills the box nicely without stretching
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp) // Fixed height so it doesn't push the slider off the screen
                                    .padding(vertical = 8.dp)
                                    .clip(RoundedCornerShape(12.dp)) // Nice rounded corners
                            )
                        }

                        // SLIDER to scrub through the walk
                        Slider(
                            value = viewModel.manualTagIndex,
                            onValueChange = { viewModel.manualTagIndex = it },
                            valueRange = 0f..maxIndex,
                            steps = 0
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Selected Time: $timeLabel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Location: ${String.format("%.4f", previewPoint?.lat ?: 0.0)}, ${String.format("%.4f", previewPoint?.lon ?: 0.0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // ACTION BUTTONS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // SKIP BUTTON
                            OutlinedButton(
                                onClick = {
                                    if (isLastPhoto) {
                                        viewModel.showUnmatchedDialog = false
                                        viewModel.unmatchedUris = emptyList()
                                        Toast.makeText(context, "Finished tagging session", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.currentQueueIndex++ // Instantly loads next photo
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Skip")
                            }

                            // APPLY BUTTON
                            Button(
                                onClick = {
                                    // 1. Tag ONLY the current photo in the queue
                                    val targetPoint = trailToUse.getOrElse(viewModel.manualTagIndex.toInt()) { trailToUse.last() }
                                    currentPhotoUri?.let { uri ->
                                        PhotoTagger.tagManual(context, listOf(uri), targetPoint)
                                    }

                                    // 2. Cycle the Queue!
                                    if (isLastPhoto) {
                                        viewModel.showUnmatchedDialog = false
                                        viewModel.unmatchedUris = emptyList()
                                        Toast.makeText(context, "All photos placed!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.currentQueueIndex++ // Instantly loads next photo
                                        Toast.makeText(context, "Tagged photo ${viewModel.currentQueueIndex}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                // Dynamically change the text!
                                Text(if (isLastPhoto) "Apply & Finish" else "Apply & Next")
                            }


                        }
                    }
                }
            }
    }
        if (viewModel.showFreeWalkModeDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!viewModel.isGeneratingTrail) { // Prevent dismissing while map is downloading
                    viewModel.showFreeWalkModeDialog = false
                    viewModel.pendingSessionAction = null
                }
            },
            title = { Text("Choose Walk Mode") },
            text = {
                Column {
                    Text("How would you like to track this walk?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Option 1: Normal Free Walk
                    Button(
                        onClick = {
                            viewModel.isFollowRoadMode = false
                            routePrefs.edit { putBoolean("isFollowRoadMode", false) }

                            viewModel.showFreeWalkModeDialog = false
                            viewModel.pendingSessionAction?.invoke()
                            viewModel.pendingSessionAction = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !viewModel.isGeneratingTrail
                    ) {
                        Text("Random walk")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Option 2: Autonomous Follow Roads
                    Button(
                        onClick = {
                            viewModel.isGeneratingTrail = true

                            coroutineScope.launch {
                                val downloadedGraph = fetchRoadGraph(
                                    centerLat = viewModel.currentLat,
                                    centerLon = viewModel.currentLon,
                                    radiusMeters = 2000
                                )

                                val startIntersection = downloadedGraph.getClosestNode(viewModel.currentLat, viewModel.currentLon)

                                if (startIntersection != null) {
                                    val connectedRoads = downloadedGraph.adjacencyList[startIntersection.id]
                                    val firstTarget = connectedRoads?.randomOrNull()?.targetNodeId ?: -1L

                                    viewModel.followRoadCurrentNode = startIntersection.id
                                    viewModel.followRoadTargetNode = firstTarget
                                    viewModel.followRoadLastNode = -1L

                                    routePrefs.edit {
                                        putBoolean("isFollowRoadMode", true)
                                        putLong("followRoadCurrentNode", startIntersection.id)
                                            .putLong("followRoadTargetNode", firstTarget)
                                            .putLong("followRoadLastNode", -1L)
                                    }

                                    viewModel.isFollowRoadMode = true

                                    // SNAP the starting GPS coordinates perfectly to the intersection!
                                    viewModel.currentLat = startIntersection.lat
                                    viewModel.currentLon = startIntersection.lon

                                    viewModel.activeRoadGraph = downloadedGraph

                                    viewModel.isGeneratingTrail = false
                                    viewModel.showFreeWalkModeDialog = false
                                    viewModel.pendingSessionAction?.invoke()
                                    viewModel.pendingSessionAction = null
                                } else {
                                    withContext(Dispatchers.Main) {
                                        viewModel.isGeneratingTrail = false
                                        Toast.makeText(context, "No roads found! Defaulting to Free Walk.", Toast.LENGTH_LONG).show()
                                        viewModel.isFollowRoadMode = false
                                        viewModel.showFreeWalkModeDialog = false
                                        viewModel.pendingSessionAction?.invoke()
                                        viewModel.pendingSessionAction = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !viewModel.isGeneratingTrail
                    ) {
                        if (viewModel.isGeneratingTrail) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Downloading Map...")
                        } else {
                            Text("Follow Roads")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    viewModel.showFreeWalkModeDialog = false
                    viewModel.pendingSessionAction = null
                }, enabled = !viewModel.isGeneratingTrail) { Text("Cancel") }
            }
        )
    }
        if (viewModel.showRouteModeDialog) {

            val isValidCircuit = remember(viewModel.importedRoute) {
                if (viewModel.importedRoute.isNotEmpty()) {
                    val startPoint = viewModel.importedRoute.first()
                    val endPoint = viewModel.importedRoute.last()
                    val gap = haversineMeters(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
                    gap <= 30.0 // True if the gap is 50 meters or less
                } else {
                    false
                }
            }

            AlertDialog(
                onDismissRequest = {
                    viewModel.showRouteModeDialog = false
                    viewModel.pendingSessionAction = null
                },
                title = { Text("Route End Behavior") },
                text = {
                    Column {
                        Text("What should happen when you reach the end of the trail?", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Option 1: Random
                        Button(
                            onClick = {
                                viewModel.loopRouteBackwards = false
                                viewModel.loopRouteContinuously = false
                                routePrefs.edit {
                                    putBoolean("loopBackwards", false)
                                    putBoolean("loopContinuously", false)
                                    putBoolean("hasSelectedRouteBehavior", true)
                                }
                                viewModel.showRouteModeDialog = false
                                viewModel.pendingSessionAction?.invoke() // Execute the Start/Resume!
                                viewModel.pendingSessionAction = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Generate trail randomly") }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 2: Patrol
                        Button(
                            onClick = {
                                viewModel.loopRouteBackwards = true
                                viewModel.loopRouteContinuously = false
                                routePrefs.edit {
                                    putBoolean("loopBackwards", true)
                                    putBoolean("loopContinuously", false)
                                    putBoolean("hasSelectedRouteBehavior", true)
                                }
                                viewModel.showRouteModeDialog = false
                                viewModel.pendingSessionAction?.invoke()
                                viewModel.pendingSessionAction = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn around and walk back ") }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 3: Circuit
                        Button(
                            onClick = {
                                viewModel.loopRouteBackwards = false
                                viewModel.loopRouteContinuously = true

                                routePrefs.edit {
                                    putBoolean("loopBackwards", false)
                                    putBoolean("loopContinuously", true)
                                    putBoolean("hasSelectedRouteBehavior", true)
                                }
                                viewModel.showRouteModeDialog = false
                                viewModel.pendingSessionAction?.invoke()
                                viewModel.pendingSessionAction = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isValidCircuit
                        ) {
                            if (isValidCircuit) {
                                Text("Loop continuously ")
                            } else {
                                Text("Unable to loop (Gap is over 30m)")
                            }

                        }
                    }
                },
                confirmButton = {}, // Leaving this blank because our options act as the confirm buttons
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.showRouteModeDialog = false
                        viewModel.pendingSessionAction = null
                    }) { Text("Cancel") }
                }
            )
        }
}
}


