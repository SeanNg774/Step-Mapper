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



@RequiresApi(Build.VERSION_CODES.O)
fun extendTrail(
    startLat: Double,
    startLon: Double,
    startTime: LocalDateTime,
    steps: Int,
    stepLengthMeters: Double ,
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


