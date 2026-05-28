package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Duration
import kotlin.math.*
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val time: LocalDateTime
)



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
    loopRouteBackwards: Boolean = false, // NEW
    loopRouteContinuously: Boolean = false,
    initialRouteDirection: Int = 1
): Triple<List<TrailPoint>, Int, Int > {

    if (steps <= 0) return Triple(emptyList(), startingWaypointIndex, initialRouteDirection)
    val points = mutableListOf<TrailPoint>()
    var lat = startLat
    var lon = startLon
    var direction = Math.random() * 360 // start facing a random direction
    var currentTime = startTime
    var currentIndex = startingWaypointIndex
    var currentRouteDir = initialRouteDirection

    // Generate random weights
    // creates fast and slow steps just like real walking
    val rawWeights = List(steps) { kotlin.random.Random.nextDouble(0.5, 1.5) }
    val sumWeights = rawWeights.sum()

    // Calculate the exact nanoseconds the app was asleep.
    // scales them to fit the exact total duration perfectly.
    // This ensures Total Time is still mathematically correct for EXIF feature.
    val totalDurationNanos = Duration.between(startTime, endTime).toNanos()
    val baseUnitNanos = if (sumWeights > 0) totalDurationNanos / sumWeights else 1_000_000_000.0

    for (i in 0 until steps) {
        val stepDurationNanos = (rawWeights[i] * baseUnitNanos).toLong()

        if (importedRoute.size > 1) {
            // 1. Advance index in our current direction
            while (currentIndex >= 0 && currentIndex < importedRoute.size) {

                // NEW: Force 1.5m accuracy for the ends, allow 5.0m corner-cutting for the middle
                val isEndPoint = (currentIndex == 0 || currentIndex == importedRoute.size - 1)
                val hitRadius = if (isEndPoint) 1.5 else 5.0

                if (haversineMeters(lat, lon, importedRoute[currentIndex].lat, importedRoute[currentIndex].lon) < hitRadius) {
                    currentIndex += currentRouteDir
                } else {
                    break // We haven't reached this point yet, stop advancing!
                }
            }

            // 2. Bounce at the ends of the array!
            if (currentIndex >= importedRoute.size) {
                if (loopRouteContinuously) {
                    currentIndex = 0 // Wrap instantly back to the start!
                    currentRouteDir = 1 // Ensure we keep moving forward
                } else if (loopRouteBackwards) {
                    currentRouteDir = -1 // Reverse!
                    currentIndex = (importedRoute.size - 2).coerceAtLeast(0)
                }
            } else if (currentIndex < 0) {
                if (loopRouteContinuously) {
                    currentIndex = importedRoute.size - 1 // Wrap backwards (edge case)
                    currentRouteDir = -1
                } else if (loopRouteBackwards) {
                    currentRouteDir = 1 // Forward again!
                    currentIndex = 1.coerceAtMost(importedRoute.size - 1)
                }
            }

            // 3. Aim at target
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
        // Translate the scalar distance (stride length) into vector coordinates (Lat/Lon).
        // Longitude is scaled using cosine to account for Earth's curvature.
        val rad = Math.toRadians(direction)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat))

        lat += (stepLengthMeters / metersPerDegLat) * cos(rad)
        lon += (stepLengthMeters / metersPerDegLon) * sin(rad)


        // Advance the timeline by this step's exact calculated duration
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