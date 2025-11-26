package com.example.stepcounter3.ui

import android.widget.Toast
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


@Composable
fun MapScreen() {
    val mmucyberjaya = LatLng(2.9276384, 101.6420577)

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(mmucyberjaya, 14f)
        }
    ) {
        Marker(
            state = MarkerState(position = mmucyberjaya),
            title = "Multimedia University",
            snippet = "Marker in Cyberjaya"
        )
    }
}

@Composable
fun StepCounterScreen(
    onOpenMap: () -> Unit,
    totalStepsFlow: MutableStateFlow<Int>,
    previousTotalSteps: Float,
    onReset: () -> Unit,
    onStartSession: () -> Unit,
    onEndSession: (steps: Int, distanceKm: Double, speedKmh: Double) -> Unit,
    isSessionRunningFlow: MutableStateFlow<Boolean>,
    sessionStartTimeFlow: MutableStateFlow<Long>,
    sessionStartStepsFlow: MutableStateFlow<Int>
) {
    val totalSteps by totalStepsFlow.collectAsState()
    val currentSteps = (totalSteps - previousTotalSteps.toInt()).coerceAtLeast(0)
    val context = LocalContext.current
    val isSessionRunning by isSessionRunningFlow.collectAsState()
    val sessionStartTime by sessionStartTimeFlow.collectAsState()
    val sessionStartSteps by sessionStartStepsFlow.collectAsState()

    var elapsedTime by remember { mutableStateOf(0L) }
    var lastSessionSteps by remember { mutableStateOf(0) }
    var lastSessionDistance by remember { mutableStateOf(0.0) }
    var lastSessionSpeed by remember { mutableStateOf(0.0) }

    // Tick every second while session is running
    LaunchedEffect(isSessionRunning) {
        while (isSessionRunning) {
            elapsedTime = (System.currentTimeMillis() - sessionStartTime) / 1000
            delay(1000)
        }
    }
    val sessionEndTime = System.currentTimeMillis()
    val totalDurationMillis = sessionEndTime - sessionStartTimeFlow.collectAsState().value
    val totalDurationSeconds = totalDurationMillis / 1000

    // Calculate distance and speed
    val stepLengthMeters = 0.75 // average step length
    val sessionSteps = if (isSessionRunning) totalSteps - sessionStartSteps else 0
    val distanceMeters = sessionSteps * stepLengthMeters
    val distanceKm = distanceMeters / 1000.0
    val speedKmh = if (elapsedTime > 0) distanceMeters / elapsedTime * 3.6 else 0.0
    val averageSpeedMps = if (totalDurationSeconds > 0) distanceMeters / totalDurationSeconds else 0.0
    val averageSpeedKmh = averageSpeedMps * 3.6

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

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

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onStartSession,
                    enabled = !isSessionRunning
                ) { Text("Start") }

                Button(
                    onClick = {
                        // Save last session info
                        lastSessionSteps = sessionSteps
                        lastSessionDistance = distanceKm
                        lastSessionSpeed = speedKmh

                        onEndSession(sessionSteps, distanceKm, speedKmh)
                    },
                    enabled = isSessionRunning
                ) { Text("End") }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenMap) {
                Text("Map")
            }
        }
    }
}
