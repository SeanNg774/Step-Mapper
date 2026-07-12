package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Duration
import kotlin.math.*
import java.time.LocalDateTime




@RequiresApi(Build.VERSION_CODES.O)
fun extendTrail(
    startLat: Double,
    startLon: Double,
    startTime: LocalDateTime,
    endTime: LocalDateTime,
    steps: Int,
    stepLengthMeters: Double,
    importedRoute: List<TrailPoint> = emptyList(),
    startingWaypointIndex:Int = 0,
    loopRouteBackwards: Boolean = false,
    loopRouteContinuously: Boolean = false,
    initialRouteDirection: Int = 1,
    stopAtRouteEnd: Boolean = false
): Triple<List<TrailPoint>, Int, Int > {

    if (steps <= 0) return Triple(emptyList(), startingWaypointIndex, initialRouteDirection)
    val points = mutableListOf<TrailPoint>()
    var lat = startLat
    var lon = startLon
    var direction = Math.random() * 360
    var currentTime = startTime
    var currentIndex = startingWaypointIndex
    var currentRouteDir = initialRouteDirection

    val rawWeights = List(steps) { kotlin.random.Random.nextDouble(0.5, 1.5) }
    val sumWeights = rawWeights.sum()

    val totalDurationNanos = Duration.between(startTime, endTime).toNanos()
    val baseUnitNanos = if (sumWeights > 0) totalDurationNanos / sumWeights else 1_000_000_000.0

    for (i in 0 until steps) {
        val stepDurationNanos = (rawWeights[i] * baseUnitNanos).toLong()

        if (importedRoute.size > 1) {
            while (currentIndex >= 0 && currentIndex < importedRoute.size) {
                val isEndPoint = (currentIndex == 0 || currentIndex == importedRoute.size - 1)
                val hitRadius = if (isEndPoint) 1.5 else 5.0

                if (haversineMeters(lat, lon, importedRoute[currentIndex].lat, importedRoute[currentIndex].lon) < hitRadius) {
                    currentIndex += currentRouteDir
                } else {
                    break
                }
            }

            if (currentIndex >= importedRoute.size) {
                if (loopRouteContinuously) {
                    currentIndex = 0
                    currentRouteDir = 1
                } else if (loopRouteBackwards) {
                    currentRouteDir = -1
                    currentIndex = (importedRoute.size - 2).coerceAtLeast(0)
                } else if (stopAtRouteEnd) {
                    break
                }
            } else if (currentIndex < 0) {
                if (loopRouteContinuously) {
                    currentIndex = importedRoute.size - 1
                    currentRouteDir = -1
                } else if (loopRouteBackwards) {
                    currentRouteDir = 1
                    currentIndex = 1.coerceAtMost(importedRoute.size - 1)
                } else if (stopAtRouteEnd) {
                    break
                }
            }

            if (currentIndex >= 0 && currentIndex < importedRoute.size) {
                val target = importedRoute[currentIndex]
                direction = calculateBearing(lat, lon, target.lat, target.lon)
                direction += (-2..2).random()
            } else {
                direction += (-10..10).random()
            }
        } else {
            direction += (-10..10).random()
        }

        val rad = Math.toRadians(direction)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat))

        lat += (stepLengthMeters / metersPerDegLat) * cos(rad)
        lon += (stepLengthMeters / metersPerDegLon) * sin(rad)

        currentTime = currentTime.plusNanos(stepDurationNanos)
        points.add(TrailPoint(lat, lon, currentTime))
    }

    return Triple(points, currentIndex, currentRouteDir)
}

fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)

    val y = sin(dLon) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

    var bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360) % 360 // Keeps the angle strictly between 0 and 360
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}