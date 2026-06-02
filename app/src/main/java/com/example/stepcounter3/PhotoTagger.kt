package com.example.stepcounter3

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class TagResult(
    val taggedCount: Int,
    val unmatchedUris: List<Uri>
)

object PhotoTagger {

    private const val TAG = "PhotoTagger"

    // Formatter for EXIF standard: "yyyy:MM:dd HH:mm:ss"
    @RequiresApi(Build.VERSION_CODES.O)
    private val EXIF_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * Tries to auto-tag photos based on their timestamp.
     * Returns a list of URIs that could NOT be matched or written.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun tagAuto(context: Context, trail: List<TrailPoint>, uris: List<Uri>): TagResult {
        val unmatched = mutableListOf<Uri>()
        var taggedCount = 0

        if (trail.isEmpty()) return TagResult(0, uris)

        val trailStart = trail.first().time
        val trailEnd = trail.last().time

        for (uri in uris) {
            try {
                //  Get creation time of photo
                val photoTime = getPhotoTime(context, uri)

                if (photoTime == null) {
                    Log.w(TAG, "Could not determine time for: $uri")
                    unmatched.add(uri)
                    continue
                }

                // Check if time is roughly within the trail range (plus buffer)
                // 10-minute buffer to catch slight clock drifts or pre-walk photos
                if (isTimeWithinRange(photoTime, trailStart, trailEnd, bufferMinutes = 10)) {

                    // 3. Find closest point
                    val bestPoint = findClosestPoint(trail, photoTime)

                    if (bestPoint != null) {
                        // 4. WRITE EXIF
                        val success = writeExif(context, uri, bestPoint.lat, bestPoint.lon)
                        if (success) {
                            taggedCount++
                        } else {
                            unmatched.add(uri) // Write failed
                        }
                    } else {
                        unmatched.add(uri) // No close point found
                    }
                } else {
                    Log.d(TAG, "Time $photoTime outside trail range ($trailStart - $trailEnd)")
                    unmatched.add(uri) // Outside time range
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing $uri", e)
                unmatched.add(uri)
            }
        }

        return TagResult(taggedCount, unmatched)
    }

    /**
     * Manually tags a list of URIs with a specific point.
     */
    fun tagManual(context: Context, uris: List<Uri>, point: TrailPoint): Int {
        var count = 0
        for (uri in uris) {
            if (writeExif(context, uri, point.lat, point.lon)) {
                count++
            }
        }
        return count
    }

    /**
     * The Logic:
     * 1. Try reading EXIF 'DateTimeOriginal' or 'DateTime'
     * 2. If missing (screenshots), query Android MediaStore for 'DATE_TAKEN'
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPhotoTime(context: Context, uri: Uri): LocalDateTime? {
        // Try EXIF first (Fastest, most accurate for Photos)
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                if (dateString != null) {
                    // Parse "yyyy:MM:dd HH:mm:ss"
                    // EXIF has no timezone, so we assume it matches the device's current wall-clock time
                    // which matches our TrailPoint.time (LocalDateTime.now)
                    return try {
                        LocalDateTime.parse(dateString, EXIF_DATE_FORMAT)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF stream", e)
        }

        // 2. Fallback: Document File Modification Time
        // (Safest fallback for URIs returned by OpenMultipleDocuments)
        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val lastModifiedMillis = documentFile?.lastModified()

            if (lastModifiedMillis != null && lastModifiedMillis > 0) {
                return Instant.ofEpochMilli(lastModifiedMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read file modification time", e)
        }

        // 3. Complete failure (Return null so it goes to your unmatchedUris list)
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isTimeWithinRange(
        time: LocalDateTime,
        start: LocalDateTime,
        end: LocalDateTime,
        bufferMinutes: Long
    ): Boolean {
        val s = start.minusMinutes(bufferMinutes)
        val e = end.plusMinutes(bufferMinutes)
        return (time.isAfter(s) || time.isEqual(s)) && (time.isBefore(e) || time.isEqual(e))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun findClosestPoint(trail: List<TrailPoint>, time: LocalDateTime): TrailPoint? {
        // Simple linear search (Trail is usually short enough).
        // For huge trails, binary search would be better.
        var bestPoint: TrailPoint? = null
        var minDiffSeconds = Long.MAX_VALUE

        for (point in trail) {
            val diff = abs(Duration.between(point.time, time).seconds)
            if (diff < minDiffSeconds) {
                minDiffSeconds = diff
                bestPoint = point
            }
        }


        return bestPoint
    }

    private fun writeExif(context: Context, uri: Uri, lat: Double, lon: Double): Boolean {
        try {
            // Open in Read-Write mode ("rw")
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->

                val exif = ExifInterface(pfd.fileDescriptor)

                // 1. Format Latitude
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toExifDMS(lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat > 0) "N" else "S")

                // 2. Format Longitude
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toExifDMS(lon))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon > 0) "E" else "W")

                // 3. Save
                exif.saveAttributes()
                return true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write EXIF to $uri", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for $uri", e)
        }
        return false
    }

    /**
     * Helper: Converts decimal coordinates (e.g. 3.14159) to EXIF format (e.g. "3/1,8/1,29/1")
     */
    private fun toExifDMS(decimal: Double): String {
        val absolute = abs(decimal)
        val degrees = absolute.toInt()
        val minutes = ((absolute - degrees) * 60).toInt()
        val seconds = (absolute - degrees - minutes / 60.0) * 3600 * 1000 // Multiply by 1000 for precision

        // Format: "num/denom,num/denom,num/denom"
        // We use 1000 as denominator for seconds to keep 3 decimal places of precision
        return "$degrees/1,$minutes/1,${seconds.toInt()}/1000"
    }}