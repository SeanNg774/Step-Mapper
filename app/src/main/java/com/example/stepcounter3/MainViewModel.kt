/*package com.example.stepcounter3

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import com.example.stepcounter3.PhotoTagger


@RequiresApi(Build.VERSION_CODES.O)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- Dependencies (Matching Class Diagram) ---
    private val repository = TrailRepository(application)
    private val trailGenerator = TrailGeneration()
    private val gpxGenerator = GPXGenerator()
    private val photoTagger = PhotoTagger()

    // --- UI State Flows (Strict Unidirectional Data Flow) ---
    val activeTrail: StateFlow<List<TrailPoint>> = repository.activeSession

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _sessionSteps = MutableStateFlow(0)
    val sessionSteps: StateFlow<Int> = _sessionSteps.asStateFlow()

    private val _distanceKm = MutableStateFlow(0.0)
    val distanceKm: StateFlow<Double> = _distanceKm.asStateFlow()

    private val _elapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds: StateFlow<Long> = _elapsedTimeSeconds.asStateFlow()

    private val _strideLength = MutableStateFlow(0.7) // Default stride in meters
    val strideLength: StateFlow<Double> = _strideLength.asStateFlow()

    // --- User Actions (From UI) ---

    fun setStrideLength(length: Double) {
        _strideLength.value = length
    }

    fun startSession() {
        _isTracking.value = true
        // The Service will be started via Intent from MainActivity based on this state
    }

    fun stopSession() {
        _isTracking.value = false
    }

    fun resumeFromLastTrail() {
        _sessionSteps.value = repository.getSavedTrailSteps()
        _elapsedTimeSeconds.value = repository.getSavedTrailDuration()
        _distanceKm.value = (_sessionSteps.value * _strideLength.value) / 1000.0
        _isTracking.value = true
    }

    // --- Core Logic (Called by StepService) ---

    /**
     * This handles the Step Detection and Catch-Up Algorithm.
     * It completely removes this heavy math from the UI thread.
     */
    fun updateStepsFromSensor(totalSensorSteps: Int, sessionStartBaseline: Int) {
        if (!_isTracking.value) return

        val currentSteps = (totalSensorSteps - sessionStartBaseline).coerceAtLeast(0)
        val stepsSinceLastUpdate = currentSteps - _sessionSteps.value

        _sessionSteps.value = currentSteps
        _distanceKm.value = (currentSteps * _strideLength.value) / 1000.0

        // Catch-Up Algorithm Trigger (If app was killed or sensor batched a huge chunk)
        if (stepsSinceLastUpdate > 0) {
            val currentTrail = repository.getAllPoints()

            if (currentTrail.isNotEmpty()) {
                val lastPoint = currentTrail.last()
                val now = LocalDateTime.now()

                // Calculate the path using TrailGeneration
                val newPath = trailGenerator.extendTrail(
                    startLat = lastPoint.lat,
                    startLon = lastPoint.lon,
                    startTime = lastPoint.time,
                    steps = stepsSinceLastUpdate,
                    stepLengthMeters = _strideLength.value,
                    endTime = now
                )

                // Save to Repository
                repository.addPoint(
                    point = newPath.last(), // Or add the whole path if needed
                    currentSteps = currentSteps,
                    durationMillis = _elapsedTimeSeconds.value
                )
            }
        }
    }

    // --- Export & Media Integration ---

    /**
     * Matches exportGpx() in Class Diagram.
     * Uses Coroutines to avoid freezing the UI while writing files.
     */
    fun exportGpx(context: Context, onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val points = repository.getAllPoints()
                if (points.isNotEmpty()) {
                    gpxGenerator.generateGpxFile(context, points)
                } else null
            }
            onResult(file)
        }
    }

    /**
     * Matches processGeotags() in Class Diagram.
     */
    fun processGeotags(context: Context, uris: List<Uri>, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val taggedCount = withContext(Dispatchers.IO) {
                val points = repository.getAllPoints()
                // Calls the abstracted tagAuto method we defined for the Class Diagram
                photoTagger.tagAuto(context, points, uris)
            }
            onComplete(taggedCount)
        }
    }

    fun clearData() {
        repository.clearSession()
        _sessionSteps.value = 0
        _distanceKm.value = 0.0
        _elapsedTimeSeconds.value = 0L
    }
}*/