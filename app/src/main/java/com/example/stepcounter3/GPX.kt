package com.example.stepcounter3

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.format.DateTimeFormatter
import android.content.Context
import android.net.Uri
import java.io.File
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream
import java.time.Duration
import android.content.Intent
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime

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

@RequiresApi(Build.VERSION_CODES.O)
fun parseGpxFile(inputStream: InputStream): List<TrailPoint> {
    val points = mutableListOf<TrailPoint>()
    val parser: XmlPullParser = Xml.newPullParser()

    // 1. Create our Synthetic Timer (Starts exactly right now)
    var fallbackTime = LocalDateTime.now()

    try {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentParsedTime: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: ""

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    // GPX files can use <trkpt>, <rtept>, or <wpt> depending on how they were generated
                    if (name.equals("trkpt", ignoreCase = true) ||
                        name.equals("rtept", ignoreCase = true) ||
                        name.equals("wpt", ignoreCase = true)) {

                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentParsedTime = null // Reset the time string for this new point
                    }
                    else if (name.equals("time", ignoreCase = true)) {
                        // If we are currently inside a point and we find a <time> tag, extract it!
                        if (currentLat != null && currentLon != null) {
                            currentParsedTime = parser.nextText()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    // When we reach the closing tag of the point, bundle it all together
                    if (name.equals("trkpt", ignoreCase = true) ||
                        name.equals("rtept", ignoreCase = true) ||
                        name.equals("wpt", ignoreCase = true)) {

                        if (currentLat != null && currentLon != null) {

                            // --- SYNTHETIC TIMESTAMP LOGIC ---
                            val finalTime = if (!currentParsedTime.isNullOrBlank()) {
                                try {
                                    // Try to parse the real timestamp (Works for Strava/Garmin files)
                                    LocalDateTime.parse(currentParsedTime, DateTimeFormatter.ISO_DATE_TIME)
                                } catch (e: Exception) {
                                    // The time format was weird! Fallback to synthetic time.
                                    val tempTime = fallbackTime
                                    fallbackTime = fallbackTime.plusSeconds(1)
                                    tempTime
                                }
                            } else {
                                // 3. NO TIME TAG FOUND! (Internet drawn route). Inject synthetic time!
                                val tempTime = fallbackTime
                                fallbackTime = fallbackTime.plusSeconds(1)
                                tempTime
                            }

                            // Add the safe, time-stamped point to our array
                            points.add(TrailPoint(currentLat, currentLon, finalTime))
                        }

                        // Clear the variables ready for the next point in the loop
                        currentLat = null
                        currentLon = null
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        inputStream.close()
    }

    return points
}

fun saveRouteToInternalStorage(context: Context, uri: Uri) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.filesDir, "active_route.gpx")
        inputStream?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// Loads the copy instantly when the app reboots
@RequiresApi(Build.VERSION_CODES.O)
fun loadRouteFromInternalStorage(context: Context): List<TrailPoint> {
    val file = File(context.filesDir, "active_route.gpx")
    if (!file.exists()) return emptyList()
    return try {
        parseGpxFile(file.inputStream())
    } catch (e: Exception) {
        emptyList()
    }
}

fun clearRouteFromInternalStorage(context: Context) {
    val file = java.io.File(context.filesDir, "active_route.gpx")
    if (file.exists()) {
        file.delete()
    }
}
