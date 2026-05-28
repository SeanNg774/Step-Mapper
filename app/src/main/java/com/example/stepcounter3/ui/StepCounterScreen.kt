@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.stepcounter3.ui

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
import java.time.LocalDateTime.now
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.example.stepcounter3.R
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.health.connect.datatypes.ExerciseRoute
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import androidx.compose.ui.geometry.Offset
import com.example.stepcounter3.calculateBearing
import com.example.stepcounter3.clearRouteFromInternalStorage
import com.example.stepcounter3.loadRouteFromInternalStorage
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit


fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
    val vectorDrawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
    vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val bitmap = Bitmap.createBitmap(
        vectorDrawable.intrinsicWidth,
        vectorDrawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    vectorDrawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StravaUploadScreen(onClose: () -> Unit) {
    // This holds the callback from the WebView waiting for the user to pick a file
    var fileChooserCallback: ValueCallback<Array<Uri>>? = remember { null }

    // Android's native file picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data?.data
            if (data != null) {
                // Pass the selected file back to the WebView!
                fileChooserCallback?.onReceiveValue(arrayOf(data))
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
        } else {
            // User cancelled the picker
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null // Reset
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strava GPX Uploader") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close Strava Uploader")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues), // Respects the Top Bar space
            factory = { context ->
                WebView(context).apply {
                    // 1. Enable JavaScript (Strava requires it to load)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // 2. SPOOF DESKTOP CHROME (Tricks Strava into showing the PC layout)
                    val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    settings.userAgentString = desktopUserAgent

                    // Force viewport to behave like a desktop screen
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true

                    // Allow zooming since desktop sites are tiny on phones
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    webViewClient = WebViewClient() // Keeps navigation inside the app

                    // 3. INTERCEPT FILE CHOOSER
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // Save the callback so we can trigger it later
                            fileChooserCallback = filePathCallback

                            // Create an intent to open the phone's file explorer
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                // Strava looks for GPX/FIT/TCX files
                                type = "*/*"
                            }

                            // Launch the native picker!
                            filePickerLauncher.launch(intent)
                            return true
                        }
                    }

                    // 4. Load the direct upload page!
                    loadUrl("https://www.strava.com/upload/select")
                }
            }
        )
    }
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
    // NEW: Auto-pan to the start of an imported route when it loads
    LaunchedEffect(importedRoute, isMapLoaded) {
        if (importedRoute.isNotEmpty()) {
            val routePrefs = context.getSharedPreferences("RouteSettings", Context.MODE_PRIVATE)
            val sessionStart = routePrefs.getInt("sessionStartIndex", 0)


            val safeIndex = sessionStart.coerceIn(0, importedRoute.size - 1)
            val startPoint = importedRoute[safeIndex]

            if (isMapLoaded) {
                cameraPositionState.animate(
                    update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        com.google.android.gms.maps.model.LatLng(startPoint.lat, startPoint.lon),
                        18f // 18f is a nice tight zoom level
                    ),
                    durationMs = 1500 // Smooth 1.5 second panning animation
                )
            } else {
                cameraPositionState.position =
                    com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(
                        com.google.android.gms.maps.model.LatLng(startPoint.lat, startPoint.lon),
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
    onTrailGenerated: (List<TrailPoint>) -> Unit,
    onSyncStepBaseline: (Int) -> Unit,

) {
    val totalSteps by totalStepsFlow.collectAsState()
    val sessionStartSteps by sessionStartStepsFlow.collectAsState()
    (totalSteps - previousTotalSteps.toInt()).coerceAtLeast(0)
    val context = LocalContext.current
    val isSessionRunning by isSessionRunningFlow.collectAsState()
    val sessionStartTime by sessionStartTimeFlow.collectAsState()
    var homeLat by remember { mutableStateOf(defaultLat) }
    var homeLon by remember { mutableStateOf(defaultLon) }
    var isPickingLocation by remember { mutableStateOf(false) }
    var generatedTrail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var elapsedTime by remember { mutableStateOf(0L) }
    var lastSessionSteps by remember { mutableStateOf(initialSteps) }
    var lastSessionDistance by remember { mutableStateOf(initialSteps * initialStrideLength/1000.0) }
    var lastSessionSpeed by remember { mutableStateOf(0.0) }
    var lastSessionTrail by remember { mutableStateOf(initialTrail) }
    val routePrefs = context.getSharedPreferences("RouteSettings", Context.MODE_PRIVATE)
    var importedRoute by remember { mutableStateOf(loadRouteFromInternalStorage(context)) }
    var liveTrail by remember { mutableStateOf(initialTrail) }
    var currentLat by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lat ?: homeLat)
    }
    var currentLon by remember {
        mutableStateOf(initialTrail.lastOrNull()?.lon ?: homeLon)
    }
    var lastCheckpointTime by remember {
        mutableStateOf(initialTrail.lastOrNull()?.time ?: now())
    }
    LaunchedEffect(isSessionRunning, importedRoute) {
        // If the session is running, but RAM wiped the blue line, AND we are on a GPX route...
        if (isSessionRunning && liveTrail.size <= 1 && importedRoute.isNotEmpty()) {
            val savedIndex = routePrefs.getInt("savedRouteIndex", 0)
            val sessionStart = routePrefs.getInt("sessionStartIndex", 0)

            if (savedIndex > 0 && savedIndex >= sessionStart) {
                val safeStart = sessionStart.coerceIn(0, importedRoute.size - 1)
                val safeEnd = savedIndex.coerceIn(0, importedRoute.size - 1)

                liveTrail = importedRoute.subList(safeStart, safeEnd + 1).map { oldPoint ->
                    TrailPoint(oldPoint.lat, oldPoint.lon, now())}
                currentLat = importedRoute[safeEnd].lat
                currentLon = importedRoute[safeEnd].lon
                lastCheckpointTime = liveTrail.lastOrNull()?.time ?: now()
            }
        }
    }
    var lastUpdatedSteps by remember { mutableStateOf(0) }
    // Revert this to the simple version
    var lastStepCheckpoint by remember {
        mutableStateOf(sessionStartSteps + initialSteps)
    }
    var walkingDirection by remember {
        mutableStateOf(Math.random() * 360)
    }
    var strideLength by remember { mutableStateOf(initialStrideLength) }
    var showStrideDialog by remember { mutableStateOf(false) }
    var resumePoint by remember { mutableStateOf(initialTrail.lastOrNull()) }
    var currentMapType by remember { mutableStateOf(MapType.NORMAL) }
    var lastSessionDuration by remember {mutableStateOf(initialDuration)}

    var sessionResumedTimestamp by remember { mutableStateOf(0L) }
    var manualResumeTime by remember { mutableStateOf(0L) }
    var isGeneratingTrail by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope() // Needed to launch background tasks
    var isManualTagMode by remember { mutableStateOf(false) }
    var loopRouteBackwards by remember {
        mutableStateOf(routePrefs.getBoolean("loopBackwards", false))
    }
    var routeDirection by remember {
        mutableStateOf(routePrefs.getInt("savedRouteDirection", 1))
    }
    var loopRouteContinuously by remember {
        mutableStateOf(routePrefs.getBoolean("loopContinuously", false))
    }
    var showUnmatchedDialog by remember { mutableStateOf(false) }
    var unmatchedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentQueueIndex by remember { mutableStateOf(0) } // NEW: Tracks our place in line
    var manualTagIndex by remember { mutableStateOf(0f) } // Float for slider
    var showStravaWebView by remember { mutableStateOf(false) }
    var routeTargetIndex by remember {
        mutableStateOf(routePrefs.getInt("savedRouteIndex", 0))
    }
    var showRouteModeDialog by remember { mutableStateOf(false) }
    var pendingSessionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

// Listen for when the session starts or resumes
    LaunchedEffect(isSessionRunning) {
        if (isSessionRunning) {
            sessionResumedTimestamp = System.currentTimeMillis()
        }
    }
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
                if (isManualTagMode) {
                    // BYPASS EXIF: Send everything straight to the slider
                    unmatchedUris = uris
                    currentQueueIndex = 0 // NEW: Reset queue
                    showUnmatchedDialog = true
                } else {
                    val trailToUse = lastSessionTrail.ifEmpty { liveTrail }

                    // Auto-Tagging
                    val result = PhotoTagger.tagAuto(context, trailToUse, uris)

                    //  Handle Results
                    if (result.unmatchedUris.isNotEmpty()) {
                        unmatchedUris = result.unmatchedUris
                        currentQueueIndex = 0 // NEW: Reset queue
                        showUnmatchedDialog = true
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
                    // 1. Open the file the user selected
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {

                        // 2. Pass it to the parser we just built
                        val parsedPoints = parseGpxFile(inputStream)
                        inputStream.close()

                        if (parsedPoints.isNotEmpty()) {
                            importedRoute = parsedPoints
                            routeTargetIndex = 0
                            routeDirection = 1

                            // NEW: Save it permanently to the hard drive
                            saveRouteToInternalStorage(context, uri)

                        }

                        // 3. Save it to state and notify the user
                        if (parsedPoints.isNotEmpty()) {
                            importedRoute = parsedPoints
                            routeTargetIndex = 0
                            routeDirection = 1

                            // NEW: Reset memory for the new route!
                            routePrefs.edit()
                                .putInt("savedRouteIndex", 0)
                                .putInt("savedRouteDirection", 1)
                                .putBoolean("hasSelectedRouteBehavior", false)
                                .apply()

                            Toast.makeText(context, "Success! Loaded existing trail.", Toast.LENGTH_SHORT).show()

                            // Optional: Instantly snap the map camera to the start of the imported route
                            currentLat = parsedPoints.first().lat
                            currentLon = parsedPoints.first().lon
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

        if (manualResumeTime > 0 && (System.currentTimeMillis() - manualResumeTime < 3000)) {
            val ghostSteps = totalSteps - lastStepCheckpoint

            if (ghostSteps > 0) {
                // Shift the baseline UP to hide these steps from the session total
                onSyncStepBaseline(ghostSteps)
            }
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

            //  CATCH UP(App was killed/paused)
            if (stepsSinceCheckpoint > 50) {
                val estimatedSeconds = (stepsSinceCheckpoint * 0.6).toLong()
                val calculatedEndTime = lastCheckpointTime.plusSeconds(estimatedSeconds)
                val now = now()
                val finalEndTime = if (calculatedEndTime.isAfter(now)) now else calculatedEndTime
                // Generate interpolated trail points for missed steps
                val catchUpResult = extendTrail(
                    startLat = currentLat,
                    startLon = currentLon,
                    startTime = lastCheckpointTime,
                    steps = stepsSinceCheckpoint,
                    stepLengthMeters = strideLength,
                    endTime = finalEndTime,
                    importedRoute = importedRoute,
                    startingWaypointIndex = routeTargetIndex,
                    loopRouteBackwards = loopRouteBackwards,
                    loopRouteContinuously = loopRouteContinuously,// Pass the setting
                    initialRouteDirection = routeDirection   // Pass the direction
                )
                val missedPath = catchUpResult.first
                routeTargetIndex = catchUpResult.second
                routeDirection = catchUpResult.third

                routePrefs.edit()
                    .putInt("savedRouteIndex", routeTargetIndex)
                    .putInt("savedRouteDirection", routeDirection)
                    .apply()

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
                val now = now()
                val distanceMeters = stepsSinceCheckpoint * strideLength


                if (importedRoute.size > 1) {

                    // NEW: Dynamic radius check
                    while (routeTargetIndex >= 0 && routeTargetIndex < importedRoute.size) {
                        val isEndPoint = (routeTargetIndex == 0 || routeTargetIndex == importedRoute.size - 1)

                        // 1. Get our base strictness
                        val baseRadius = if (isEndPoint) 1.5 else 5.0

                        // 2. Widen the net if we are about to make a massive multi-step jump!
                        val hitRadius = baseRadius.coerceAtLeast(distanceMeters).coerceAtMost(8.0)

                        if (haversineMeters(currentLat, currentLon, importedRoute[routeTargetIndex].lat, importedRoute[routeTargetIndex].lon) < hitRadius) {
                            routeTargetIndex += routeDirection

                            routePrefs.edit()
                                .putInt("savedRouteIndex", routeTargetIndex)
                                .putInt("savedRouteDirection", routeDirection)
                                .apply()
                        } else {
                            break // We haven't reached this point yet
                        }}

                    if (routeTargetIndex >= importedRoute.size) {
                        if (loopRouteContinuously) {
                            routeTargetIndex = 0
                            routeDirection = 1
                        } else if (loopRouteBackwards) {
                            routeDirection = -1
                            routeTargetIndex = (importedRoute.size - 2).coerceAtLeast(0)
                        }
                    } else if (routeTargetIndex < 0) {
                        if (loopRouteContinuously) {
                            routeTargetIndex = importedRoute.size - 1
                            routeDirection = -1
                        } else if (loopRouteBackwards) {
                            routeDirection = 1
                            routeTargetIndex = 1.coerceAtMost(importedRoute.size - 1)
                        }
                    }

                    if (routeTargetIndex >= 0 && routeTargetIndex < importedRoute.size) {
                        val target = importedRoute[routeTargetIndex]
                        walkingDirection = calculateBearing(currentLat, currentLon, target.lat, target.lon)
                        walkingDirection += (-2..2).random()
                    } else {
                        walkingDirection += (-10..10).random()
                    }
                } else {
                    walkingDirection += (-10..10).random()
                }
                // Drift direction slightly
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
            val currentSessionSteps = totalSteps - sessionStartStepsFlow.value
            onTrailUpdated(liveTrail, currentSessionSteps, currentDuration)
        }
    }




    val sessionEndTime = System.currentTimeMillis()
    val totalDurationMillis = sessionEndTime - sessionStartTimeFlow.collectAsState().value
    val totalDurationSeconds = totalDurationMillis / 1000

    // Calculate distance and speed
    val sessionSteps = if (isSessionRunning && totalSteps > 0) {
        //.coerceAtLeast(0) forces it to ignore the negative math
        // while waiting for the sensor to load.
        (totalSteps - sessionStartSteps).coerceAtLeast(0)
    } else {
        0
    }
    val distanceMeters = sessionSteps * strideLength
    val distanceKm = distanceMeters / 1000.0
    val speedKmh = if (elapsedTime > 0) distanceMeters / elapsedTime * 3.6 else 0.0
    val averageSpeedMps = if (totalDurationSeconds > 0) distanceMeters / totalDurationSeconds else 0.0
    averageSpeedMps * 3.6
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
    when {
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


                val trailToUseForPreview = if (lastSessionTrail.isNotEmpty()) lastSessionTrail else liveTrail
                val currentPreviewPoint = if (showUnmatchedDialog && trailToUseForPreview.isNotEmpty()) {
                    // Grab the exact coordinate matching the current slider index
                    trailToUseForPreview.getOrNull(manualTagIndex.toInt())
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
                        mapType = currentMapType, // <--- Pass State
                        onMapTypeToggle = {
                            // Toggle Logic: Normal -> Satellite -> Hybrid -> Normal
                            currentMapType = when (currentMapType) {
                                MapType.NORMAL -> MapType.HYBRID
                                else -> MapType.NORMAL
                            }
                        },
                        importedRoute = importedRoute,
                        previewMarkerPoint = currentPreviewPoint // LINKED

                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
            if (!showUnmatchedDialog) {


                //  Session info while running
                if (isSessionRunning) {
                    Text("Steps: $sessionSteps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Distance: %.3f km".format(distanceKm),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                //  Last session info after ending
                // Last session info after ending
                if (!isSessionRunning && lastSessionSteps > 0) {
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
                                "Steps: $lastSessionSteps",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Distance: %.3f km".format(lastSessionDistance),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                }


                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        enabled = !isSessionRunning,
                        onClick = {
                            // Package all the Start logic into a reusable block
                            val startLogic: () -> Unit = {
                                onClearSavedData()

                                val newSessionStart = routeTargetIndex.coerceIn(0, importedRoute.lastIndex.coerceAtLeast(0))
                                routePrefs.edit().putInt("sessionStartIndex", newSessionStart).apply()

                                lastUpdatedSteps = 0
                                if (importedRoute.isNotEmpty()) {
                                    currentLat = importedRoute[newSessionStart].lat
                                    currentLon = importedRoute[newSessionStart].lon
                                } else {
                                    currentLat = homeLat
                                    currentLon = homeLon
                                }

                                val startPoint = TrailPoint(currentLat, currentLon, now())
                                liveTrail = listOf(startPoint)
                                onTrailUpdated(liveTrail, 0, 0L)

                                lastStepCheckpoint = totalSteps
                                lastCheckpointTime = now()
                                manualResumeTime = System.currentTimeMillis()
                                onStartSession(0, 0L)
                            }

                            // The Interceptor
                            val hasSelectedBehavior = routePrefs.getBoolean("hasSelectedRouteBehavior", false)

                            if (importedRoute.isNotEmpty() && !hasSelectedBehavior) {
                                // They have a GPX, but haven't chosen a behavior yet. Ask them!
                                pendingSessionAction = startLogic
                                showRouteModeDialog = true
                            } else if (importedRoute.isEmpty()) {
                                // Optional: If you still have the Free Walk dialog, trigger it here.
                                // Otherwise, just run startLogic()
                                startLogic()
                            } else {
                                // They have a GPX AND they already chose a behavior previously. Just start!
                                startLogic()
                            }

                        }
                    ) { Text("Start") }

                    Button(
                        // Ensure button is only clickable if session is running AND we aren't already generating
                        enabled = isSessionRunning && !isGeneratingTrail,
                        onClick = {
                            isGeneratingTrail = true
                            val exactEndTime = now()

                            // MOVE HEAVY MATH TO BACKGROUND THREAD
                            coroutineScope.launch(Dispatchers.Default) {

                                // Calculate remaining steps using your file's specific state variables
                                val remainingUnprocessedSteps = totalSteps - lastStepCheckpoint

                                val finalPath =
                                    if (remainingUnprocessedSteps > 0 && liveTrail.isNotEmpty()) {
                                        val lastPoint = liveTrail.last()
                                        extendTrail(
                                            startLat = lastPoint.lat,
                                            startLon = lastPoint.lon,
                                            startTime = lastPoint.time,
                                            endTime = exactEndTime,
                                            steps = remainingUnprocessedSteps,
                                            stepLengthMeters = strideLength,
                                            importedRoute = importedRoute,
                                            startingWaypointIndex = routeTargetIndex,
                                            loopRouteBackwards = loopRouteBackwards,
                                            loopRouteContinuously = loopRouteContinuously,// Add this
                                            initialRouteDirection = routeDirection
                                        ).first
                                    } else {
                                        emptyList()
                                    }

                                // SWITCH BACK TO MAIN THREAD TO UPDATE UI
                                withContext(Dispatchers.Main) {
                                    if (finalPath.isNotEmpty()) {
                                        liveTrail = liveTrail + finalPath
                                        // Update state trackers
                                        val veryLast = liveTrail.last()
                                        currentLat = veryLast.lat
                                        currentLon = veryLast.lon
                                        lastCheckpointTime = veryLast.time
                                        lastStepCheckpoint = totalSteps
                                    } else if (liveTrail.isNotEmpty()) {
                                        // EDGE CASE: 0 steps taken, drop final stationary point for EXIF compatibility
                                        val lastPoint = liveTrail.last()
                                        liveTrail = liveTrail + TrailPoint(
                                            lastPoint.lat,
                                            lastPoint.lon,
                                            exactEndTime
                                        )
                                        lastCheckpointTime = exactEndTime
                                    }

                                    if (liveTrail.isNotEmpty()) {
                                        resumePoint = liveTrail.last()
                                    }

                                    // Final metric calculations based on updated trail
                                    val finalSessionSteps =
                                        (totalSteps - sessionStartSteps).coerceAtLeast(0)
                                    val finalDistanceKm =
                                        (finalSessionSteps * strideLength) / 1000.0
                                    val finalDurationMillis = elapsedTime * 1000L

                                    // Save Last Session States
                                    onTrailUpdated(
                                        liveTrail,
                                        finalSessionSteps,
                                        finalDurationMillis
                                    )
                                    lastSessionSteps = finalSessionSteps
                                    lastSessionDistance = finalDistanceKm
                                    lastSessionSpeed = instantSpeedKmh
                                    lastSessionTrail = liveTrail.toList()
                                    lastSessionDuration = finalDurationMillis

                                    routePrefs.edit {
                                        putInt("savedRouteIndex", routeTargetIndex)
                                            .putInt("savedRouteDirection", routeDirection)
                                    }

                                    // Stop loading bar and trigger final callback
                                    isGeneratingTrail = false
                                    onEndSession(
                                        finalSessionSteps,
                                        finalDistanceKm,
                                        instantSpeedKmh,
                                        finalDurationMillis
                                    )
                                }
                            }
                        }
                    ) {
                        if (isGeneratingTrail) {
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

                if (isSessionRunning) {
                    Button(
                        onClick = {
                            // 1. URL-Encode the label to prevent strict apps (like Earth) from crashing
                            val label = Uri.encode("Current Location")

                            // 2. Use the official Android standard for dropping a pin.
                            // Google explicitly recommends using "geo:0,0" and putting the actual coordinates in the "q" parameter.
                            val gmmIntentUri = Uri.parse("geo:$currentLat,$currentLon?q=$currentLat,$currentLon")
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
                    if (importedRoute.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                // 1. Ensure we don't accidentally restart the session
                                pendingSessionAction = null
                                // 2. Pop the dialog open!
                                showRouteModeDialog = true
                            }
                        ) {
                            Text("Change Route Behavior")
                        }
                    }



                }






                if (!isSessionRunning && resumePoint != null && totalSteps > 0) {
                    Button(
                        onClick = {
                            val resumeLogic: () -> Unit = {
                                val historyTrail: List<TrailPoint>
                                val historySteps: Int
                                val historyDuration: Long

                                if (lastSessionTrail.isNotEmpty() && lastSessionSteps > 0) {
                                    historyTrail = lastSessionTrail
                                    historySteps = lastSessionSteps
                                    historyDuration = lastSessionDuration
                                } else {
                                    historyTrail = initialTrail
                                    historySteps = initialSteps
                                    historyDuration = initialDuration
                                }

                                // ---> THE FIX: Use an if/else instead of returning out of the lambda! <---
                                if (historySteps <= 0 || historyTrail.size < 2) {
                                    Toast.makeText(context, "Walk a few steps to get a valid trail", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (historyTrail.size <= 1 && importedRoute.isNotEmpty() && routeTargetIndex > 0) {
                                        val sessionStart = routePrefs.getInt("sessionStartIndex", 0)
                                        val safeStart = sessionStart.coerceIn(0, importedRoute.size - 1)
                                        val safeEnd = routeTargetIndex.coerceIn(0, importedRoute.size - 1)

                                        if (safeEnd >= safeStart) {
                                            liveTrail = importedRoute.subList(safeStart, safeEnd + 1).map { oldPoint ->
                                                TrailPoint(oldPoint.lat, oldPoint.lon, now())
                                            }
                                            currentLat = importedRoute[safeEnd].lat
                                            currentLon = importedRoute[safeEnd].lon
                                        }
                                    } else {
                                        liveTrail = historyTrail
                                        resumePoint?.let { point ->
                                            currentLat = point.lat
                                            currentLon = point.lon
                                        }
                                    }

                                    lastUpdatedSteps = 0
                                    lastStepCheckpoint = totalSteps
                                    lastCheckpointTime = now()
                                    manualResumeTime = System.currentTimeMillis()
                                    onClearSavedData()
                                    onTrailUpdated(liveTrail, historySteps, historyDuration)
                                    onStartSession(historySteps, historyDuration)
                                }
                            }

                            resumeLogic()
                        }
                    ) { Text(" Resume from Last Trail") }
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

                if (!isSessionRunning) {
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
                        if (importedRoute.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    // 1. Wipe the UI state
                                    importedRoute = emptyList()
                                    routeTargetIndex = 0

                                    loopRouteBackwards = false
                                    loopRouteContinuously = false

                                    routePrefs.edit {
                                        putBoolean("loopBackwards", false)
                                            .putBoolean("loopContinuously", false)
                                            putBoolean("hasSelectedRouteBehavior", false)
                                            .putInt(
                                                "savedRouteIndex",
                                                0
                                            ) // NEW: Wipe saved progress
                                            .putInt(
                                                "savedRouteDirection",
                                                1
                                            ) // NEW: Wipe saved direction
                                    }

                                    // 2. Wipe the hard drive cache
                                    clearRouteFromInternalStorage(context)

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


                if (!isSessionRunning && (lastSessionTrail.isNotEmpty() || initialTrail.isNotEmpty())) {

                    Divider()
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
                                isManualTagMode = false // Use EXIF
                                photoPickerLauncher.launch(arrayOf("image/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Auto-Tag Photos")
                        }

                        Button(
                            onClick = {
                                isManualTagMode = true // Bypass EXIF, go straight to slider
                                photoPickerLauncher.launch(arrayOf("image/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manual Placement")
                        }
                    }
                    // ---------------------------------
                }


                if (!isSessionRunning) {
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

                            } else {

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

                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/upload/select"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFC4C02))
                    ) {
                        Text("Upload to Strava")
                    }
                }


                if (!isSessionRunning) {
                    Button(
                        onClick = { showStrideDialog = true }
                    ) {
                        Text("Set Stride Length (default =0.7m)")
                    }

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
                            TextButton(onClick = { showStrideDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            // --- FR6.2 PROMPT DIALOG ---
            // --- FR6.2 PROMPT DIALOG ---
            if (showUnmatchedDialog && unmatchedUris.isNotEmpty()) {
                val trailToUse = if (lastSessionTrail.isNotEmpty()) lastSessionTrail else liveTrail
                val maxIndex = (trailToUse.size - 1).coerceAtLeast(0).toFloat()

                // NEW: Get the exact photo we are currently tagging
                val currentPhotoUri = unmatchedUris.getOrNull(currentQueueIndex)
                val isLastPhoto = currentQueueIndex == unmatchedUris.size - 1

                // Helper to format time for the slider label
                val previewPoint = trailToUse.getOrNull(manualTagIndex.toInt())
                val timeLabel = previewPoint?.time?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "--:--"

                @OptIn(ExperimentalMaterial3Api::class)
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                ModalBottomSheet(
                    onDismissRequest = {
                        showUnmatchedDialog = false
                        unmatchedUris = emptyList() // Clear memory on dismiss
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
                            value = manualTagIndex,
                            onValueChange = { manualTagIndex = it },
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
                                        showUnmatchedDialog = false
                                        unmatchedUris = emptyList()
                                        Toast.makeText(context, "Finished tagging session", Toast.LENGTH_SHORT).show()
                                    } else {
                                        currentQueueIndex++ // Instantly loads next photo
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
                                    val targetPoint = trailToUse.getOrElse(manualTagIndex.toInt()) { trailToUse.last() }
                                    currentPhotoUri?.let { uri ->
                                        PhotoTagger.tagManual(context, listOf(uri), targetPoint)
                                    }

                                    // 2. Cycle the Queue!
                                    if (isLastPhoto) {
                                        showUnmatchedDialog = false
                                        unmatchedUris = emptyList()
                                        Toast.makeText(context, "All photos placed!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        currentQueueIndex++ // Instantly loads next photo
                                        Toast.makeText(context, "Tagged photo ${currentQueueIndex}", Toast.LENGTH_SHORT).show()
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
        if (showRouteModeDialog) {

            val isValidCircuit = remember(importedRoute) {
                if (importedRoute.isNotEmpty()) {
                    val startPoint = importedRoute.first()
                    val endPoint = importedRoute.last()
                    val gap = haversineMeters(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
                    gap <= 30.0 // True if the gap is 50 meters or less
                } else {
                    false
                }
            }

            AlertDialog(
                onDismissRequest = {
                    showRouteModeDialog = false
                    pendingSessionAction = null
                },
                title = { Text("Route End Behavior") },
                text = {
                    Column {
                        Text("What should happen when you reach the end of the trail?", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Option 1: Random
                        Button(
                            onClick = {
                                loopRouteBackwards = false
                                loopRouteContinuously = false
                                routePrefs.edit()
                                    .putBoolean("loopBackwards", false)
                                    .putBoolean("loopContinuously", false)
                                    .putBoolean("hasSelectedRouteBehavior",true)
                                    .apply()
                                showRouteModeDialog = false
                                pendingSessionAction?.invoke() // Execute the Start/Resume!
                                pendingSessionAction = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Generate trail randomly") }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 2: Patrol
                        Button(
                            onClick = {
                                loopRouteBackwards = true
                                loopRouteContinuously = false
                                routePrefs.edit()
                                    .putBoolean("loopBackwards", true)
                                    .putBoolean("loopContinuously", false)
                                    .putBoolean("hasSelectedRouteBehavior",true)
                                    .apply()
                                showRouteModeDialog = false
                                pendingSessionAction?.invoke()
                                pendingSessionAction = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn around and walk back ") }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 3: Circuit
                        Button(
                            onClick = {
                                loopRouteBackwards = false
                                loopRouteContinuously = true

                                routePrefs.edit()
                                    .putBoolean("loopBackwards", false)
                                    .putBoolean("loopContinuously", true)
                                    .putBoolean("hasSelectedRouteBehavior", true)
                                    .apply()
                                showRouteModeDialog = false
                                pendingSessionAction?.invoke()
                                pendingSessionAction = null
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
                        showRouteModeDialog = false
                        pendingSessionAction = null
                    }) { Text("Cancel") }
                }
            )
        }
}
}


