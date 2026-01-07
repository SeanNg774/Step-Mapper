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

    val startTime = points.first().time
    val endTime = points.last().time
    val durationSeconds = Duration.between(startTime, endTime).seconds
    val totalDistanceMeters = points.zipWithNext { a, b ->
        haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }.sum()
    val avgSpeedKmh = if (durationSeconds > 0) totalDistanceMeters / durationSeconds * 3.6 else 0.0

    val trackPointsXml = points.zipWithNext { a, b ->
        val dist = haversineMeters(a.lat, a.lon, b.lat, b.lon)
        val duration = Duration.between(a.time, b.time).seconds
        val speedKmh = if (duration > 0) dist / duration * 3.6 else 0.0
        val utcTime = b.time.atZone(java.time.ZoneId.systemDefault()).toInstant()

        """<trkpt lat="${b.lat}" lon="${b.lon}">
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
fun saveGpxFile(context: Context, fileName: String, gpxData: String): Uri {
    val file = File(context.filesDir, fileName)
    file.writeText(gpxData)

    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
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