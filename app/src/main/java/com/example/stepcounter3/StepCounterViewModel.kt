package com.example.stepcounter3

import RoadGraph
import addNoiseToCoordinate
import android.os.Build
import androidx.annotation.RequiresApi
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.stepcounter3.TrailPoint
import com.google.maps.android.compose.MapType
import java.time.LocalDateTime
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

@RequiresApi(Build.VERSION_CODES.O)
class StepCounterViewModel(
    private val routePrefs: SharedPreferences
) : ViewModel() {
    // SENSOR & REAL-TIME STATE
    var totalSteps by mutableStateOf(0)
    var isSessionRunning by mutableStateOf(routePrefs.getBoolean("isSessionRunning", false))
    var sessionStartTime by mutableStateOf(routePrefs.getLong("sessionStartTime", 0L))
    var sessionStartSteps by mutableStateOf(routePrefs.getInt("sessionStartSteps", 0))

    // ==========================================
    // 1. LOCATION & MAP STATE
    // ==========================================
    var homeLat by mutableStateOf(0.0)
    var homeLon by mutableStateOf(0.0)
    var currentLat by mutableStateOf(0.0)
    var currentLon by mutableStateOf(0.0)
    var isPickingLocation by mutableStateOf(false)
    var currentMapType by mutableStateOf(MapType.NORMAL)

    // ==========================================
    // 2. SESSION & TRAIL STATE
    // ==========================================
    var elapsedTime by mutableStateOf(0L)
    var lastUpdatedSteps by mutableStateOf(0)
    var lastStepCheckpoint by mutableStateOf(0)
    var lastCheckpointTime by mutableStateOf(LocalDateTime.now())

    var liveTrail by mutableStateOf<List<TrailPoint>>(emptyList())
    var generatedTrail by mutableStateOf<List<TrailPoint>>(emptyList())

    var sessionResumedTimestamp by mutableStateOf(0L)
    var manualResumeTime by mutableStateOf(0L)
    var isGeneratingTrail by mutableStateOf(false)
    var pendingSessionAction by mutableStateOf<(() -> Unit)?>(null)

    // ==========================================
    // 3. MATH & WALKING STATE
    // ==========================================
    var walkingDirection by mutableStateOf(Math.random() * 360)
    var strideLength by mutableStateOf(0.7) // Default
    var currentLateralOffset by mutableStateOf(0.0)

    // ==========================================
    // 4. PREVIOUS SESSION MEMORY
    // ==========================================
    var lastSessionSteps by mutableStateOf(0)
    var lastSessionDistance by mutableStateOf(0.0)
    var lastSessionSpeed by mutableStateOf(0.0)
    var lastSessionDuration by mutableStateOf(0L)
    var lastSessionTrail by mutableStateOf<List<TrailPoint>>(emptyList())
    var resumePoint by mutableStateOf<TrailPoint?>(null)

    // ==========================================
    // 5. GPX IMPORT & ROUTE BEHAVIOR
    // ==========================================
    var importedRoute by mutableStateOf<List<TrailPoint>>(emptyList())
    var routeTargetIndex by mutableStateOf(routePrefs.getInt("savedRouteIndex", 0))
    var routeDirection by mutableStateOf(routePrefs.getInt("savedRouteDirection", 1))
    var loopRouteBackwards by mutableStateOf(routePrefs.getBoolean("loopBackwards", false))
    var loopRouteContinuously by mutableStateOf(routePrefs.getBoolean("loopContinuously", false))

    // ==========================================
    // 6. AUTONOMOUS WALKER (FR2.4)
    // ==========================================
    var isFollowRoadMode by mutableStateOf(routePrefs.getBoolean("isFollowRoadMode", false))
    var activeRoadGraph by mutableStateOf<RoadGraph?>(null)
    var followRoadCurrentNode by mutableStateOf(routePrefs.getLong("followRoadCurrentNode", -1L))
    var followRoadTargetNode by mutableStateOf(routePrefs.getLong("followRoadTargetNode", -1L))
    var followRoadLastNode by mutableStateOf(routePrefs.getLong("followRoadLastNode", -1L))

    // ==========================================
    // 7. UI DIALOG TOGGLES
    // ==========================================
    var showStrideDialog by mutableStateOf(false)
    var showRouteModeDialog by mutableStateOf(false)
    var showFreeWalkModeDialog by mutableStateOf(false)

    // ==========================================
    // 8. PHOTO TAGGING STATE
    // ==========================================
    var isManualTagMode by mutableStateOf(false)
    var showUnmatchedDialog by mutableStateOf(false)
    var unmatchedUris by mutableStateOf<List<Uri>>(emptyList())
    var currentQueueIndex by mutableStateOf(0)
    var manualTagIndex by mutableStateOf(0f)
    var isInitialized by mutableStateOf(false)

    // Helper functions to update SharedPreferences cleanly
    fun saveRouteBehavior(loopBackwards: Boolean, loopContinuously: Boolean) {
        this.loopRouteBackwards = loopBackwards
        this.loopRouteContinuously = loopContinuously
        routePrefs.edit {
            putBoolean("loopBackwards", loopBackwards)
            putBoolean("loopContinuously", loopContinuously)
            putBoolean("hasSelectedRouteBehavior", true)
        }
    }

    fun onStepTick(
        totalSteps: Int,
        sessionStartSteps: Int,
        isSessionRunning: Boolean,
        onSyncStepBaseline: (Int) -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
        sessionStartTime: Long
    ) {
        if (!isSessionRunning) return

        if (isFollowRoadMode && activeRoadGraph == null) {
            return // Freeze guard
        }

        if (lastStepCheckpoint == 0 && totalSteps > 0) {
            lastStepCheckpoint = totalSteps
            return
        }

        if (totalSteps < lastStepCheckpoint) {
            lastStepCheckpoint = totalSteps
            return
        }

        if (manualResumeTime > 0 && (System.currentTimeMillis() - manualResumeTime < 3000)) {
            val ghostSteps = totalSteps - lastStepCheckpoint
            if (ghostSteps > 0) onSyncStepBaseline(ghostSteps)
            lastStepCheckpoint = totalSteps
            return
        }

        val sessionAge = System.currentTimeMillis() - sessionStartTime
        if (sessionAge < 1000) {
            lastStepCheckpoint = totalSteps
            return
        }

        val stepsSinceCheckpoint = totalSteps - lastStepCheckpoint

        if (stepsSinceCheckpoint >= 10) {
            val now = LocalDateTime.now()

            // CATCH UP MODE
            if (stepsSinceCheckpoint > 50) {
                val estimatedSeconds = (stepsSinceCheckpoint * 0.6).toLong()
                val calculatedEndTime = lastCheckpointTime.plusSeconds(estimatedSeconds)
                val finalEndTime = if (calculatedEndTime.isAfter(now)) now else calculatedEndTime

                if (isFollowRoadMode) {
                    val graph = activeRoadGraph!!
                    var simLat = currentLat
                    var simLon = currentLon
                    var simTime = lastCheckpointTime
                    var simTargetId = followRoadTargetNode
                    var simCurrentId = followRoadCurrentNode

                    val fastForwardPoints = mutableListOf<TrailPoint>()
                    var remainingDistance = stepsSinceCheckpoint * strideLength
                    val stepDist = strideLength * 2.0

                    while (remainingDistance > 0) {
                        val targetNode = graph.nodes[simTargetId]
                        if (targetNode == null) break

                        val distToTarget = haversineMeters(simLat, simLon, targetNode.lat, targetNode.lon)
                        var pointToSaveLat = simLat
                        var pointToSaveLon = simLon

                        if (distToTarget <= stepDist) {
                            simLat = targetNode.lat
                            simLon = targetNode.lon
                            remainingDistance -= distToTarget

                            pointToSaveLat = simLat
                            pointToSaveLon = simLon

                            val connectedEdges = graph.adjacencyList[targetNode.id] ?: emptyList()
                            val validNextEdges = if (connectedEdges.size > 1) {
                                connectedEdges.filter { it.targetNodeId != simCurrentId }
                            } else connectedEdges

                            val nextEdge = validNextEdges.randomOrNull()
                            if (nextEdge != null) {
                                followRoadLastNode = simCurrentId
                                simCurrentId = targetNode.id
                                simTargetId = nextEdge.targetNodeId
                            } else break
                        } else {
                            val roadBearing = com.example.stepcounter3.calculateBearing(simLat, simLon, targetNode.lat, targetNode.lon)
                            val rad = Math.toRadians(roadBearing)

                            val metersPerDegLat = 111_320.0
                            val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(simLat))

                            simLat += (stepDist / metersPerDegLat) * kotlin.math.cos(rad)
                            simLon += (stepDist / metersPerDegLon) * kotlin.math.sin(rad)
                            remainingDistance -= stepDist

                            val driftChange = (-100..100).random() / 100.0
                            currentLateralOffset = (currentLateralOffset + driftChange).coerceIn(-4.0, 4.0)

                            val noisyPoint = addNoiseToCoordinate(simLat, simLon, roadBearing, currentLateralOffset)
                            pointToSaveLat = noisyPoint.lat
                            pointToSaveLon = noisyPoint.lon
                        }

                        simTime = simTime.plusSeconds(1)
                        fastForwardPoints.add(TrailPoint(pointToSaveLat, pointToSaveLon, simTime))
                    }

                    currentLat = simLat
                    currentLon = simLon
                    lastCheckpointTime = simTime
                    followRoadCurrentNode = simCurrentId
                    followRoadTargetNode = simTargetId

                    routePrefs.edit()
                        .putLong("followRoadCurrentNode", followRoadCurrentNode)
                        .putLong("followRoadTargetNode", followRoadTargetNode)
                        .putLong("followRoadLastNode", followRoadLastNode)
                        .apply()

                    liveTrail = liveTrail + fastForwardPoints
                } else {
                    val catchUpResult = com.example.stepcounter3.extendTrail(
                        startLat = currentLat, startLon = currentLon, startTime = lastCheckpointTime,
                        steps = stepsSinceCheckpoint, stepLengthMeters = strideLength, endTime = finalEndTime,
                        importedRoute = importedRoute, startingWaypointIndex = routeTargetIndex,
                        loopRouteBackwards = loopRouteBackwards, loopRouteContinuously = loopRouteContinuously,
                        initialRouteDirection = routeDirection
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
            } else {
                // NORMAL MODE
                val distanceMeters = stepsSinceCheckpoint * strideLength

                if (isFollowRoadMode) {
                    val graph = activeRoadGraph!!
                    val targetNode = graph.nodes[followRoadTargetNode]

                    if (targetNode != null) {
                        val distToTarget = com.example.stepcounter3.haversineMeters(currentLat, currentLon, targetNode.lat, targetNode.lon)

                        if (distToTarget <= distanceMeters.coerceAtLeast(2.0)) {
                            currentLat = targetNode.lat
                            currentLon = targetNode.lon

                            val connectedEdges = graph.adjacencyList[targetNode.id] ?: emptyList()
                            val validNextEdges = if (connectedEdges.size > 1) {
                                connectedEdges.filter { it.targetNodeId != followRoadCurrentNode }
                            } else connectedEdges

                            val nextEdge = validNextEdges.randomOrNull()
                            if (nextEdge != null) {
                                followRoadLastNode = followRoadCurrentNode
                                followRoadCurrentNode = targetNode.id
                                followRoadTargetNode = nextEdge.targetNodeId

                                routePrefs.edit()
                                    .putLong("followRoadCurrentNode", followRoadCurrentNode)
                                    .putLong("followRoadTargetNode", followRoadTargetNode)
                                    .putLong("followRoadLastNode", followRoadLastNode)
                                    .apply()
                            }

                            liveTrail = liveTrail + TrailPoint(currentLat, currentLon, now)
                            lastCheckpointTime = now
                        } else {
                            val roadBearing = com.example.stepcounter3.calculateBearing(currentLat, currentLon, targetNode.lat, targetNode.lon)
                            val rad = Math.toRadians(roadBearing)

                            val metersPerDegLat = 111_320.0
                            val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(currentLat))

                            currentLat += (distanceMeters / metersPerDegLat) * kotlin.math.cos(rad)
                            currentLon += (distanceMeters / metersPerDegLon) * kotlin.math.sin(rad)

                            val driftChange = (-100..100).random() / 100.0
                            currentLateralOffset = (currentLateralOffset + driftChange).coerceIn(-4.0, 4.0)

                            val noisyPoint = addNoiseToCoordinate(currentLat, currentLon, roadBearing, currentLateralOffset)
                            liveTrail = liveTrail + noisyPoint
                            lastCheckpointTime = now
                        }
                    }
                } else {
                    if (importedRoute.size > 1) {
                        while (routeTargetIndex >= 0 && routeTargetIndex < importedRoute.size) {
                            val isEndPoint = (routeTargetIndex == 0 || routeTargetIndex == importedRoute.size - 1)
                            val baseRadius = if (isEndPoint) 1.5 else 5.0
                            val hitRadius = baseRadius.coerceAtLeast(distanceMeters).coerceAtMost(8.0)

                            if (haversineMeters(currentLat, currentLon, importedRoute[routeTargetIndex].lat, importedRoute[routeTargetIndex].lon) < hitRadius) {
                                routeTargetIndex += routeDirection
                                routePrefs.edit()
                                    .putInt("savedRouteIndex", routeTargetIndex)
                                    .putInt("savedRouteDirection", routeDirection)
                                    .apply()
                            } else break
                        }

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
                            walkingDirection = com.example.stepcounter3.calculateBearing(currentLat, currentLon, target.lat, target.lon)
                            walkingDirection += (-2..2).random()
                        } else walkingDirection += (-10..10).random()
                    } else walkingDirection += (-10..10).random()

                    val rad = Math.toRadians(walkingDirection)
                    val metersPerDegLat = 111_320.0
                    val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(currentLat))

                    currentLat += (distanceMeters / metersPerDegLat) * Math.cos(rad)
                    currentLon += (distanceMeters / metersPerDegLon) * Math.sin(rad)

                    liveTrail = liveTrail + TrailPoint(currentLat, currentLon, now)
                    lastCheckpointTime = now
                }
            }

            // Save state
            val currentDuration = System.currentTimeMillis() - sessionStartTime
            lastStepCheckpoint = totalSteps
            val currentSessionSteps = totalSteps - sessionStartSteps
            onTrailUpdated(liveTrail, currentSessionSteps, currentDuration)
        }
    }
    fun startSession(
        totalSteps: Int,
        onClearSavedData: () -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
        onStartSession: (Int, Long) -> Unit
    ) {
        onClearSavedData()

        if (importedRoute.isNotEmpty()) {
            isFollowRoadMode = false
            routePrefs.edit().putBoolean("isFollowRoadMode", false).apply()
        }

        val newSessionStart = routeTargetIndex.coerceIn(0, importedRoute.lastIndex.coerceAtLeast(0))
        routePrefs.edit().putInt("sessionStartIndex", newSessionStart).apply()

        lastUpdatedSteps = 0
        if (importedRoute.isNotEmpty()) {
            currentLat = importedRoute[newSessionStart].lat
            currentLon = importedRoute[newSessionStart].lon
        } else if (!isFollowRoadMode) {
            currentLat = homeLat
            currentLon = homeLon
        }

        val startPoint = TrailPoint(currentLat, currentLon, LocalDateTime.now())
        liveTrail = listOf(startPoint)
        onTrailUpdated(liveTrail, 0, 0L)

        lastStepCheckpoint = totalSteps
        lastCheckpointTime = LocalDateTime.now()
        manualResumeTime = System.currentTimeMillis()
        onStartSession(0, 0L)
    }

    fun endSession(
        totalSteps: Int,
        sessionStartSteps: Int,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
        onEndSession: (Int, Double, Double, Long) -> Unit
    ) {
        isGeneratingTrail = true
        val exactEndTime = LocalDateTime.now()

        // viewModelScope ensures this math survives even if the user rotates their phone!
        viewModelScope.launch(Dispatchers.Default) {
            val remainingUnprocessedSteps = totalSteps - lastStepCheckpoint

            val finalPath = if (remainingUnprocessedSteps > 0 && liveTrail.isNotEmpty()) {
                val lastPoint = liveTrail.last()
                extendTrail(
                    startLat = lastPoint.lat, startLon = lastPoint.lon, startTime = lastPoint.time,
                    endTime = exactEndTime, steps = remainingUnprocessedSteps, stepLengthMeters = strideLength,
                    importedRoute = importedRoute, startingWaypointIndex = routeTargetIndex,
                    loopRouteBackwards = loopRouteBackwards, loopRouteContinuously = loopRouteContinuously,
                    initialRouteDirection = routeDirection
                ).first
            } else emptyList()

            withContext(Dispatchers.Main) {
                if (finalPath.isNotEmpty()) {
                    liveTrail = liveTrail + finalPath
                    val veryLast = liveTrail.last()
                    currentLat = veryLast.lat
                    currentLon = veryLast.lon
                    lastCheckpointTime = veryLast.time
                    lastStepCheckpoint = totalSteps
                } else if (liveTrail.isNotEmpty()) {
                    val lastPoint = liveTrail.last()
                    liveTrail = liveTrail + TrailPoint(lastPoint.lat, lastPoint.lon, exactEndTime)
                    lastCheckpointTime = exactEndTime
                }

                if (liveTrail.isNotEmpty()) resumePoint = liveTrail.last()

                val finalSessionSteps = (totalSteps - sessionStartSteps).coerceAtLeast(0)
                val finalDistanceKm = (finalSessionSteps * strideLength) / 1000.0
                val finalDurationMillis = elapsedTime * 1000L

                val finalSpeed = if (liveTrail.size >= 2) {
                    val p1 = liveTrail[liveTrail.size - 2]
                    val p2 = liveTrail.last()
                    val dist = haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon)
                    val time = java.time.Duration.between(p1.time, p2.time).seconds
                    if (time > 0) (dist / time) * 3.6 else 0.0
                } else 0.0

                onTrailUpdated(liveTrail, finalSessionSteps, finalDurationMillis)
                lastSessionSteps = finalSessionSteps
                lastSessionDistance = finalDistanceKm
                lastSessionSpeed = finalSpeed
                lastSessionTrail = liveTrail.toList()
                lastSessionDuration = finalDurationMillis

                routePrefs.edit {
                    putInt("savedRouteIndex", routeTargetIndex)
                    putInt("savedRouteDirection", routeDirection)
                }

                isGeneratingTrail = false
                onEndSession(finalSessionSteps, finalDistanceKm, finalSpeed, finalDurationMillis)
            }
        }
    }

    fun resumeSession(
        context: android.content.Context,
        totalSteps: Int,
        initialTrail: List<TrailPoint>,
        initialSteps: Int,
        initialDuration: Long,
        onClearSavedData: () -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
        onShowToast: (String) -> Unit
    ) {
        val historyTrail = if (lastSessionTrail.isNotEmpty() && lastSessionSteps > 0) lastSessionTrail else initialTrail
        val historySteps = if (lastSessionTrail.isNotEmpty() && lastSessionSteps > 0) lastSessionSteps else initialSteps
        val historyDuration = if (lastSessionTrail.isNotEmpty() && lastSessionSteps > 0) lastSessionDuration else initialDuration

        if (historySteps <= 0 || historyTrail.size < 2) {
            onShowToast("Walk a few steps to get a valid trail")
        } else {
            if (historyTrail.size <= 1 && importedRoute.isNotEmpty() && routeTargetIndex > 0) {
                val sessionStart = routePrefs.getInt("sessionStartIndex", 0)
                val safeStart = sessionStart.coerceIn(0, importedRoute.size - 1)
                val safeEnd = routeTargetIndex.coerceIn(0, importedRoute.size - 1)

                if (safeEnd >= safeStart) {
                    liveTrail = importedRoute.subList(safeStart, safeEnd + 1).map { TrailPoint(it.lat, it.lon, LocalDateTime.now()) }
                    currentLat = importedRoute[safeEnd].lat
                    currentLon = importedRoute[safeEnd].lon
                }
            } else {
                liveTrail = historyTrail
                resumePoint?.let { point -> currentLat = point.lat; currentLon = point.lon }
            }

            lastUpdatedSteps = 0
            lastStepCheckpoint = totalSteps
            lastCheckpointTime = LocalDateTime.now()
            manualResumeTime = System.currentTimeMillis()
            onClearSavedData()
            onTrailUpdated(liveTrail, historySteps, historyDuration)
        }
    }

    fun clearRoute(context: android.content.Context) {
        importedRoute = emptyList()
        routeTargetIndex = 0
        loopRouteBackwards = false
        loopRouteContinuously = false

        routePrefs.edit {
            putBoolean("loopBackwards", false)
                .putBoolean("loopContinuously", false)
                .putBoolean("hasSelectedRouteBehavior", false)
                .putBoolean("isFollowRoadMode", false)
                .putInt("savedRouteIndex", 0)
                .putInt("savedRouteDirection", 1)
                .putLong("followRoadCurrentNode", -1L)
                .putLong("followRoadTargetNode", -1L)
                .putLong("followRoadLastNode", -1L)
        }

        followRoadCurrentNode = -1L
        followRoadTargetNode = -1L
        followRoadLastNode = -1L
        isFollowRoadMode = false
        activeRoadGraph = null

        clearRouteFromInternalStorage(context)
    }
    fun initialize(
        context: android.content.Context,
        defaultLat: Double,
        defaultLon: Double,
        initialTrail: List<TrailPoint>,
        initialSteps: Int,
        initialDuration: Long,
        initialStride: Double,
        sessionStartSteps: Int
    ) {
        if (isInitialized) return // Only run this exactly once
        isInitialized = true

        // 1. Base Locations
        this.homeLat = defaultLat
        this.homeLon = defaultLon

        // 2. Load History
        this.lastSessionSteps = initialSteps
        this.lastSessionDistance = (initialSteps * initialStride) / 1000.0
        this.lastSessionDuration = initialDuration
        this.lastSessionTrail = initialTrail

        // 3. Load GPX from hard drive
        this.importedRoute = loadRouteFromInternalStorage(context)

        // 4. Live States
        this.liveTrail = initialTrail
        this.currentLat = initialTrail.lastOrNull()?.lat ?: homeLat
        this.currentLon = initialTrail.lastOrNull()?.lon ?: homeLon
        this.lastCheckpointTime = initialTrail.lastOrNull()?.time ?: LocalDateTime.now()

        // 5. Pedometer Checkpoint Math
        this.lastStepCheckpoint = sessionStartSteps + initialSteps
        this.strideLength = initialStride
        this.resumePoint = initialTrail.lastOrNull()
    }
    fun onStepsReceived(steps: Int) {
        if (isSessionRunning && steps > 0) {
            val currentBaseline = sessionStartSteps
            if (currentBaseline == 0 || (System.currentTimeMillis() - sessionStartTime < 2000)) {
                if (steps > currentBaseline) {
                    sessionStartSteps = steps
                    routePrefs.edit().putInt("sessionStartSteps", steps).apply()
                }
            }
        }
        totalSteps = steps
    }
    fun startSession(
        context: android.content.Context, // NEW
        onClearSavedData: () -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit
    ) {
        // 1. Start the Android Service
        val startIntent = android.content.Intent(context, com.example.stepcounter3.StepService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }

        // 2. Set Session States
        isSessionRunning = true
        sessionStartTime = System.currentTimeMillis()
        sessionStartSteps = totalSteps

        routePrefs.edit()
            .putBoolean("isSessionRunning", true)
            .putLong("sessionStartTime", sessionStartTime)
            .putInt("sessionStartSteps", sessionStartSteps)
            .apply()

        // 3. Normal Start Logic
        onClearSavedData()
        if (importedRoute.isNotEmpty()) {
            isFollowRoadMode = false
            routePrefs.edit().putBoolean("isFollowRoadMode", false).apply()
        }

        val newSessionStart = routeTargetIndex.coerceIn(0, importedRoute.lastIndex.coerceAtLeast(0))
        routePrefs.edit().putInt("sessionStartIndex", newSessionStart).apply()

        lastUpdatedSteps = 0
        if (importedRoute.isNotEmpty()) {
            currentLat = importedRoute[newSessionStart].lat
            currentLon = importedRoute[newSessionStart].lon
        } else if (!isFollowRoadMode) {
            currentLat = homeLat
            currentLon = homeLon
        }

        val startPoint = TrailPoint(currentLat, currentLon, LocalDateTime.now())
        liveTrail = listOf(startPoint)
        onTrailUpdated(liveTrail, 0, 0L)

        lastStepCheckpoint = totalSteps
        lastCheckpointTime = LocalDateTime.now()
        manualResumeTime = System.currentTimeMillis()
    }

    fun endSession(
        context: android.content.Context, // NEW
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit
    ) {
        // 1. Stop the Android Service
        val stopIntent = android.content.Intent(context, StepService::class.java)
        stopIntent.action = "STOP_SERVICE"
        context.startService(stopIntent)

        // 2. Clear Session States
        isSessionRunning = false
        routePrefs.edit().putBoolean("isSessionRunning", false).apply()

        // 3. Normal End Logic
        isGeneratingTrail = true
        val exactEndTime = LocalDateTime.now()

        viewModelScope.launch(Dispatchers.Default) {
            val remainingUnprocessedSteps = totalSteps - lastStepCheckpoint

            val finalPath = if (remainingUnprocessedSteps > 0 && liveTrail.isNotEmpty()) {
                val lastPoint = liveTrail.last()
                extendTrail(
                    startLat = lastPoint.lat, startLon = lastPoint.lon, startTime = lastPoint.time,
                    endTime = exactEndTime, steps = remainingUnprocessedSteps, stepLengthMeters = strideLength,
                    importedRoute = importedRoute, startingWaypointIndex = routeTargetIndex,
                    loopRouteBackwards = loopRouteBackwards, loopRouteContinuously = loopRouteContinuously,
                    initialRouteDirection = routeDirection
                ).first
            } else emptyList()

            withContext(Dispatchers.Main) {
                if (finalPath.isNotEmpty()) {
                    liveTrail = liveTrail + finalPath
                    val veryLast = liveTrail.last()
                    currentLat = veryLast.lat
                    currentLon = veryLast.lon
                    lastCheckpointTime = veryLast.time
                    lastStepCheckpoint = totalSteps
                } else if (liveTrail.isNotEmpty()) {
                    val lastPoint = liveTrail.last()
                    liveTrail = liveTrail + TrailPoint(lastPoint.lat, lastPoint.lon, exactEndTime)
                    lastCheckpointTime = exactEndTime
                }

                if (liveTrail.isNotEmpty()) resumePoint = liveTrail.last()

                val finalSessionSteps = (totalSteps - sessionStartSteps).coerceAtLeast(0)
                val finalDistanceKm = (finalSessionSteps * strideLength) / 1000.0
                val finalDurationMillis = elapsedTime * 1000L

                val finalSpeed = if (liveTrail.size >= 2) {
                    val p1 = liveTrail[liveTrail.size - 2]
                    val p2 = liveTrail.last()
                    val dist = haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon)
                    val time = java.time.Duration.between(p1.time, p2.time).seconds
                    if (time > 0) (dist / time) * 3.6 else 0.0
                } else 0.0

                onTrailUpdated(liveTrail, finalSessionSteps, finalDurationMillis)
                lastSessionSteps = finalSessionSteps
                lastSessionDistance = finalDistanceKm
                lastSessionSpeed = finalSpeed
                lastSessionTrail = liveTrail.toList()
                lastSessionDuration = finalDurationMillis

                routePrefs.edit {
                    putInt("savedRouteIndex", routeTargetIndex)
                    putInt("savedRouteDirection", routeDirection)
                }

                isGeneratingTrail = false
            }
        }
    }
}