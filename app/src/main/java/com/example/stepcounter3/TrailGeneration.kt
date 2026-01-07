package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.*
import java.time.LocalDateTime


data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val time: LocalDateTime
)

object TrailGenerator {

    @RequiresApi(Build.VERSION_CODES.O)
    fun generateTrail(
        steps: Int,
        startLat: Double,
        startLon: Double,
        stepLengthMeters: Double = 0.7,
    ): List<TrailPoint> {

        val totalDistance = steps * stepLengthMeters
        val points = mutableListOf<TrailPoint>()

        var currentLat = startLat
        var currentLon = startLon
        var direction = Math.random() * 360
        var currentTime = LocalDateTime.now()

        points.add(TrailPoint(currentLat, currentLon, currentTime))

        var distanceWalked = 0.0

        while (distanceWalked < totalDistance) {

            // Move 1 to 3 meters randomly
            val stepDist = (1..3).random().toDouble()

            val rad = Math.toRadians(direction)
            val dLat = (stepDist / 111_320.0) * cos(rad)
            val dLon = (stepDist / 111_320.0) * sin(rad)

            currentLat += dLat
            currentLon += dLon

            distanceWalked += stepDist

            // Add slight direction change ±20°
            direction += (-20..20).random()

            // Add timestamp
            currentTime = currentTime.plusSeconds(stepDist.toLong())

            points.add(TrailPoint(currentLat, currentLon, currentTime))
        }

        return points
    }
}


@RequiresApi(Build.VERSION_CODES.O)
fun extendTrail(
    startLat: Double,
    startLon: Double,
    startTime: LocalDateTime,
    steps: Int,
    stepLengthMeters: Double = 0.7,
): List<TrailPoint> {

    if (steps <= 0) return emptyList()

    val points = mutableListOf<TrailPoint>()
    var lat = startLat
    var lon = startLon
    var direction = Math.random() * 360
    var time = startTime

    repeat(steps) {

        // 🔥 VARIABLE step cadence (seconds per step)
        val secondsPerStep = listOf(0.4, 0.6, 0.8, 1.0, 1.2).random()

        val rad = Math.toRadians(direction)

        lat += (stepLengthMeters / 111_320.0) * cos(rad)
        lon += (stepLengthMeters / 111_320.0) * sin(rad)

        time = time.plusNanos((secondsPerStep * 1_000_000_000).toLong())

        points.add(TrailPoint(lat, lon, time))

        direction += (-10..10).random()
    }

    return points
}


