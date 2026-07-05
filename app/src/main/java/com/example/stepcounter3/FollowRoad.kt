import android.os.Build
import androidx.annotation.RequiresApi
import com.example.stepcounter3.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin

// Represents a single coordinate on the road (intersections or curves)
data class MapNode(val id: Long, val lat: Double, val lon: Double)

// Represents a connected segment of road
data class MapEdge(val targetNodeId: Long, val wayId: Long)

class RoadGraph {
    val nodes = mutableMapOf<Long, MapNode>()
    val adjacencyList = mutableMapOf<Long, MutableList<MapEdge>>()

    fun addEdge(fromId: Long, toId: Long, wayId: Long) {
        adjacencyList.getOrPut(fromId) { mutableListOf() }.add(MapEdge(toId, wayId))
        adjacencyList.getOrPut(toId) { mutableListOf() }.add(MapEdge(fromId, wayId))
    }

    // ---> NEW: THE SNAPPING ALGORITHM <---
    fun getClosestNode(targetLat: Double, targetLon: Double): MapNode? {
        if (nodes.isEmpty()) return null

        var closestNode: MapNode? = null
        var shortestDistance = Double.MAX_VALUE

        for ((_, node) in nodes) {
            val distance = calculateDistance(targetLat, targetLon, node.lat, node.lon)
            if (distance < shortestDistance) {
                shortestDistance = distance
                closestNode = node
            }
        }
        return closestNode
    }

    // Standard Haversine formula to measure real-world distance in meters
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6378137.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }
}
// This must run on a background thread!
// This must run on a background thread!
suspend fun fetchRoadGraph(centerLat: Double, centerLon: Double, radiusMeters: Int): RoadGraph {
    return withContext(Dispatchers.IO) {
        val graph = RoadGraph()
        try {
            // 1. The Smart City Query (Filters out highways and buildings)
            val query = """
                [out:json][timeout:45];
(
  way["highway"~"residential|path|footway|living_street|track|tertiary|unclassified|secondary|service|primary|trunk|motorway_link"](around:$radiusMeters,$centerLat,$centerLon);
);
(._;>;);
out skel qt ; 
            """.trimIndent()


            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val urlString = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

            // 2. Proper Android Network Connection
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"

            // ---> FIX: OVERPASS REQUIRES A USER-AGENT! <---
            // Without this, they actively block your app and return "No roads found"
            connection.setRequestProperty("User-Agent", "StepCounterFYP/1.0 (Android)")
            connection.connectTimeout = 15000
            connection.readTimeout = 35000

            if (connection.responseCode != 200) {
                android.util.Log.e("OverpassError", "Blocked: ${connection.responseCode}")
                return@withContext graph // Silently fail so the UI can retry
            }

            // 3. Read and Parse JSON
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonObject = org.json.JSONObject(response)
            val elements = jsonObject.getJSONArray("elements")
            android.util.Log.d("MapDebug", "Downloaded ${elements.length()} elements at $centerLat, $centerLon")

// Add this:
            if (elements.length() == 0) {
                android.util.Log.e("MapDebug", "ABSOLUTE DEAD ZONE: No map data returned!")
            } else {
                // Check if we got any 'ways' (roads)
                var wayCount = 0
                for (i in 0 until elements.length()) {
                    if (elements.getJSONObject(i).getString("type") == "way") wayCount++
                }
                android.util.Log.d("MapDebug", "Total ways found: $wayCount")
            }
            // First Pass: Nodes
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.getString("type") == "node") {
                    val id = element.getLong("id")
                    val lat = element.getDouble("lat")
                    val lon = element.getDouble("lon")
                    graph.nodes[id] = MapNode(id, lat, lon)
                }
            }

            // Second Pass: Edges
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.getString("type") == "way") {
                    val wayId = element.getLong("id")
                    val nodesArray = element.getJSONArray("nodes")
                    for (j in 0 until nodesArray.length() - 1) {
                        val fromId = nodesArray.getLong(j)
                        val toId = nodesArray.getLong(j + 1)
                        if (graph.nodes.containsKey(fromId) && graph.nodes.containsKey(toId)) {
                            graph.addEdge(fromId, toId, wayId)}
                    }
                }
            }
            return@withContext graph

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext graph
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
// Generates a point exactly "offsetMeters" away from the center of the road
fun addNoiseToCoordinate(baseLat: Double, baseLon: Double, roadBearing: Double, offsetMeters: Double): TrailPoint {
    val earthRadiusMeters = 6378137.0

    val perpendicularBearing = Math.toRadians(roadBearing + 90.0)
    val angularDistance = offsetMeters / earthRadiusMeters

    val latRad = Math.toRadians(baseLat)
    val lonRad = Math.toRadians(baseLon)

    val newLatRad = kotlin.math.asin(kotlin.math.sin(latRad) * kotlin.math.cos(angularDistance) +
            kotlin.math.cos(latRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(perpendicularBearing))

    val newLonRad = lonRad + kotlin.math.atan2(kotlin.math.sin(perpendicularBearing) * kotlin.math.sin(angularDistance) * kotlin.math.cos(latRad),
        kotlin.math.cos(angularDistance) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad))

    return TrailPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad), java.time.LocalDateTime.now())
}