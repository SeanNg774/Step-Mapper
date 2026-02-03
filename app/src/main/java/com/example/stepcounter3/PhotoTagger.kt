package com.example.stepcounter3

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class TagResult(
    val taggedCount: Int,
    val unmatchedUris: List<Uri>,
    val skippedCount: Int
)

object PhotoTagger {

    private const val TAG = "PhotoTagger"

    @RequiresApi(Build.VERSION_CODES.O)
    fun tagAuto(context: Context, trail: List<TrailPoint>, photoUris: List<Uri>): TagResult {
        if (trail.isEmpty()) {
            Log.e(TAG, "Tagging failed: Trail is empty")
            return TagResult(0, emptyList(), 0)
        }

        var taggedCount = 0
        var skippedCount = 0
        val unmatched = mutableListOf<Uri>()

        // 1. Establish Time Boundaries
        val trailStart = trail.first().time
        val trailEnd = trail.last().time
        val sessionStart = trailStart.minusMinutes(10) // Generous buffer
        val sessionEnd = trailEnd.plusMinutes(10)

        Log.d(TAG, "Trail Time Range: $trailStart to $trailEnd")

        for (uri in photoUris) {
            try {
                // 2. Get Accurate Time
                val photoTime = getPhotoTime(context, uri)

                if (photoTime == null) {
                    Log.w(TAG, "Could not extract time from photo: $uri")
                    unmatched.add(uri)
                    continue
                }

                Log.d(TAG, "Scanning Photo Time: $photoTime")

                // 3. Check Range
                if (photoTime.isAfter(sessionStart) && photoTime.isBefore(sessionEnd)) {

                    // 4. Find Closest Point
                    val location = findClosestLocation(trail, photoTime)

                    // Debug: Log the difference
                    val diffSeconds = abs(ChronoUnit.SECONDS.between(location.time, photoTime))
                    Log.d(TAG, "Matched to Point: ${location.time} (Diff: ${diffSeconds}s)")

                    if (writeExif(context, uri, location)) {
                        taggedCount++
                    } else {
                        unmatched.add(uri)
                    }
                } else {
                    Log.d(TAG, "Skipped: Photo time outside session range.")
                    skippedCount++
                }

            } catch (e: Exception) {
                e.printStackTrace()
                unmatched.add(uri)
            }
        }
        return TagResult(taggedCount, unmatched, skippedCount)
    }

    fun tagManual(context: Context, uris: List<Uri>, targetPoint: TrailPoint): Int {
        var count = 0
        for (uri in uris) {
            if (writeExif(context, uri, targetPoint)) {
                count++
            }
        }
        return count
    }

    private fun writeExif(context: Context, uri: Uri, point: TrailPoint): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(point.lat, point.lon)
                exif.saveAttributes()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EXIF: ${e.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPhotoTime(context: Context, uri: Uri): LocalDateTime? {
        // 1. Try EXIF "Original" tag (Best for Camera Photos)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)

                if (dateTimeOriginal != null) {
                    val cleanString = dateTimeOriginal.trim().replace("\u0000", "")
                    val pattern = if (cleanString.contains("-")) "yyyy-MM-dd HH:mm:ss" else "yyyy:MM:dd HH:mm:ss"
                    return LocalDateTime.parse(cleanString, DateTimeFormatter.ofPattern(pattern))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF Original parse failed: ${e.message}")
        }

        // 2. Try MediaStore (Best for Screenshots & Downloads)
        // We do this BEFORE the generic 'TAG_DATETIME' because MediaStore
        // handles UTC-to-Local conversion correctly.
        try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dateTaken = it.getLong(0)
                    if (dateTaken > 0) {
                        return LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(dateTaken),
                            ZoneId.systemDefault() // <--- This fixes the TimeZone issue
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore lookup failed")
        }

        // 3. Last Resort: Generic EXIF DateTime
        // (Often corresponds to file modified time, which might be UTC, causing the bug)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val dateTimeGeneric = exif.getAttribute(ExifInterface.TAG_DATETIME)

                if (dateTimeGeneric != null) {
                    val cleanString = dateTimeGeneric.trim().replace("\u0000", "")
                    val pattern = if (cleanString.contains("-")) "yyyy-MM-dd HH:mm:ss" else "yyyy:MM:dd HH:mm:ss"
                    return LocalDateTime.parse(cleanString, DateTimeFormatter.ofPattern(pattern))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF Generic parse failed")
        }

        return null
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun findClosestLocation(trail: List<TrailPoint>, time: LocalDateTime): TrailPoint {
        // Finds the point with the smallest time difference
        return trail.minByOrNull { point ->
            abs(ChronoUnit.SECONDS.between(point.time, time))
        } ?: trail.last()
    }
}