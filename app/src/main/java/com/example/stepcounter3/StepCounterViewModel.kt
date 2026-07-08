
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.Context
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.format.DateTimeFormatter
import com.google.gson.GsonBuilder
import kotlin.math.abs

enum class MapProvider { GOOGLE, OSM }


data class WalkHistoryItem(
    val timestamp: Long,
    val steps: Int,
    val distance: Double,
    val trail: List<TrailPoint>
)

@RequiresApi(Build.VERSION_CODES.O)
class StepCounterViewModel(
    val routePrefs: SharedPreferences
) : ViewModel() {

    var totalSteps by mutableStateOf(0)
    var isSessionRunning by mutableStateOf(routePrefs.getBoolean("isSessionRunning", false))
    var sessionStartTime by mutableStateOf(routePrefs.getLong("sessionStartTime", 0L))
    var sessionStartSteps by mutableStateOf(routePrefs.getInt("sessionStartSteps", 0))

    var homeLat by mutableStateOf(0.0)
    var homeLon by mutableStateOf(0.0)
    var currentLat by mutableStateOf(0.0)
    var currentLon by mutableStateOf(0.0)
    var isPickingLocation by mutableStateOf(false)
    var currentMapType by mutableStateOf(MapType.NORMAL)

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

    // ---> FIX: Unified Universal Compass <---
    // It checks the hard drive for your last known direction.
    // If you have never used the app before, THEN it picks a random starting direction!
    var walkingDirection by mutableStateOf(routePrefs.getFloat("lastRoadBearing", (Math.random() * 360).toFloat()).toDouble())
    var strideLength by mutableStateOf(0.7)
    var currentLateralOffset by mutableStateOf(0.0)

    var lastSessionSteps by mutableStateOf(0)
    var lastSessionDistance by mutableStateOf(0.0)
    var lastSessionSpeed by mutableStateOf(0.0)
    var lastSessionDuration by mutableStateOf(0L)
    var lastSessionTrail by mutableStateOf<List<TrailPoint>>(emptyList())
    var resumePoint by mutableStateOf<TrailPoint?>(null)

    var importedRoute by mutableStateOf<List<TrailPoint>>(emptyList())
    var routeTargetIndex by mutableStateOf(routePrefs.getInt("savedRouteIndex", 0))
    var routeDirection by mutableStateOf(routePrefs.getInt("savedRouteDirection", 1))
    var loopRouteBackwards by mutableStateOf(routePrefs.getBoolean("loopBackwards", false))
    var loopRouteContinuously by mutableStateOf(routePrefs.getBoolean("loopContinuously", false))

    var followRoadAfterGpx by mutableStateOf(routePrefs.getBoolean("followRoadAfterGpx", false))

    var isFollowRoadMode by mutableStateOf(routePrefs.getBoolean("isFollowRoadMode", false))
    var activeRoadGraph by mutableStateOf<RoadGraph?>(null)
    var followRoadCurrentNode by mutableStateOf(routePrefs.getLong("followRoadCurrentNode", -1L))
    var followRoadTargetNode by mutableStateOf(routePrefs.getLong("followRoadTargetNode", -1L))
    var followRoadLastNode by mutableStateOf(routePrefs.getLong("followRoadLastNode", -1L))

    private val MEMORY_SIZE = 5
    private var recentNodes: MutableList<Long> = loadRecentNodesMemory()
    private fun loadRecentNodesMemory(): MutableList<Long> {
        val json = routePrefs.getString("recentNodesJson", "[]")
        return try {
            val type = object : TypeToken<MutableList<Long>>() {}.type
            customGson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }
    private fun saveRecentNodesMemory() {
        val json = customGson.toJson(recentNodes)
        routePrefs.edit { putString("recentNodesJson", json) }
    }

    var lastRoadBearing by mutableStateOf(routePrefs.getFloat("lastRoadBearing", 0f).toDouble())

    var showStrideDialog by mutableStateOf(false)
    var showRouteModeDialog by mutableStateOf(false)
    var showFreeWalkModeDialog by mutableStateOf(false)

    var isManualTagMode by mutableStateOf(false)
    var showUnmatchedDialog by mutableStateOf(false)
    var unmatchedUris by mutableStateOf<List<Uri>>(emptyList())
    var currentQueueIndex by mutableStateOf(0)
    var manualTagIndex by mutableStateOf(0f)
    var isInitialized by mutableStateOf(false)
    var currentMapProvider by mutableStateOf(MapProvider.valueOf(routePrefs.getString("mapProvider", "GOOGLE") ?: "GOOGLE"))

    var mapChunkCenterLat by mutableStateOf(routePrefs.getFloat("chunkLat", 0f).toDouble())
    var mapChunkCenterLon by mutableStateOf(routePrefs.getFloat("chunkLon", 0f).toDouble())

    var trailColor by mutableStateOf(routePrefs.getInt("trailColor", android.graphics.Color.BLUE))
    var showColorDialog by mutableStateOf(false)
    var isDownloadingGraph by mutableStateOf(false)

    var currentChunkRadius by mutableStateOf(routePrefs.getInt("chunkRadius", 500))
    var showOverwriteDialog by mutableStateOf(false)
    private val hiddenCatchUpBuffer = mutableListOf<TrailPoint>()
    var isCalculatingMassiveRoute by mutableStateOf(false)
    var showDirectionDialog by mutableStateOf(false)
    var walkHistory by mutableStateOf<List<WalkHistoryItem>>(emptyList())

    var availableDirections by mutableStateOf<List<Pair<String, Long>>>(emptyList())
    var pendingFollowRoadStartNode by mutableStateOf(-1L)

    private val customGson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDateTime::class.java, JsonSerializer<LocalDateTime> { src, _, _ ->
            JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        })
        .registerTypeAdapter(LocalDateTime::class.java, JsonDeserializer<LocalDateTime> { json, _, _ ->
            LocalDateTime.parse(json.asString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        })
        .create()
    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("walkHistoryJson", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<WalkHistoryItem>>() {}.type
                // Use customGson here!
                walkHistory = customGson.fromJson(json, type)
            } catch (e: Exception) {
                // If the old corrupted data causes an error, just clear it out
                walkHistory = emptyList()
            }
        }
    }

    fun saveHistory(context: Context) {
        val prefs = context.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE)
        val json = customGson.toJson(walkHistory)
        prefs.edit { putString("walkHistoryJson", json) }
    }

    fun updateTrailColor(newColor: Int) {
        trailColor = newColor
        routePrefs.edit { putInt("trailColor", newColor) }
    }

    fun saveRouteBehavior(loopBackwards: Boolean, loopContinuously: Boolean) {
        this.loopRouteBackwards = loopBackwards
        this.loopRouteContinuously = loopContinuously
        routePrefs.edit {
            putBoolean("loopBackwards", loopBackwards)
            putBoolean("loopContinuously", loopContinuously)
            putBoolean("hasSelectedRouteBehavior", true)
        }
    }
    fun updateRouteBehavior(
        newLoopBackwards: Boolean,
        newLoopContinuously: Boolean,
        newFollowRoadAfterGpx: Boolean
    ) {
        // ---> FIX: FORCE A CLEAN HANDOFF <---
        if (!newFollowRoadAfterGpx && this.isFollowRoadMode) {

            // 1. Bake the roads into the GPX route so they become a static track
            if (liveTrail.isNotEmpty()) {
                val oldGpxEnd = importedRoute.lastOrNull()

                // Find where the live trail deviates from the old GPX
                var splitIndex = -1
                if (oldGpxEnd != null) {
                    for (i in liveTrail.indices.reversed()) {
                        if (haversineMeters(liveTrail[i].lat, liveTrail[i].lon, oldGpxEnd.lat, oldGpxEnd.lon) < 20.0) {
                            splitIndex = i
                            break
                        }
                    }
                }

                // Add the walked road segment to the GPX route permanently
                val segmentToAdd = if (splitIndex != -1) liveTrail.subList(splitIndex + 1, liveTrail.size) else liveTrail
                importedRoute = importedRoute + segmentToAdd
            }

            // 2. CRITICAL: KILL THE ROAD ENGINE
            this.isFollowRoadMode = false
            this.activeRoadGraph = null
            this.followRoadCurrentNode = -1L
            this.followRoadTargetNode = -1L

            // 3. Reset index to the new end so it knows where to start "Turning Around"
            this.routeTargetIndex = importedRoute.size - 1
            this.routeDirection = -1
        }

        // Apply settings
        this.loopRouteBackwards = newLoopBackwards
        this.loopRouteContinuously = newLoopContinuously
        this.followRoadAfterGpx = newFollowRoadAfterGpx

        routePrefs.edit {
            putBoolean("loopBackwards", newLoopBackwards)
            putBoolean("loopContinuously", newLoopContinuously)
            putBoolean("followRoadAfterGpx", newFollowRoadAfterGpx)
            putBoolean("isFollowRoadMode", false) // Force OFF
            putBoolean("hasSelectedRouteBehavior", true)
            putInt("savedRouteIndex", routeTargetIndex)
            putInt("savedRouteDirection", routeDirection)
        }
    }

    fun onStepTick(
        totalSteps: Int,
        sessionStartSteps: Int,
        isSessionRunning: Boolean,
        onSyncStepBaseline: (Int) -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit,
        sessionStartTime: Long,
    ) {

        android.util.Log.d("RoadEngine", "isFollowRoadMode: $isFollowRoadMode, graphIsNull: ${activeRoadGraph == null}")
        if (lastStepCheckpoint == 0) {
            lastStepCheckpoint = totalSteps

            // Also reset the baseline so the UI shows '0' instead of the massive sensor number
            if (!isSessionRunning || sessionStartSteps == 0) {
                this.sessionStartSteps = totalSteps
            }
            return // Exit immediately without running the heavy math loop
        }

        if (!isSessionRunning) return

        if (isFollowRoadMode && activeRoadGraph == null) {
            return // Freeze guard — graph is downloading
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

        // We removed pendingCatchUpSteps to fix the "Double Step Counting" bug!
        val stepsSinceCheckpoint = totalSteps - lastStepCheckpoint
        if (totalSteps <= lastStepCheckpoint) return
        if (isFollowRoadMode && activeRoadGraph == null) return


        if (stepsSinceCheckpoint >= 10 || isCalculatingMassiveRoute) {
            val now = LocalDateTime.now()

            // ==========================================
            // CATCH UP MODE
            // ==========================================
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
                    var stepsSuccessfullySimulated = 0

                    // ---> COMPASS HEALING PROTOCOL (Prevents Amnesia U-Turns) <---
                    if (graph.nodes[simTargetId] == null) {
                        val closestNode = graph.getClosestNode(simLat, simLon)
                        if (closestNode != null) {
                            simCurrentId = closestNode.id

                            val neighbors = graph.adjacencyList[simCurrentId] ?: emptyList()
                            if (neighbors.isNotEmpty()) {
                                var bestNeighbor = neighbors.first().targetNodeId
                                var smallestTurn = 360.0

                                for (edge in neighbors) {
                                    val nNode = graph.nodes[edge.targetNodeId]
                                    if (nNode != null) {
                                        val bearing = calculateBearing(simLat, simLon, nNode.lat, nNode.lon)
                                        var diff = abs(bearing - lastRoadBearing)
                                        if (diff > 180) diff = 360 - diff

                                        if (diff < smallestTurn) {
                                            smallestTurn = diff
                                            bestNeighbor = edge.targetNodeId
                                        }
                                    }
                                }
                                simTargetId = bestNeighbor
                            } else {
                                simTargetId = closestNode.id
                            }
                        }
                    }

                    while (remainingDistance > 0) {

                        // ---> MASTER EDGE DETECTOR <---
                        val distFromChunk = com.example.stepcounter3.haversineMeters(simLat, simLon, mapChunkCenterLat, mapChunkCenterLon)
                        if (distFromChunk > (currentChunkRadius * 0.9)) {
                            currentLat = simLat
                            currentLon = simLon
                            lastCheckpointTime = simTime
                            followRoadCurrentNode = simCurrentId
                            followRoadTargetNode = simTargetId
                            hiddenCatchUpBuffer.addAll(fastForwardPoints)
                            isCalculatingMassiveRoute = true
                            lastStepCheckpoint += stepsSuccessfullySimulated


                            mapChunkCenterLat = simLat
                            mapChunkCenterLon = simLon
                            routePrefs.edit {
                                putFloat("chunkLat", simLat.toFloat())
                                putFloat("chunkLon", simLon.toFloat())
                                putLong("followRoadCurrentNode", simCurrentId)
                                putLong("followRoadTargetNode", simTargetId)
                                putLong("followRoadLastNode", followRoadLastNode)
                            }

                            isDownloadingGraph = true
                            activeRoadGraph = null
                            liveTrail = liveTrail + hiddenCatchUpBuffer
                            hiddenCatchUpBuffer.clear()

                            val currentDuration = System.currentTimeMillis() - sessionStartTime
                            val currentSessionSteps = totalSteps - sessionStartSteps
                            onTrailUpdated(liveTrail, currentSessionSteps, currentDuration)
                            return // Exit safely, let the UI download!
                        }

                        val targetNode = graph.nodes[simTargetId]
                        if (targetNode == null) break

                        val distToTarget = haversineMeters(simLat, simLon, targetNode.lat, targetNode.lon)
                        var pointToSaveLat = simLat
                        var pointToSaveLon = simLon

                        if (distToTarget <= stepDist) {
                            simLat = targetNode.lat
                            simLon = targetNode.lon
                            val safeDistance = distToTarget.coerceAtLeast(1.0)
                            remainingDistance -= safeDistance

                            // Ensure we don't accidentally add thousands of fake steps
                            stepsSuccessfullySimulated += Math.max(1, (safeDistance / strideLength).toInt())
                            pointToSaveLat = simLat
                            pointToSaveLon = simLon

                            val connectedEdges = graph.adjacencyList[targetNode.id] ?: emptyList()
                            if (!recentNodes.contains(simCurrentId)) {
                                recentNodes.add(simCurrentId)
                                if (recentNodes.size > MEMORY_SIZE) recentNodes.removeAt(0) // Keep it small

                                saveRecentNodesMemory()
                            }

                            val validNextEdges = connectedEdges.filter { it.targetNodeId != simCurrentId }
                            var nextEdge = validNextEdges.randomOrNull()

                            // ---> RAGGED EDGE DETECTOR <---
                            if (nextEdge == null) {
                                val distFromChunkCenter = haversineMeters(simLat, simLon, mapChunkCenterLat, mapChunkCenterLon)

                                if (distFromChunkCenter >( currentChunkRadius * 0.85)) {
                                    // Fake map edge! DO NOT U-TURN! Download the next map right now!
                                    currentLat = simLat
                                    currentLon = simLon
                                    lastCheckpointTime = simTime
                                    followRoadCurrentNode = simCurrentId
                                    followRoadTargetNode = simTargetId
                                    hiddenCatchUpBuffer.addAll(fastForwardPoints)
                                    isCalculatingMassiveRoute = true
                                    lastStepCheckpoint += stepsSuccessfullySimulated

                                    mapChunkCenterLat = simLat
                                    mapChunkCenterLon = simLon
                                    routePrefs.edit {
                                        putFloat("chunkLat", simLat.toFloat())
                                        putFloat("chunkLon", simLon.toFloat())
                                        putLong("followRoadCurrentNode", simCurrentId)
                                        putLong("followRoadTargetNode", simTargetId)
                                        putLong("followRoadLastNode", followRoadLastNode)
                                    }

                                    isDownloadingGraph = true
                                    activeRoadGraph = null
                                    liveTrail = liveTrail + hiddenCatchUpBuffer
                                    hiddenCatchUpBuffer.clear()

                                    val currentDuration = System.currentTimeMillis() - sessionStartTime
                                    val currentSessionSteps = totalSteps - sessionStartSteps
                                    onTrailUpdated(liveTrail , currentSessionSteps, currentDuration)
                                    return
                                } else {
                                    // Real dead end. U-Turn allowed!
                                    recentNodes.clear()

                                    saveRecentNodesMemory()
                                    nextEdge = connectedEdges.randomOrNull()
                                }
                            }

                            // ---> THE ENGINE (You missed this last time!) <---
                            if (nextEdge != null) {
                                followRoadLastNode = simCurrentId
                                simCurrentId = targetNode.id
                                simTargetId = nextEdge.targetNodeId
                            } else {
                                val recoveryNode = graph.getClosestNode(simLat, simLon)
                                if (recoveryNode != null && recoveryNode.id != targetNode.id) {
                                    simCurrentId = recoveryNode.id
                                    simTargetId = graph.adjacencyList[simCurrentId]?.randomOrNull()?.targetNodeId ?: recoveryNode.id
                                } else break
                            }

                        } else {
                            val roadBearing = calculateBearing(simLat, simLon, targetNode.lat, targetNode.lon)
                            lastRoadBearing = roadBearing // <--- SAVING THE COMPASS

                            val rad = Math.toRadians(roadBearing)
                            val metersPerDegLat = 111_320.0
                            val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(simLat))

                            simLat += (stepDist / metersPerDegLat) * kotlin.math.cos(rad)
                            simLon += (stepDist / metersPerDegLon) * kotlin.math.sin(rad)
                            remainingDistance -= stepDist
                            stepsSuccessfullySimulated += 2

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

                    routePrefs.edit {
                        putLong("followRoadCurrentNode", followRoadCurrentNode)
                        putLong("followRoadTargetNode", followRoadTargetNode)
                        putLong("followRoadLastNode", followRoadLastNode)
                    }

                    hiddenCatchUpBuffer.addAll(fastForwardPoints)
                    liveTrail = liveTrail + hiddenCatchUpBuffer // Push EVERYTHING to the map at once!
                    hiddenCatchUpBuffer.clear() // Wipe the buffer for next time
                    isCalculatingMassiveRoute = false // Turn off the loading UI
                    lastStepCheckpoint += stepsSuccessfullySimulated

                    saveRecentNodesMemory()

                } else {
                    val catchUpResult = com.example.stepcounter3.extendTrail(
                        startLat = currentLat, startLon = currentLon, startTime = lastCheckpointTime,
                        steps = stepsSinceCheckpoint, stepLengthMeters = strideLength, endTime = finalEndTime,
                        importedRoute = importedRoute, startingWaypointIndex = routeTargetIndex,
                        loopRouteBackwards = loopRouteBackwards, loopRouteContinuously = loopRouteContinuously,
                        initialRouteDirection = routeDirection,
                        stopAtRouteEnd = followRoadAfterGpx
                    )
                    val missedPath = catchUpResult.first
                    routeTargetIndex = catchUpResult.second
                    routeDirection = catchUpResult.third

                    routePrefs.edit {
                        putInt("savedRouteIndex", routeTargetIndex)
                        putInt("savedRouteDirection", routeDirection)
                    }

                    liveTrail = liveTrail + missedPath
                    if (missedPath.isNotEmpty()) {
                        val last = missedPath.last()
                        if (missedPath.size > 1) {
                            val secondLast = missedPath[missedPath.size - 2]
                            lastRoadBearing = com.example.stepcounter3.calculateBearing(secondLast.lat, secondLast.lon, last.lat, last.lon)
                        } else if (liveTrail.size > 1) {
                            val secondLast = liveTrail[liveTrail.size - 2]
                            lastRoadBearing = com.example.stepcounter3.calculateBearing(secondLast.lat, secondLast.lon, last.lat, last.lon)
                        }
                        currentLat = last.lat
                        currentLon = last.lon
                        lastCheckpointTime = last.time
                    }

                    if (followRoadAfterGpx && routeTargetIndex >= importedRoute.size) {
                        lastStepCheckpoint += missedPath.size
                        isFollowRoadMode = true
                        followRoadAfterGpx = false
                        routePrefs.edit {
                            putBoolean("isFollowRoadMode", true)
                            putBoolean("followRoadAfterGpx", false)
                        }

                        val startNode = activeRoadGraph?.nodes?.get(followRoadCurrentNode)
                        if (startNode != null) {
                            currentLat = startNode.lat
                            currentLon = startNode.lon
                        }
                        return
                    }
                }
            } else {
                // ==========================================
                // NORMAL MODE (Standard Walking)
                // ==========================================
                val distanceMeters = stepsSinceCheckpoint * strideLength

                if (isFollowRoadMode) {
                    val graph = activeRoadGraph!!

                    // ---> COMPASS HEALING PROTOCOL (Normal Mode) <---
                    if (graph.nodes[followRoadTargetNode] == null) {
                        val distFromChunk = haversineMeters(currentLat, currentLon, mapChunkCenterLat, mapChunkCenterLon)
                        if (distFromChunk < (currentChunkRadius*0.8)) {
                            val closestNode = graph.getClosestNode(currentLat, currentLon)
                            if (closestNode != null) {
                                followRoadCurrentNode = closestNode.id
                                val neighbors = graph.adjacencyList[followRoadCurrentNode] ?: emptyList()
                                if (neighbors.isNotEmpty()) {
                                    var bestNeighbor = neighbors.first().targetNodeId
                                    var smallestTurn = 360.0
                                    for (edge in neighbors) {
                                        val nNode = graph.nodes[edge.targetNodeId]
                                        if (nNode != null) {
                                            val bearing = calculateBearing(currentLat, currentLon, nNode.lat, nNode.lon)
                                            var diff = abs(bearing - lastRoadBearing)
                                            if (diff > 180) diff = 360 - diff
                                            if (diff < smallestTurn) {
                                                smallestTurn = diff
                                                bestNeighbor = edge.targetNodeId
                                            }
                                        }
                                    }
                                    followRoadTargetNode = bestNeighbor
                                } else {
                                    followRoadTargetNode = closestNode.id
                                }
                            }
                        } else {
                            mapChunkCenterLat = currentLat
                            mapChunkCenterLon = currentLon
                            routePrefs.edit {
                                putFloat("chunkLat", currentLat.toFloat())
                                putFloat("chunkLon", currentLon.toFloat())
                            }
                            isDownloadingGraph = true
                            activeRoadGraph = null
                            lastStepCheckpoint = totalSteps
                            return
                        }
                    }

                    val targetNode = graph.nodes[followRoadTargetNode]
                    if (targetNode != null) {
                        val distToTarget = haversineMeters(
                            currentLat, currentLon, targetNode.lat, targetNode.lon
                        )

                        if (distToTarget <= distanceMeters.coerceAtLeast(2.0)) {
                            currentLat = targetNode.lat
                            currentLon = targetNode.lon


                            val connectedEdges = graph.adjacencyList[targetNode.id] ?: emptyList()

                            if (!recentNodes.contains(followRoadCurrentNode)) {
                                recentNodes.add(followRoadCurrentNode)
                                if (recentNodes.size > MEMORY_SIZE) recentNodes.removeAt(0)
                            }
                            saveRecentNodesMemory()
                            val validNextEdges = connectedEdges.filter { it.targetNodeId != followRoadCurrentNode }
                            var nextEdge = validNextEdges.randomOrNull()

                            // ---> RAGGED EDGE DETECTOR (Normal Mode) <---
                            if (nextEdge == null) {
                                val distFromChunkCenter = com.example.stepcounter3.haversineMeters(currentLat, currentLon, mapChunkCenterLat, mapChunkCenterLon)

                                if (distFromChunkCenter > (currentChunkRadius *0.85)) {
                                    mapChunkCenterLat = currentLat
                                    mapChunkCenterLon = currentLon
                                    routePrefs.edit {
                                        putFloat("chunkLat", currentLat.toFloat())
                                        putFloat("chunkLon", currentLon.toFloat())
                                    }
                                    isDownloadingGraph = true
                                    activeRoadGraph = null
                                    lastStepCheckpoint = totalSteps
                                    return
                                } else {
                                    recentNodes.clear()

                                    saveRecentNodesMemory()
                                    nextEdge = connectedEdges.randomOrNull()
                                }
                            }

                            if (nextEdge != null) {
                                followRoadLastNode = followRoadCurrentNode
                                followRoadCurrentNode = targetNode.id
                                followRoadTargetNode = nextEdge.targetNodeId
                            }

                            routePrefs.edit {
                                putLong("followRoadCurrentNode", followRoadCurrentNode)
                                putLong("followRoadTargetNode", followRoadTargetNode)
                                putLong("followRoadLastNode", followRoadLastNode)
                            }

                            liveTrail = liveTrail + TrailPoint(currentLat, currentLon, now)
                            lastCheckpointTime = now
                        } else {
                            val roadBearing = calculateBearing(
                                currentLat, currentLon, targetNode.lat, targetNode.lon
                            )
                            lastRoadBearing = roadBearing // <--- SAVING THE COMPASS

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
                                routePrefs.edit {
                                    putInt("savedRouteIndex", routeTargetIndex)
                                    putInt("savedRouteDirection", routeDirection)
                                }
                            } else break
                        }

                        if (routeTargetIndex >= importedRoute.size) {
                            if (loopRouteContinuously) {
                                routeTargetIndex = 0
                                routeDirection = 1
                            } else if (loopRouteBackwards) {
                                routeDirection = -1
                                routeTargetIndex = (importedRoute.size - 2).coerceAtLeast(0)
                            } else if (followRoadAfterGpx) {
                                isFollowRoadMode = true
                                followRoadAfterGpx = false
                                routePrefs.edit {
                                    putBoolean("isFollowRoadMode", true)
                                    putBoolean("followRoadAfterGpx", false)
                                }
                                val startNode = activeRoadGraph?.nodes?.get(followRoadCurrentNode)
                                if (startNode != null) {
                                    currentLat = startNode.lat
                                    currentLon = startNode.lon
                                }
                                return
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
                            walkingDirection = com.example.stepcounter3.calculateBearing(
                                currentLat, currentLon, target.lat, target.lon
                            )
                            walkingDirection += (-2..2).random()
                        } else walkingDirection += (-10..10).random()
                    } else walkingDirection += (-10..10).random()

                    lastRoadBearing = walkingDirection
                    val rad = Math.toRadians(walkingDirection)
                    val metersPerDegLat = 111_320.0
                    val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(currentLat))

                    currentLat += (distanceMeters / metersPerDegLat) * Math.cos(rad)
                    currentLon += (distanceMeters / metersPerDegLon) * Math.sin(rad)

                    liveTrail = liveTrail + TrailPoint(currentLat, currentLon, now)
                    lastCheckpointTime = now
                }
            }

            // Normal Mode Master Edge Detector
            if (isFollowRoadMode && activeRoadGraph != null) {
                val distFromChunkCenter = haversineMeters(currentLat, currentLon, mapChunkCenterLat, mapChunkCenterLon)
                if (distFromChunkCenter > (currentChunkRadius * 0.9)) {
                    mapChunkCenterLat = currentLat
                    mapChunkCenterLon = currentLon
                    routePrefs.edit()
                        .putFloat("chunkLat", currentLat.toFloat())
                        .putFloat("chunkLon", currentLon.toFloat())
                        .apply()
                    isDownloadingGraph = true
                    activeRoadGraph = null
                }
            }

            // Permanently Save Compass
            routePrefs.edit().putFloat("lastRoadBearing", lastRoadBearing.toFloat()).apply()

            val currentDuration = System.currentTimeMillis() - sessionStartTime
            lastStepCheckpoint = totalSteps

            isCalculatingMassiveRoute = false
            val currentSessionSteps = totalSteps - sessionStartSteps
            onTrailUpdated(liveTrail + hiddenCatchUpBuffer, currentSessionSteps, currentDuration)
        }
    }

    fun resumeSession(
        context: Context,
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
            val startIntent = android.content.Intent(context, com.example.stepcounter3.StepService::class.java)
            context.startForegroundService(startIntent)

            isSessionRunning = true
            sessionStartSteps = totalSteps - historySteps
            sessionStartTime = System.currentTimeMillis() - historyDuration

            routePrefs.edit {
                putBoolean("isSessionRunning", true)
                putLong("sessionStartTime", sessionStartTime)
                putInt("sessionStartSteps", sessionStartSteps)
            }

            if (historyTrail.size <= 1 && importedRoute.isNotEmpty() && routeTargetIndex > 0 && !isFollowRoadMode) {
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

        lastSessionTrail = emptyList()
        lastSessionSteps = 0
        liveTrail = emptyList()

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
        context: Context,
        defaultLat: Double,
        defaultLon: Double,
        initialTrail: List<TrailPoint>,
        initialSteps: Int,
        initialDuration: Long,
        initialStride: Double,
        sessionStartSteps: Int
    ) {
        if (isInitialized) return
        isInitialized = true

        this.homeLat = defaultLat
        this.homeLon = defaultLon
        this.lastSessionSteps = initialSteps
        this.lastSessionDistance = (initialSteps * initialStride) / 1000.0
        this.lastSessionDuration = initialDuration
        this.lastSessionTrail = initialTrail
        this.importedRoute = loadRouteFromInternalStorage(context)
        this.liveTrail = initialTrail
        this.currentLat = initialTrail.lastOrNull()?.lat ?: homeLat
        this.currentLon = initialTrail.lastOrNull()?.lon ?: homeLon
        this.lastCheckpointTime = initialTrail.lastOrNull()?.time ?: LocalDateTime.now()
        this.lastStepCheckpoint = sessionStartSteps + initialSteps
        if (this.totalSteps == 0) {
            this.totalSteps = sessionStartSteps + initialSteps
        }
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
        context: Context,
        isNewWalk: Boolean,
        onClearSavedData: () -> Unit,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit
    ) {
        val startIntent = android.content.Intent(context, StepService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }

        isSessionRunning = true
        sessionStartTime = System.currentTimeMillis()
        sessionStartSteps = totalSteps

        routePrefs.edit()
            .putBoolean("isSessionRunning", true)
            .putLong("sessionStartTime", sessionStartTime)
            .putInt("sessionStartSteps", sessionStartSteps)
            .apply()

        if (isNewWalk) onClearSavedData()

        if (isNewWalk || liveTrail.isEmpty()) {
            if (isFollowRoadMode && followRoadCurrentNode != -1L) {
                val startNode = activeRoadGraph?.nodes?.get(followRoadCurrentNode)
                if (startNode != null) {
                    currentLat = startNode.lat
                    currentLon = startNode.lon
                } else if (lastSessionTrail.isNotEmpty()) {
                    currentLat = lastSessionTrail.last().lat
                    currentLon = lastSessionTrail.last().lon
                }
            } else if (importedRoute.isNotEmpty()) {
                val newSessionStart = routeTargetIndex.coerceIn(0, importedRoute.lastIndex.coerceAtLeast(0))
                currentLat = importedRoute[newSessionStart].lat
                currentLon = importedRoute[newSessionStart].lon
            }

            // ---> FIX: Removed the 'else { currentLat = homeLat }' trap here! <---
            // The engine will now completely trust the coordinates you passed it from the UI.

            if (isNewWalk) liveTrail = emptyList()
        } else {
            val lastPoint = liveTrail.last()
            currentLat = lastPoint.lat
            currentLon = lastPoint.lon
        }

        val startPoint = TrailPoint(currentLat, currentLon, LocalDateTime.now())
        liveTrail = if (liveTrail.isEmpty()) listOf(startPoint) else liveTrail + startPoint
        onTrailUpdated(liveTrail, 0, 0L)

        lastStepCheckpoint = totalSteps
        lastCheckpointTime = LocalDateTime.now()
        manualResumeTime = System.currentTimeMillis()
    }

    fun endSession(
        context: Context,
        onTrailUpdated: (List<TrailPoint>, Int, Long) -> Unit
    ) {
        val stopIntent = android.content.Intent(context, StepService::class.java)
        stopIntent.action = "STOP_SERVICE"
        context.startService(stopIntent)

        isSessionRunning = false
        routePrefs.edit().putBoolean("isSessionRunning", false).apply()

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
                    initialRouteDirection = routeDirection,
                    stopAtRouteEnd = followRoadAfterGpx
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

                val newHistoryItem = WalkHistoryItem(
                    timestamp = System.currentTimeMillis(),
                    steps = finalSessionSteps,
                    distance = finalDistanceKm,
                    trail = liveTrail.toList()
                )
                // Add to the front of the list, and keep only the newest 10!
                walkHistory = (listOf(newHistoryItem) + walkHistory).take(10)
                saveHistory(context)

                routePrefs.edit {
                    putInt("savedRouteIndex", routeTargetIndex)
                    putInt("savedRouteDirection", routeDirection)
                }

                isGeneratingTrail = false
            }
        }
    }
}
