package com.example.stepcounter3

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.ZoneOffset

class TrailRepository(context: Context) {

    // Matches the Class Diagram: Private SharedPreferences instance
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)

    // Matches the Class Diagram: In-Memory MutableList mapped to a StateFlow for the UI to observe
    private val _activeSession = MutableStateFlow<List<TrailPoint>>(emptyList())
    val activeSession: StateFlow<List<TrailPoint>> = _activeSession.asStateFlow()

    init {
        // Automatically load the saved trail from disk when the app starts (Crash Recovery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val savedTrailString = sharedPrefs.getString("savedTrail", "") ?: ""
            _activeSession.value = stringToTrail(savedTrailString)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun addPoint(point: TrailPoint, currentSteps: Int, durationMillis: Long) {
        val currentList = _activeSession.value.toMutableList()
        currentList.add(point)
        val newList = currentList.toList()

        // 1. Update In-Memory List
        _activeSession.value = newList

        // 2. Async Backup to SharedPreferences (Matches Sequence Diagram)
        saveTrailToPrefs(newList, currentSteps, durationMillis)
    }

    fun getAllPoints(): List<TrailPoint> {
        return _activeSession.value
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun clearSession() {
        _activeSession.value = emptyList()
        sharedPrefs.edit().apply {
            putString("savedTrail", "")
            putInt("savedTrailSteps", 0)
            putLong("savedTrailDuration", 0L)
        }.apply() // .apply() is asynchronous
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun saveTrailToPrefs(trail: List<TrailPoint>, steps: Int, durationMillis: Long) {
        sharedPrefs.edit().apply {
            putString("savedTrail", trailToString(trail))
            putInt("savedTrailSteps", steps)
            putLong("savedTrailDuration", durationMillis)
        }.apply()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun trailToString(trail: List<TrailPoint>): String {
        return trail.joinToString(";") { point ->
            "${point.lat},${point.lon},${point.time.toEpochSecond(ZoneOffset.UTC)}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stringToTrail(data: String): List<TrailPoint> {
        if (data.isBlank()) return emptyList()
        return data.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size == 3) {
                TrailPoint(
                    lat = parts[0].toDouble(),
                    lon = parts[1].toDouble(),
                    time = LocalDateTime.ofEpochSecond(parts[2].toLong(), 0, ZoneOffset.UTC)
                )
            } else null
        }
    }

    // --- Extra Helpers for ViewModel State Restoration ---

    fun getSavedTrailSteps(): Int {
        return sharedPrefs.getInt("savedTrailSteps", 0)
    }

    fun getSavedTrailDuration(): Long {
        return sharedPrefs.getLong("savedTrailDuration", 0L)
    }

    fun getSharedPrefs(): SharedPreferences {
        return sharedPrefs
    }
}