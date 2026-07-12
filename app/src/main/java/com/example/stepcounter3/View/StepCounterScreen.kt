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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.Alignment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.example.stepcounter3.R
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.view.ViewGroup
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import com.example.stepcounter3.StepCounterViewModel
import fetchRoadGraph
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.stepcounter3.MapProvider
import java.io.File
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import androidx.core.graphics.toColorInt

fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
    val vectorDrawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
    vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val bitmap = createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val canvas = Canvas(bitmap)
    vectorDrawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

fun getHumanReadableDirection(bearing: Double): String {
    val directions = arrayOf("Top", "Top-Right", "Right", "Bottom-Right", "Bottom", "Bottom-Left", "Left", "Top-Left", "Top")
    val index = (((bearing + 22.5) % 360) / 45).toInt()
    return directions[index]
}
@Composable
fun LocationPickerScreen(
    startLat: Double,
    startLon: Double,
    lastRoadBearing: Double,
    mapProvider: MapProvider,             
    mapType: MapType,
    onProviderToggle: () -> Unit,
    onMapTypeToggle: () -> Unit,
    onBearingChanged: (Double) -> Unit,
    onLocationSelected: (Double, Double) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var selectedPos by remember { mutableStateOf(LatLng(startLat, startLon)) }
    var latText by remember { mutableStateOf(startLat.toString()) }
    var lonText by remember { mutableStateOf(startLon.toString()) }

    // (Removed the local currentMapType and currentMapProvider variables)

    remember {
        val osmConfig = Configuration.getInstance()
        osmConfig.load(context, context.getSharedPreferences("osm_prefs", Context.MODE_PRIVATE))
        osmConfig.userAgentValue = context.packageName

        val basePath = File(context.cacheDir, "osmdroid")
        basePath.mkdirs()
        osmConfig.osmdroidBasePath = basePath

        val tileCache = File(osmConfig.osmdroidBasePath, "tile")
        tileCache.mkdirs()
        osmConfig.osmdroidTileCache = tileCache
        true
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(startLat, startLon), 15f)
    }
    val markerState = rememberUpdatedMarkerState(position = selectedPos)

    LaunchedEffect(selectedPos) {
        markerState.position = selectedPos
    }

    fun updateFromMap(pos: LatLng) {
        selectedPos = pos
        latText = "%.6f".format(pos.latitude)
        lonText = "%.6f".format(pos.longitude)
    }

    fun updateFromText() {
        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()
        if (lat != null && lon != null) {
            val newPos = LatLng(lat, lon)
            selectedPos = newPos
            cameraPositionState.move(CameraUpdateFactory.newLatLng(newPos))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- THE MAP SWITCHER ---
        if (mapProvider == MapProvider.GOOGLE) { // <--- USE PARAMETER
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                onMapClick = { latLng -> updateFromMap(latLng) },
                properties = MapProperties(mapType = mapType, isMyLocationEnabled = false) // <--- USE PARAMETER
            ) {
                Marker(
                    state = markerState,
                    title = "Selected Location",
                )
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(18.0)
                        controller.setCenter(GeoPoint(selectedPos.latitude, selectedPos.longitude))

                        val tapReceiver = object : org.osmdroid.events.MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                updateFromMap(LatLng(p.latitude, p.longitude))
                                return true
                            }
                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        }
                        overlays.add(org.osmdroid.views.overlay.MapEventsOverlay(tapReceiver))
                    }
                },
                update = { mapView ->
                    mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Marker }

                    val pickerMarker = org.osmdroid.views.overlay.Marker(mapView)
                    pickerMarker.position = GeoPoint(selectedPos.latitude, selectedPos.longitude)
                    pickerMarker.title = "Selected Location"
                    pickerMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    mapView.overlays.add(pickerMarker)

                    mapView.invalidate()
                }
            )
        }

        // --- SIDE BUTTON PANEL ---
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onProviderToggle, // <--- TRIGGER CALLBACK
                modifier = Modifier.background(Color.White.copy(alpha = 0.7f), shape = CircleShape)
            ) { Icon(Icons.Default.Map, "Switch Map Provider", tint = Color.Black) }

            if (mapProvider == MapProvider.GOOGLE) { // <--- USE PARAMETER
                IconButton(
                    onClick = onMapTypeToggle, // <--- TRIGGER CALLBACK
                    modifier = Modifier.background(Color.White.copy(alpha = 0.7f), shape = CircleShape)
                ) { Icon(Icons.Default.Layers, "Switch Map Type", tint = Color.Black) }
            }
        }

        // --- TOP INPUT PANEL ---
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Enter Coordinates or Tap Map", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText, onValueChange = { latText = it },
                        label = { Text("Lat") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = lonText, onValueChange = { lonText = it },
                        label = { Text("Lon") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { updateFromText() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Move Marker to Coordinates")
                }
            }
        }

        // --- BOTTOM ACTION BUTTONS ---
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FilledTonalButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onLocationSelected(selectedPos.latitude, selectedPos.longitude) }) {
                Text("Confirm Starting Point")
            }
        }
    }
}

@Composable
fun OsmMapView(
    trail: List<TrailPoint>,
    importedRoute: List<TrailPoint>,
    previewMarkerPoint: TrailPoint?,
    trailColor: Int,
    currentLat: Double,
    currentLon: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current


    // Forces OSM to use internal cache instead of asking for restricted SD Card permissions
    remember {
        val osmConfig = Configuration.getInstance()
        osmConfig.load(context, context.getSharedPreferences("osm_prefs", Context.MODE_PRIVATE))
        osmConfig.userAgentValue = context.packageName

        val basePath = File(context.cacheDir, "osmdroid")
        basePath.mkdirs()
        osmConfig.osmdroidBasePath = basePath

        val tileCache = File(osmConfig.osmdroidBasePath, "tile")
        tileCache.mkdirs()
        osmConfig.osmdroidTileCache = tileCache
        true
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            MapView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTileSource(TileSourceFactory.MAPNIK) // Default OSM map style
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(18.0)
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            //  Draw Imported GPX Route (Gray)
            if (importedRoute.isNotEmpty()) {
                val routeOverlay = OsmPolyline(mapView)
                routeOverlay.setPoints(importedRoute.map { GeoPoint(it.lat, it.lon) })
                routeOverlay.outlinePaint.color = android.graphics.Color.LTGRAY
                routeOverlay.outlinePaint.strokeWidth = 10f
                mapView.overlays.add(routeOverlay)
            }

            //  Draw Active Walk Trail (Blue)
            if (trail.isNotEmpty()) {
                val trailOverlay = OsmPolyline(mapView)
                trailOverlay.setPoints(trail.map { GeoPoint(it.lat, it.lon) })
                trailOverlay.outlinePaint.color = trailColor
                trailOverlay.outlinePaint.strokeWidth = 10f
                mapView.overlays.add(trailOverlay)

                // Start Marker
                val startMarker = OsmMarker(mapView)
                startMarker.position = GeoPoint(trail.first().lat, trail.first().lon)
                startMarker.title = "Start"
                startMarker.setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                mapView.overlays.add(startMarker)

                // Current/End Marker
                val endMarker = OsmMarker(mapView)
                endMarker.position = GeoPoint(trail.last().lat, trail.last().lon)
                endMarker.title = "End"
                endMarker.setAnchor(0.2f, 1.0f)


                endMarker.icon = ContextCompat.getDrawable(context, com.example.stepcounter3.R.drawable.outline_flag_24)
                mapView.overlays.add(endMarker)

            }

            //  Draw Photo Preview Marker (Magenta)
            if (previewMarkerPoint != null) {
                val previewMarker = OsmMarker(mapView)
                previewMarker.position = GeoPoint(previewMarkerPoint.lat, previewMarkerPoint.lon)
                previewMarker.title = "Selected Photo"
                previewMarker.setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)

                // ---> FIX 3: ADDED THE MAGENTA TINTED PREVIEW ICON <---
                val magentaIcon = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)?.mutate()
                magentaIcon?.setTint(android.graphics.Color.MAGENTA)
                previewMarker.icon = magentaIcon

                mapView.overlays.add(previewMarker)
                mapView.controller.setCenter(GeoPoint(previewMarkerPoint.lat, previewMarkerPoint.lon))
            } else if (trail.isNotEmpty()) {
                mapView.controller.setCenter(GeoPoint(trail.last().lat, trail.last().lon))
            }else {
                mapView.controller.setCenter(GeoPoint(currentLat, currentLon))
            }

            mapView.invalidate() // Force the map to redraw with new overlays
        }
    )
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun MapScreen(
    trail: List<TrailPoint>,
    onMapTypeToggle: () -> Unit,
    mapType: MapType,
    importedRoute: List<TrailPoint> = emptyList(),
    previewMarkerPoint: TrailPoint? = null,
    mapProvider: MapProvider,
    onProviderToggle: () -> Unit,
    trailColor: Int,
    onColorPickerClick :()-> Unit,
    currentLat: Double,
    currentLon: Double
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(currentLat, currentLon), 17f)
    }
    var isMapLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(previewMarkerPoint) {
        previewMarkerPoint?.let { point ->

            cameraPositionState.move(
                CameraUpdateFactory.newLatLng(
                    LatLng(point.lat, point.lon)
                )
            )
        }
    }
    LaunchedEffect(currentLat, currentLon) {
        if (trail.isEmpty() && currentLat != 0.0) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(currentLat, currentLon),
                17f
            )
        }
    }

    // Move camera to first point when screen opens
    LaunchedEffect(trail) {
        if (trail.isNotEmpty()) {
            val latestPoint = trail.last()

            // Get the current zoom level
            // If the map just started (zoom is near 0), default to 17f.
            // Otherwise, respect the user's current zoom.
            val currentZoom = cameraPositionState.position.zoom
            val targetZoom = if (currentZoom < 10f) 17f else currentZoom

            // Move the camera instantly to the new location with the calculated zoom
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
                        18f
                    ),
                    durationMs = 1500 // 1.5 second panning animation
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


        if (mapProvider == MapProvider.GOOGLE) {

            // Just ONE GoogleMap block
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(mapType = mapType, isMyLocationEnabled = false),
                onMapLoaded = { isMapLoaded = true }
            ) {

                if (importedRoute.size > 1) {
                    Polyline(
                        points = importedRoute.map { LatLng(it.lat, it.lon) },
                        color = Color.LightGray.copy(alpha = 0.8f),
                        width = 10f
                    )
                }

                if (trail.size > 1) {
                    Polyline(
                        points = trail.map { LatLng(it.lat, it.lon) },
                        color = Color(trailColor),
                        width = 10f
                    )
                }

                trail.firstOrNull()?.let { start ->
                    Marker(
                        state = MarkerState(LatLng(start.lat, start.lon)),
                        title = "Start"
                    )
                }

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
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA),
                        zIndex = 100f
                    )
                }
            }
        } else {
            // Draw our new OSM Map!
            OsmMapView(
                trail = trail,
                importedRoute = importedRoute,
                previewMarkerPoint = previewMarkerPoint,
                trailColor = trailColor,
                currentLat = currentLat, // <--- NEW
                currentLon = currentLon, // <--- NEW
                modifier = Modifier.fillMaxSize()
            )
        }

        // --- BUTTON OVERLAYS ---
        // (These stay OUTSIDE the maps so they float safely on top of the UI)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Button 1: Toggle Google vs OSM
            IconButton(
                // We will pass this click up to the main screen to open the dialog
                onClick = onColorPickerClick,
                modifier = Modifier.background(Color.White.copy(alpha = 0.8f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "Change Trail Color",
                    tint = Color.Black
                )
            }
            IconButton(
                onClick = onProviderToggle,
                modifier = Modifier.background(
                    Color.White.copy(alpha = 0.8f),
                    shape = CircleShape
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "Switch to ${if (mapProvider == MapProvider.GOOGLE) "OSM" else "Google"}",
                    tint = Color.Black
                )
            }

            // Button 2: Toggle Map Layers (Only show if Google is active)
            if (mapProvider == MapProvider.GOOGLE) {
                IconButton(
                    onClick = onMapTypeToggle,
                    modifier = Modifier.background(
                        Color.White.copy(alpha = 0.8f),
                        shape = CircleShape
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Switch Map Type",
                        tint = Color.Black
                    )
                }
            }
        }

    }

}@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DirectionPickerScreen(
    viewModel: StepCounterViewModel,
    onDirectionSelected: (Long) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        //  We re-use your awesome MapScreen to fill the background!
        // We pass a single point trail so it draws a pin right at the intersection.
        MapScreen(
            trail = listOf(TrailPoint(viewModel.currentLat, viewModel.currentLon, java.time.LocalDateTime.now())),
            mapType = viewModel.currentMapType,
            onMapTypeToggle = {
                viewModel.currentMapType = when (viewModel.currentMapType) {
                    MapType.NORMAL -> MapType.HYBRID
                    else -> MapType.NORMAL
                }
            },
            importedRoute = viewModel.importedRoute,
            previewMarkerPoint = null,
            mapProvider = viewModel.currentMapProvider,
            onProviderToggle = {
                viewModel.currentMapProvider = if (viewModel.currentMapProvider == MapProvider.GOOGLE) MapProvider.OSM else MapProvider.GOOGLE
            },
            trailColor = viewModel.trailColor,
            onColorPickerClick = { viewModel.showColorDialog = true },
            currentLat = viewModel.currentLat,
            currentLon = viewModel.currentLon
        )

        //  The Direction Options Menu floats safely at the bottom
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Which way do you want to head?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                Spacer(modifier = Modifier.height(16.dp))

                // Render the dynamically generated directions
                viewModel.availableDirections.forEach { (directionText, targetNodeId) ->
                    Button(
                        onClick = { onDirectionSelected(targetNodeId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(" $directionText")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
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

    val executeStartFlow = {

        viewModel.resumePoint?.let { point ->
            viewModel.currentLat = point.lat
            viewModel.currentLon = point.lon

            // Since this is a NEW walk, wipe the historical line off the map
            viewModel.liveTrail = emptyList()
            viewModel.lastSessionTrail = emptyList()

            // Clear the resume point so it doesn't get stuck here
            viewModel.resumePoint = null
        }

        val startLogic: () -> Unit = {
            viewModel.startSession(
                context = context,
                isNewWalk = true,
                onClearSavedData = onClearSavedData,
                onTrailUpdated = onTrailUpdated
            )
        }

        val hasSelectedBehavior =
            routePrefs.getBoolean("hasSelectedRouteBehavior", false)
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
    LaunchedEffect(Unit) {
        viewModel.initialize(
            context = context,
            defaultLat = defaultLat,
            defaultLon = defaultLon,
            initialTrail = initialTrail,
            initialSteps = initialSteps,
            initialDuration = initialDuration,
            initialStride = initialStrideLength,
            sessionStartSteps = viewModel.sessionStartSteps,

            )
        viewModel.loadHistory(context)
    }

// Listen for when the session starts or resumes
    LaunchedEffect(viewModel.isSessionRunning) {
        if (viewModel.isSessionRunning) {
            viewModel.sessionResumedTimestamp = System.currentTimeMillis()
        }
    }


    // Sync checkpoint when sessionStartSteps loads from memory
    LaunchedEffect(viewModel.sessionStartSteps, viewModel.isSessionRunning) {
        if (viewModel.isSessionRunning && initialTrail.size <= 1 && viewModel.sessionStartSteps > 0) {
            // Only update if the checkpoint is currently behind
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
            lastRoadBearing = viewModel.lastRoadBearing,
            onBearingChanged = { newBearing ->
                viewModel.lastRoadBearing = newBearing
                viewModel.routePrefs.edit {
                    putFloat("lastRoadBearing", newBearing.toFloat())
                }
            },
            onLocationSelected = { lat, lon ->
                // Update local state
                viewModel.homeLat = lat
                viewModel.homeLon = lon

                viewModel.currentLat = lat
                viewModel.currentLon = lon

                viewModel.resumePoint = null
                viewModel.lastSessionTrail = emptyList()

                // Save to persistence
                onSaveStartLocation(lat, lon)

                // Close picker
                viewModel.isPickingLocation = false


            },
            onCancel = { viewModel.isPickingLocation = false },
            onProviderToggle = {
                val newProvider = if (viewModel.currentMapProvider == MapProvider.GOOGLE) MapProvider.OSM else MapProvider.GOOGLE
                viewModel.currentMapProvider = newProvider

                viewModel.routePrefs.edit { putString("mapProvider", newProvider.name) }
            },
            onMapTypeToggle = {
                viewModel.currentMapType = if (viewModel.currentMapType == MapType.NORMAL) MapType.HYBRID else MapType.NORMAL
            },
            mapProvider = viewModel.currentMapProvider,
            mapType = viewModel.currentMapType,
        )
        return // Stop rendering the rest of the UI behind the map
    }
    if (viewModel.showDirectionDialog) {
        DirectionPickerScreen(
            viewModel = viewModel,
            onDirectionSelected = { targetNodeId ->
                // Set the chosen targets
                viewModel.followRoadCurrentNode = viewModel.pendingFollowRoadStartNode
                viewModel.followRoadTargetNode = targetNodeId
                viewModel.followRoadLastNode = -1L
                viewModel.isFollowRoadMode = true

                //  Snap coordinates exactly to the intersection
                val startNode = viewModel.activeRoadGraph?.nodes?.get(viewModel.pendingFollowRoadStartNode)
                if (startNode != null) {
                    viewModel.currentLat = startNode.lat
                    viewModel.currentLon = startNode.lon
                }

                //  Save to memory
                routePrefs.edit {
                    putBoolean("isFollowRoadMode", true)
                    putLong("followRoadCurrentNode", viewModel.followRoadCurrentNode)
                    putLong("followRoadTargetNode", viewModel.followRoadTargetNode)
                    putLong("followRoadLastNode", -1L)
                    putFloat("chunkLat", viewModel.currentLat.toFloat())
                    putFloat("chunkLon", viewModel.currentLon.toFloat())
                }

                // 4. Launch the walk!
                viewModel.showDirectionDialog = false
                viewModel.pendingSessionAction?.invoke()
                viewModel.pendingSessionAction = null
            },
            onCancel = {
                viewModel.showDirectionDialog = false
                viewModel.pendingSessionAction = null
            }
        )
        return //  Stop rendering the dashboard while picking a direction
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
                        Toast.makeText(
                            context,
                            "${result.taggedCount} matched. ${result.unmatchedUris.size} photo(s) need manual placement.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (result.taggedCount == 0) {
                        Toast.makeText(
                            context,
                            "No photos matched the trail time.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Done! ${result.taggedCount} photos tagged.",
                            Toast.LENGTH_SHORT
                        ).show()
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
                            viewModel.lastSessionTrail = emptyList()
                            viewModel.lastSessionSteps = 0
                            viewModel.liveTrail = emptyList()


                            // Reset memory for the new route!
                            routePrefs.edit {
                                putInt("savedRouteIndex", 0)
                                putInt("savedRouteDirection", 1)
                                putBoolean("hasSelectedRouteBehavior", false)
                                putBoolean("isFollowRoadMode", false)
                            }

                            viewModel.isFollowRoadMode = false // Clear Compose state
                            viewModel.activeRoadGraph = null   // Wipe the RAM map

                            Toast.makeText(
                                context,
                                "Success! Loaded existing trail.",
                                Toast.LENGTH_SHORT
                            ).show()

                            // snap the map camera to the start of the imported route
                            viewModel.currentLat = parsedPoints.first().lat
                            viewModel.currentLon = parsedPoints.first().lon
                        } else {
                            Toast.makeText(
                                context,
                                "Could not find any GPS points in that file.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error reading GPX file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )


    // If the phone's RAM clears the graph while walking,  re-download it
    LaunchedEffect(viewModel.isSessionRunning, viewModel.isFollowRoadMode) {
        android.util.Log.d("MapPolling", "Heartbeat Started!")

        while (viewModel.isSessionRunning && viewModel.isFollowRoadMode) {


            // always download if map is null
            if (viewModel.activeRoadGraph == null) {

                android.util.Log.d("MapPolling", "Graph is null. Initiating Download...")
                viewModel.isDownloadingGraph = true // Force the UI to show the "Wait..." button

                try {
                    val targetLat =
                        if (viewModel.mapChunkCenterLat != 0.0) viewModel.mapChunkCenterLat else viewModel.currentLat
                    val targetLon =
                        if (viewModel.mapChunkCenterLon != 0.0) viewModel.mapChunkCenterLon else viewModel.currentLon

                    // Force the network call onto the Background (IO) Thread
                    val downloadedGraph =
                        withContext(Dispatchers.IO) {
                            var tempGraph = fetchRoadGraph(targetLat, targetLon, 500)

                            if (tempGraph.adjacencyList.isEmpty()) {
                                android.util.Log.d(
                                    "MapPolling",
                                    "500m empty, expanding to 2000m..."
                                )
                                tempGraph = fetchRoadGraph(targetLat, targetLon, 2000)
                            }

                            tempGraph
                        }

                    if (downloadedGraph.adjacencyList.isNotEmpty()) {
                        android.util.Log.d(
                            "MapPolling",
                            "Success! Graph loaded with ${downloadedGraph.nodes.size} nodes."
                        )
                        viewModel.activeRoadGraph = downloadedGraph
                    } else {
                        android.util.Log.e(
                            "MapPolling",
                            "Failed: Overpass returned 0 roads. Waiting 10s..."
                        )
                        delay(10_000)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MapPolling", "Network Crash! Error: ${e.message}")
                    delay(10_000)
                } finally {
                    viewModel.isDownloadingGraph = false
                }
            }

            delay(1000)
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
    // Calculate distance and speed
    val sessionSteps = if (viewModel.isSessionRunning) {
        if (viewModel.totalSteps > 0) {
            // The sensor is awake! Use the live hardware math.
            (viewModel.totalSteps - viewModel.sessionStartSteps).coerceAtLeast(0)
        } else {
            // The sensor is sleeping after an app restart. Show the memory!
            initialSteps
        }
    } else {
        0
    }
    val distanceMeters = sessionSteps * viewModel.strideLength
    val distanceKm = distanceMeters / 1000.0
    val averageSpeedMps =
        if (totalDurationSeconds > 0) distanceMeters / totalDurationSeconds else 0.0
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

    // Drawer State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val dateFormatter = remember {
        java.text.SimpleDateFormat(
            "MMM dd, yyyy - HH:mm",
            java.util.Locale.getDefault()
        )
    }

    // Wrap the entire screen in the Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Text(
                    text = "Recent Walks",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()

                if (viewModel.walkHistory.isEmpty()) {
                    Text("No history yet.", modifier = Modifier.padding(16.dp), color = Color.Gray)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(viewModel.walkHistory.size) { index ->
                            val item = viewModel.walkHistory[index]

                            // History Item Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                onClick = {
                                    // 1. Load the history data into the UI
                                    viewModel.lastSessionTrail = item.trail
                                    viewModel.lastSessionSteps = item.steps
                                    viewModel.lastSessionDistance = item.distance
                                    viewModel.resumePoint = item.trail.lastOrNull()

                                    // 2. Pan the camera to the loaded trail
                                    viewModel.currentLat =
                                        item.trail.firstOrNull()?.lat ?: viewModel.currentLat
                                    viewModel.currentLon =
                                        item.trail.firstOrNull()?.lon ?: viewModel.currentLon

                                    // 3. Close the drawer
                                    scope.launch { drawerState.close() }
                                }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = dateFormatter.format(java.util.Date(item.timestamp)),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Steps: ${item.steps}")
                                    Text("Distance: %.2f km".format(item.distance))
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {


        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {


                val trailToUseForPreview =
                    viewModel.lastSessionTrail.ifEmpty { viewModel.liveTrail }
                val currentPreviewPoint =
                    if (viewModel.showUnmatchedDialog && trailToUseForPreview.isNotEmpty()) {
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
                        mapType = viewModel.currentMapType,
                        onMapTypeToggle = {
                            // Toggle Logic: Normal -> Satellite -> Hybrid -> Normal
                            viewModel.currentMapType = when (viewModel.currentMapType) {
                                MapType.NORMAL -> MapType.HYBRID
                                else -> MapType.NORMAL
                            }
                        },
                        importedRoute = viewModel.importedRoute,
                        previewMarkerPoint = currentPreviewPoint,
                        mapProvider = viewModel.currentMapProvider,
                        onProviderToggle = {
                            val newProvider = if (viewModel.currentMapProvider == MapProvider.GOOGLE) MapProvider.OSM else MapProvider.GOOGLE
                            viewModel.currentMapProvider = newProvider
                            // ---> NEW: Save to hard drive <---
                            viewModel.routePrefs.edit { putString("mapProvider", newProvider.name) }
                        },
                        trailColor = viewModel.trailColor,
                        onColorPickerClick = {
                            viewModel.showColorDialog = true
                        },
                        currentLat = viewModel.currentLat,
                        currentLon = viewModel.currentLon
                    )
                    if (!viewModel.isSessionRunning) {
                        IconButton(
                            onClick = {

                                scope.launch { drawerState.open() }
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.8f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Walk History",
                                tint = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!viewModel.showUnmatchedDialog) {

                    // =========================================================
                    //  DYNAMIC TOP SECTION: Stats (Live or Last Session)
                    // =========================================================
                    if (viewModel.isSessionRunning) {
                        // --- LIVE STATS (Active State) ---
                        if (viewModel.isCalculatingMassiveRoute) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Calculating route...", color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text("Steps: $sessionSteps", style = MaterialTheme.typography.titleMedium)
                        Text("Distance: %.3f km".format(distanceKm), style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                    } else {
                        // --- LAST SESSION STATS (Idle State) ---
                        if (viewModel.lastSessionSteps > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Last Session", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Steps: ${viewModel.lastSessionSteps}", style = MaterialTheme.typography.bodyLarge)
                                    Text("Distance: %.3f km".format(viewModel.lastSessionDistance), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // =========================================================
                    // PRIMARY CONTROLS: Start & End Buttons (Always visible)
                    // =========================================================
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            enabled = !viewModel.isSessionRunning,
                            onClick = {
                                val hasPreviousData = (viewModel.resumePoint != null && (viewModel.totalSteps > 0 || initialSteps > 0)) || viewModel.lastSessionSteps > 0
                                if (hasPreviousData) {
                                    viewModel.showOverwriteDialog = true
                                } else {
                                    executeStartFlow()
                                }
                            }
                        ) { Text("Start") }

                        Button(
                            enabled = viewModel.isSessionRunning && !viewModel.isGeneratingTrail && !viewModel.isDownloadingGraph && !viewModel.isCalculatingMassiveRoute,
                            onClick = { viewModel.endSession(context = context, onTrailUpdated = onTrailUpdated) }
                        ) {
                            if (viewModel.isGeneratingTrail) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Finalizing...")
                            } else if (viewModel.isDownloadingGraph || viewModel.isCalculatingMassiveRoute) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Wait...")
                            } else {
                                Text("End")
                            }
                        }
                    }

                    if (viewModel.showOverwriteDialog) {
                        AlertDialog(
                            onDismissRequest = { viewModel.showOverwriteDialog = false },
                            title = { Text("Start New Walk?") },
                            text = { Text("Starting a new walk will clear your previous un-exported trail. Are you sure you want to overwrite it?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.showOverwriteDialog = false
                                        executeStartFlow()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text("Overwrite & Start") }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.showOverwriteDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // =========================================================
                    //  SECONDARY CONTROLS (Split by State)
                    // =========================================================
                    if (viewModel.isSessionRunning) {
                        // --- ACTIVE WORKOUT CONTROLS ---
                        Button(onClick = {
                            val gmmIntentUri = "geo:${viewModel.currentLat},${viewModel.currentLon}?q=${viewModel.currentLat},${viewModel.currentLon}".toUri()
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                            if (mapIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(mapIntent)
                            } else {
                                Toast.makeText(context, "No map application installed", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Look up current location") }

                    } else {
                        // --- IDLE MENU CONTROLS ---
                        if (viewModel.resumePoint != null) {
                            Button(onClick = {
                                viewModel.resumeSession(
                                    context = context, totalSteps = viewModel.totalSteps, initialTrail = initialTrail,
                                    initialSteps = initialSteps, initialDuration = initialDuration,
                                    onClearSavedData = onClearSavedData, onTrailUpdated = onTrailUpdated,
                                    onShowToast = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                                )
                            }) { Text(" Resume from Last Trail") }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        val hasImportedRoute = viewModel.importedRoute.isNotEmpty()
                        Button(
                            onClick = {
                                if (hasImportedRoute) {
                                    Toast.makeText(context, "Route needs to be cleared first", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.isPickingLocation = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasImportedRoute) Color.LightGray else MaterialTheme.colorScheme.primary,
                                contentColor = if (hasImportedRoute) Color.DarkGray else MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(" Set Start Location") }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!hasImportedRoute) {
                                Button(onClick = { gpxPickerLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Load Route") }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.clearRoute(context)
                                        Toast.makeText(context, "Route cleared!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text("Clear Route") }
                            }
                        }

                        if (viewModel.lastSessionTrail.isNotEmpty() || initialTrail.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            Text("Hold on photo to select multiple", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.isManualTagMode = false
                                        photoPickerLauncher.launch(arrayOf("image/*"))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Auto-Tag Photos") }

                                Button(
                                    onClick = {
                                        viewModel.isManualTagMode = true
                                        photoPickerLauncher.launch(arrayOf("image/*"))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Manual Placement") }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = {
                                if (exportTrail.isNotEmpty()) {
                                    val gpx = buildGpxXml(points = exportTrail, name = "Indoor Walk")
                                    val savedUri = saveGpxToDownloads(context = context, fileName = "indoor_walk_${System.currentTimeMillis()}.gpx", gpxData = gpx)
                                    if (savedUri != null) shareGpxFile(context, savedUri)
                                } else {
                                    Toast.makeText(context, "No trail to export!", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Share GPX") }

                            Button(
                                onClick = {
                                    if (exportTrail.isNotEmpty()) {
                                        val gpx = buildGpxXml(points = exportTrail, name = "Indoor Walk")
                                        val savedUri = saveGpxToDownloads(context = context, fileName = "indoor_walk_${System.currentTimeMillis()}.gpx", gpxData = gpx)
                                        if (savedUri != null) {
                                            Toast.makeText(context, "GPX saved to Downloads!", Toast.LENGTH_LONG).show()
                                            val intent = Intent(Intent.ACTION_VIEW, "https://www.strava.com/upload/select".toUri())
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        Toast.makeText(context, "No trail to upload!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFC4C02))
                            ) { Text("Upload to Strava") }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.showStrideDialog = true }) { Text("Set Stride Length (default = 0.7m)") }
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
                                TextButton(onClick = { viewModel.showStrideDialog = false }) {
                                    Text(
                                        "Cancel"
                                    )
                                }
                            }
                        )
                    }
                }


                if (viewModel.showUnmatchedDialog && viewModel.unmatchedUris.isNotEmpty()) {
                    val trailToUse =
                        if (viewModel.lastSessionTrail.isNotEmpty()) viewModel.lastSessionTrail else viewModel.liveTrail
                    val maxIndex = (trailToUse.size - 1).coerceAtLeast(0).toFloat()

                    // NEW: Get the exact photo we are currently tagging
                    val currentPhotoUri =
                        viewModel.unmatchedUris.getOrNull(viewModel.currentQueueIndex)
                    val isLastPhoto =
                        viewModel.currentQueueIndex == viewModel.unmatchedUris.size - 1

                    // Helper to format time for the slider label
                    val previewPoint = trailToUse.getOrNull(viewModel.manualTagIndex.toInt())
                    val timeLabel =
                        previewPoint?.time?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                            ?: "--:--"

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
                                text = "Location: ${
                                    String.format(
                                        "%.4f",
                                        previewPoint?.lat ?: 0.0
                                    )
                                }, ${String.format("%.4f", previewPoint?.lon ?: 0.0)}",
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
                                            Toast.makeText(
                                                context,
                                                "Finished tagging session",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                                        val targetPoint =
                                            trailToUse.getOrElse(viewModel.manualTagIndex.toInt()) { trailToUse.last() }
                                        currentPhotoUri?.let { uri ->
                                            PhotoTagger.tagManual(context, listOf(uri), targetPoint)
                                        }

                                        // 2. Cycle the Queue!
                                        if (isLastPhoto) {
                                            viewModel.showUnmatchedDialog = false
                                            viewModel.unmatchedUris = emptyList()
                                            Toast.makeText(
                                                context,
                                                "All photos placed!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            viewModel.currentQueueIndex++ // Instantly loads next photo
                                            Toast.makeText(
                                                context,
                                                "Tagged photo ${viewModel.currentQueueIndex}",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                            Text(
                                "How would you like to track this walk?",
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                                    viewModel.currentMapProvider = MapProvider.OSM

                                    coroutineScope.launch {
                                        var successfulRadius = 500
                                        var downloadedGraph = withContext(Dispatchers.IO)
                                        {
                                            fetchRoadGraph(
                                                centerLat = viewModel.currentLat,
                                                centerLon = viewModel.currentLon,
                                                radiusMeters = successfulRadius
                                            )
                                        }
                                        if (downloadedGraph.nodes.size < 20) {
                                            successfulRadius = 2000
                                            downloadedGraph = withContext(Dispatchers.IO) {
                                                fetchRoadGraph(
                                                    centerLat = viewModel.currentLat,
                                                    centerLon = viewModel.currentLon,
                                                    radiusMeters = successfulRadius
                                                )
                                            }
                                        }

                                        val startIntersection = downloadedGraph.getClosestNode(
                                            viewModel.currentLat,
                                            viewModel.currentLon
                                        )

                                        if (startIntersection != null) {
                                            viewModel.currentChunkRadius = successfulRadius
                                            viewModel.mapChunkCenterLat = viewModel.currentLat
                                            viewModel.mapChunkCenterLon = viewModel.currentLon
                                            routePrefs.edit {
                                                putInt(
                                                    "chunkRadius",
                                                    successfulRadius
                                                )
                                            }

                                            val connectedRoads = downloadedGraph.adjacencyList[startIntersection.id] ?: emptyList()

                                            // ---> NEW: Intercept and ask for direction! <---
                                            if (connectedRoads.size > 1) {
                                                // Calculate the direction of every connected road
                                                viewModel.availableDirections = connectedRoads.mapNotNull { edge ->
                                                    val targetNode = downloadedGraph.nodes[edge.targetNodeId]
                                                    if (targetNode != null) {
                                                        val bearing = com.example.stepcounter3.calculateBearing(
                                                            startIntersection.lat, startIntersection.lon,
                                                            targetNode.lat, targetNode.lon
                                                        )
                                                        val dirName = getHumanReadableDirection(bearing)
                                                        Pair("$dirName (${bearing.toInt()}°)", edge.targetNodeId)
                                                    } else null
                                                }

                                                viewModel.pendingFollowRoadStartNode = startIntersection.id
                                                viewModel.activeRoadGraph = downloadedGraph

                                                // Swap the dialogs!
                                                viewModel.isGeneratingTrail = false
                                                viewModel.showFreeWalkModeDialog = false
                                                viewModel.showDirectionDialog = true

                                            } else {
                                                // Fallback: If it's a dead end (only 1 road), just start automatically
                                                val firstTarget = connectedRoads.firstOrNull()?.targetNodeId ?: -1L
                                                viewModel.followRoadCurrentNode = startIntersection.id
                                                viewModel.followRoadTargetNode = firstTarget
                                                viewModel.followRoadLastNode = -1L

                                                routePrefs.edit {
                                                    putBoolean("isFollowRoadMode", true)
                                                    putLong("followRoadCurrentNode", startIntersection.id)
                                                    putLong("followRoadTargetNode", firstTarget)
                                                    putLong("followRoadLastNode", -1L)
                                                    putFloat("chunkLat", viewModel.currentLat.toFloat())
                                                    putFloat("chunkLon", viewModel.currentLon.toFloat())
                                                }

                                                viewModel.isFollowRoadMode = true
                                                viewModel.currentLat = startIntersection.lat
                                                viewModel.currentLon = startIntersection.lon
                                                viewModel.activeRoadGraph = downloadedGraph

                                                viewModel.isGeneratingTrail = false
                                                viewModel.showFreeWalkModeDialog = false
                                                viewModel.pendingSessionAction?.invoke()
                                                viewModel.pendingSessionAction = null
                                            }
                                        } else {
                                            // ... your existing 'No roads found' fallback ...else {
                                            withContext(Dispatchers.Main) {
                                                viewModel.isGeneratingTrail = false
                                                Toast.makeText(
                                                    context,
                                                    "No roads found! Defaulting to Free Walk.",
                                                    Toast.LENGTH_LONG
                                                ).show()
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
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
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
            if (viewModel.showDirectionDialog) {
                AlertDialog(
                    onDismissRequest = {
                        viewModel.showDirectionDialog = false
                        viewModel.pendingSessionAction = null
                    },
                    title = { Text("Choose Starting Direction") },
                    text = {
                        Column {
                            Text(
                                " Which way do you want to head?",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Create a button for every available direction
                            viewModel.availableDirections.forEach { (directionText, targetNodeId) ->
                                Button(
                                    onClick = {
                                        // 1. Set the chosen targets
                                        viewModel.followRoadCurrentNode = viewModel.pendingFollowRoadStartNode
                                        viewModel.followRoadTargetNode = targetNodeId
                                        viewModel.followRoadLastNode = -1L
                                        viewModel.isFollowRoadMode = true

                                        // 2. Snap coordinates exactly to the intersection
                                        val startNode = viewModel.activeRoadGraph?.nodes?.get(viewModel.pendingFollowRoadStartNode)
                                        if (startNode != null) {
                                            viewModel.currentLat = startNode.lat
                                            viewModel.currentLon = startNode.lon
                                        }

                                        // 3. Save to hard drive
                                        routePrefs.edit {
                                            putBoolean("isFollowRoadMode", true)
                                            putLong("followRoadCurrentNode", viewModel.followRoadCurrentNode)
                                            putLong("followRoadTargetNode", viewModel.followRoadTargetNode)
                                            putLong("followRoadLastNode", -1L)
                                            putFloat("chunkLat", viewModel.currentLat.toFloat())
                                            putFloat("chunkLon", viewModel.currentLon.toFloat())
                                        }

                                        // 4. Launch the Walk!
                                        viewModel.showDirectionDialog = false
                                        viewModel.pendingSessionAction?.invoke()
                                        viewModel.pendingSessionAction = null
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Text("Head $directionText")
                                }
                            }
                        }
                    },
                    confirmButton = {}, // Blank because the options act as the buttons
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.showDirectionDialog = false
                            viewModel.pendingSessionAction = null
                        }) { Text("Cancel") }
                    }
                )
            }
            if (viewModel.showRouteModeDialog) {

                val isValidCircuit = remember(viewModel.importedRoute) {
                    if (viewModel.importedRoute.isNotEmpty()) {
                        val startPoint = viewModel.importedRoute.first()
                        val endPoint = viewModel.importedRoute.last()
                        val gap = haversineMeters(
                            startPoint.lat,
                            startPoint.lon,
                            endPoint.lat,
                            endPoint.lon
                        )
                        gap <= 30.0 // True if the gap is 30 meters or less
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
                            Text(
                                "What should happen when you reach the end of the trail?",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Option 1: Random
                            Button(
                                onClick = {
                                    viewModel.updateRouteBehavior(
                                        newLoopBackwards = false,
                                        newLoopContinuously = false,
                                        newFollowRoadAfterGpx = false
                                    )
                                    viewModel.showRouteModeDialog = false
                                    viewModel.pendingSessionAction?.invoke()
                                    viewModel.pendingSessionAction = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Generate trail randomly") }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Option 2: Patrol
                            Button(
                                onClick = {
                                    viewModel.updateRouteBehavior(
                                        newLoopBackwards = true,
                                        newLoopContinuously = false,
                                        newFollowRoadAfterGpx = false
                                    )
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
                                    viewModel.updateRouteBehavior(
                                        newLoopBackwards = false,
                                        newLoopContinuously = true,
                                        newFollowRoadAfterGpx = false
                                    )
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

                            Spacer(modifier = Modifier.height(8.dp))

                            // Option 4: Follow Roads After GPX
                            Button(
                                onClick = {
                                    viewModel.isGeneratingTrail = true
                                    viewModel.currentMapProvider = MapProvider.OSM

                                    coroutineScope.launch {
                                        // Grab the finish line of the GPX route
                                        val endPoint = viewModel.importedRoute.last()

                                        // Pre-download the map around the finish line
                                        var successfulRadius = 500
                                        var downloadedGraph = withContext(Dispatchers.IO) {
                                            fetchRoadGraph(

                                                centerLat = endPoint.lat,
                                                centerLon = endPoint.lon,
                                                radiusMeters = successfulRadius
                                            )
                                        }

                                        if (downloadedGraph.nodes.size < 20) {
                                            successfulRadius = 2000
                                            downloadedGraph = withContext(Dispatchers.IO) {
                                                fetchRoadGraph(
                                                    centerLat = endPoint.lat,
                                                    centerLon = endPoint.lon,
                                                    radiusMeters = successfulRadius
                                                )
                                            }
                                        }

                                        val startIntersection = downloadedGraph.getClosestNode(
                                            endPoint.lat,
                                            endPoint.lon
                                        )
                                        viewModel.currentChunkRadius = successfulRadius
                                        routePrefs.edit {
                                            putInt("chunkRadius", successfulRadius)
                                        }
                                        if (startIntersection != null) {
                                            viewModel.currentChunkRadius = successfulRadius


                                            viewModel.mapChunkCenterLat = endPoint.lat
                                            viewModel.mapChunkCenterLon = endPoint.lon

                                            viewModel.followRoadCurrentNode = startIntersection.id
                                            viewModel.followRoadTargetNode = -1L
                                            viewModel.followRoadLastNode = -1L

                                            viewModel.activeRoadGraph = downloadedGraph

                                            // Set the flags
                                            viewModel.loopRouteBackwards = false
                                            viewModel.loopRouteContinuously = false
                                            viewModel.followRoadAfterGpx = true

                                            routePrefs.edit {
                                                putBoolean("loopBackwards", false)
                                                putBoolean("loopContinuously", false)
                                                putBoolean("followRoadAfterGpx", true)
                                                putBoolean("hasSelectedRouteBehavior", true)

                                                // Save the correct nodes and chunk centers to memory
                                                putFloat("chunkLat", endPoint.lat.toFloat())
                                                putFloat("chunkLon", endPoint.lon.toFloat())
                                                putInt("chunkRadius", successfulRadius)
                                                putLong(
                                                    "followRoadCurrentNode",
                                                    startIntersection.id
                                                )
                                                putLong("followRoadTargetNode", -1L)
                                                putLong("followRoadLastNode", -1L)
                                            }

                                            viewModel.isGeneratingTrail = false
                                            viewModel.showRouteModeDialog = false
                                            viewModel.pendingSessionAction?.invoke()
                                            viewModel.pendingSessionAction = null
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                viewModel.isGeneratingTrail = false
                                                Toast.makeText(
                                                    context,
                                                    "No roads found near the route end!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !viewModel.isGeneratingTrail
                            ) {
                                if (viewModel.isGeneratingTrail) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Downloading Map...")
                                } else {
                                    Text("Follow roads after route ends")
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


            if (viewModel.showColorDialog) {
                // Define our available colors
                val colorOptions = listOf(
                    "Blue" to android.graphics.Color.BLUE,
                    "Red" to android.graphics.Color.RED,
                    "Green" to "#008000".toColorInt(), // Darker Green
                    "Purple" to "#800080".toColorInt(),
                    "Orange" to "#FFA500".toColorInt(),
                    "Black" to android.graphics.Color.BLACK
                )

                AlertDialog(
                    onDismissRequest = { viewModel.showColorDialog = false },
                    title = { Text("Choose Trail Color") },
                    text = {
                        Column {
                            colorOptions.forEach { (name, colorValue) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Draw a small colored circle preview
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                Color(
                                                    colorValue
                                                ), CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))

                                    // Make the row clickable
                                    TextButton(
                                        onClick = {
                                            viewModel.updateTrailColor(colorValue)
                                            viewModel.showColorDialog = false
                                        }
                                    ) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.showColorDialog = false
                        }) { Text("Cancel") }
                    }
                )
            }
        }
    }
