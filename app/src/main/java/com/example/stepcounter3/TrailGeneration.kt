package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Duration
import kotlin.math.*
import java.time.LocalDateTime


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
): List<TrailPoint> {

    if (steps <= 0) return emptyList()

    val points = mutableListOf<TrailPoint>()
    var lat = startLat
    var lon = startLon
    var direction = Math.random() * 360
    var currentTime = startTime

    // 1. HYBRID MAGIC: Generate random weights
    // This creates "fast steps" and "slow steps" just like real walking...
    val rawWeights = List(steps) { kotlin.random.Random.nextDouble(0.5, 1.5) }
    val sumWeights = rawWeights.sum()

    // 2. ...but scales them to fit the EXACT total duration perfectly.
    // This ensures your Total Time is still mathematically perfect for photos.
    val totalDurationNanos = java.time.Duration.between(startTime, endTime).toNanos()
    val baseUnitNanos = if (sumWeights > 0) totalDurationNanos / sumWeights else 1_000_000_000.0

    for (i in 0 until steps) {
        val stepDurationNanos = (rawWeights[i] * baseUnitNanos).toLong()

        val rad = Math.toRadians(direction)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat))

        lat += (stepLengthMeters / metersPerDegLat) * cos(rad)
        lon += (stepLengthMeters / metersPerDegLon) * sin(rad)

        currentTime = currentTime.plusNanos(stepDurationNanos)

        points.add(TrailPoint(lat, lon, currentTime))

        direction += (-10..10).random()
    }

    return points
}
