package com.example.stepcounter3

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.time.LocalDateTime
import java.time.ZoneOffset

data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val time: LocalDateTime
)
@RequiresApi(Build.VERSION_CODES.O)
fun trailToString(trail: List<TrailPoint>): String {
    return trail.joinToString(";") { "${it.lat},${it.lon},${it.time.toEpochSecond(ZoneOffset.UTC)}" }
}

@RequiresApi(Build.VERSION_CODES.O)
fun stringToTrail(data: String): List<TrailPoint> {
    if (data.isBlank()) return emptyList()
    return data.split(";").mapNotNull { entry ->
        val parts = entry.split(",")
        if (parts.size == 3) TrailPoint(
            parts[0].toDouble(),
            parts[1].toDouble(),
            LocalDateTime.ofEpochSecond(parts[2].toLong(),
                0,
                ZoneOffset.UTC))
        else
            null
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun saveTrailToPrefs(context: Context, trail: List<TrailPoint>, steps: Int, durationMillis: Long) {
    context.getSharedPreferences("myPrefs", Context.MODE_PRIVATE).edit {
        putString("savedTrail", trailToString(trail))
        putInt("savedTrailSteps", steps)
        putLong("savedTrailDuration", durationMillis)
    }
}


