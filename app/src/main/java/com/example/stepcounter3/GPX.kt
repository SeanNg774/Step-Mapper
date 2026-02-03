package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.format.DateTimeFormatter
import com.example.stepcounter3.TrailPoint
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream
import java.time.Duration
import kotlin.math.*
import android.content.Intent


fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}


@RequiresApi(Build.VERSION_CODES.O)
fun buildGpxXml(points: List<TrailPoint>, name: String = "TrailRun"): String {
    if (points.isEmpty()) return ""

    val formatter = DateTimeFormatter.ISO_INSTANT

    // Calculate total stats
    val startTime = points.first().time
    val endTime = points.last().time
    val durationSeconds = Duration.between(startTime, endTime).seconds
    val totalDistanceMeters = points.zipWithNext { a, b ->
        haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }.sum()
    val avgSpeedKmh = if (durationSeconds > 0) totalDistanceMeters / durationSeconds * 3.6 else 0.0

    // GENERATE XML (Single Loop)
    val trackPointsXml = points.mapIndexed { index, point ->
        val utcTime = point.time.atZone(java.time.ZoneId.systemDefault()).toInstant()

        // Calculate speed only if we have a previous point
        val speedKmh = if (index == 0) {
            0.0 // Start point has 0 speed
        } else {
            val prev = points[index - 1]
            val dist = haversineMeters(prev.lat, prev.lon, point.lat, point.lon)
            val duration = Duration.between(prev.time, point.time).seconds
            if (duration > 0) dist / duration * 3.6 else 0.0
        }

        """<trkpt lat="${point.lat}" lon="${point.lon}">
            <time>${formatter.format(utcTime)}</time>
            <extensions>
                <speed>${"%.2f".format(speedKmh)}</speed>
            </extensions>
        </trkpt>""".trimIndent()
    }.joinToString("\n")

    return """
        <gpx version="1.1" creator="IndoorWalkApp" xmlns="http://www.topografix.com/GPX/1/1">
            <metadata>
                <name>$name</name>
                <desc>Total Distance: ${"%.2f".format(totalDistanceMeters/1000.0)} km, Duration: ${durationSeconds}s, Avg Speed: ${"%.2f".format(avgSpeedKmh)} km/h</desc>
            </metadata>
            <trk>
                <name>$name</name>
                <trkseg>
                    $trackPointsXml
                </trkseg>
            </trk>
        </gpx>
    """.trimIndent()
}

@RequiresApi(Build.VERSION_CODES.Q)
fun saveGpxToDownloads(context: Context, fileName: String, gpxData: String): Uri? {
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { stream: OutputStream ->
            stream.write(gpxData.toByteArray())
            stream.flush()
        }
        Toast.makeText(context, "GPX saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
    } ?: run {
        Toast.makeText(context, "Failed to save GPX", Toast.LENGTH_SHORT).show()
    }

    return uri
}

fun shareGpxFile(context: android.content.Context, uri: android.net.Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/xml" // "application/gpx+xml" is correct but "text/xml" is more compatible
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Route"))
}