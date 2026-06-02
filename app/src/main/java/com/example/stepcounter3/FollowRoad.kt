import android.os.Build
import androidx.annotation.RequiresApi
import com.example.stepcounter3.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.atan2
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
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }
}
// This must run on a background thread!
suspend fun fetchRoadGraph(centerLat: Double, centerLon: Double, radiusMeters: Int = 1000): RoadGraph {
    return withContext(Dispatchers.IO) {
        val graph = RoadGraph()
        try {
            // 1. Build the specific Overpass Query (Ask for roads, not buildings)
            val query = """
                [out:json];
                way["highway"]["area"!~"yes"](around:$radiusMeters,$centerLat,$centerLon);
                (._;>;);
                out;
            """.trimIndent()

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

            // 2. Fetch the map!
            val response = URL(urlString).readText()
            val jsonObject = JSONObject(response)
            val elements = jsonObject.getJSONArray("elements")

            // 3. First Pass: Save all the raw coordinates (Nodes)
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.getString("type") == "node") {
                    val id = element.getLong("id")
                    val lat = element.getDouble("lat")
                    val lon = element.getDouble("lon")
                    graph.nodes[id] = MapNode(id, lat, lon)
                }
            }

            // 4. Second Pass: Connect the coordinates together (Ways/Edges)
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.getString("type") == "way") {
                    val wayId = element.getLong("id")
                    val nodesArray = element.getJSONArray("nodes")

                    // Link each node to the one directly in front of it
                    for (j in 0 until nodesArray.length() - 1) {
                        val fromId = nodesArray.getLong(j)
                        val toId = nodesArray.getLong(j + 1)
                        // This builds our intersections!
                        graph.addEdge(fromId, toId, wayId)
                    }
                }
            }

            return@withContext graph
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext graph // Returns an empty graph if the internet fails
        }
    }
}
@RequiresApi(Build.VERSION_CODES.O)
suspend fun snapTrailToRoad(rawPoints: List<TrailPoint>): List<TrailPoint> {
    // OSRM requires at least 2 points to figure out which way you are walking!
    if (rawPoints.size < 2) return rawPoints

    return withContext(Dispatchers.IO) {
        try {
            // 1. Format the coordinates for the URL: "lon,lat;lon,lat;lon,lat"
            val coordinateString = rawPoints.joinToString(";") { "${it.lon},${it.lat}" }

            // 2. Call the OSRM "Match" API for pedestrians (foot)
            val urlString = "http://router.project-osrm.org/match/v1/foot/$coordinateString?geometries=geojson&overview=full"

            // 3. Fetch data
            val response = URL(urlString).readText()
            val jsonObject = JSONObject(response)
            val matchings = jsonObject.getJSONArray("matchings")

            if (matchings.length() == 0) return@withContext rawPoints // Fallback to raw if it fails

            // 4. Extract the perfectly smooth snapped geometry
            val geometry = matchings.getJSONObject(0).getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")

            val snappedTrail = mutableListOf<TrailPoint>()

            // 5. Rebuild the array
            for (i in 0 until coordinates.length()) {
                val point = coordinates.getJSONArray(i)
                val lon = point.getDouble(0)
                val lat = point.getDouble(1)

                // Keep the original timestamp from the raw data so your photo tagger doesn't break!
                val originalTime = rawPoints.getOrNull(i)?.time ?: java.time.LocalDateTime.now()

                snappedTrail.add(TrailPoint(lat, lon, originalTime))
            }

            return@withContext snappedTrail

        } catch (e: Exception) {
            e.printStackTrace()
            // If the user loses internet connection in the woods, just return the raw messy points!
            return@withContext rawPoints
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