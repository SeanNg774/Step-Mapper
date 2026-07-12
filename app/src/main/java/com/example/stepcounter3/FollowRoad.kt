import android.os.Build
import androidx.annotation.RequiresApi
import com.example.stepcounter3.TrailPoint
import com.example.stepcounter3.haversineMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin

// Represents a single coordinate on the road (intersections or curves)
data class MapNode(val id: Long, val lat: Double, val lon: Double)
// Represents a road connecting two MapNodes
data class MapEdge(val targetNodeId: Long, val wayId: Long)
class RoadGraph {
    val nodes = mutableMapOf<Long, MapNode>()
    val adjacencyList = mutableMapOf<Long, MutableList<MapEdge>>()

    fun addEdge(fromId: Long, toId: Long, wayId: Long) {
        adjacencyList.getOrPut(fromId) { mutableListOf() }.add(MapEdge(toId, wayId))
        adjacencyList.getOrPut(toId) { mutableListOf() }.add(MapEdge(fromId, wayId))
    }

    // Snaps the user to the closest valid road node
    fun getClosestNode(targetLat: Double, targetLon: Double): MapNode? {
        if (nodes.isEmpty()) return null

        var closestNode: MapNode? = null
        var shortestDistance = Double.MAX_VALUE

        for ((_, node) in nodes) {
            val distance = haversineMeters(targetLat, targetLon, node.lat, node.lon)
            if (distance < shortestDistance) {
                shortestDistance = distance
                closestNode = node
            }
        }
        return closestNode
    }



}

suspend fun fetchRoadGraph(centerLat: Double, centerLon: Double, radiusMeters: Int): RoadGraph {
    return withContext(Dispatchers.IO) {
        val graph = RoadGraph()
        try {
            val query = """
                [out:json][timeout:45];
(
  way["highway"~"residential|path|footway|living_street|track|tertiary|unclassified|secondary|service|primary|trunk|motorway_link"](around:$radiusMeters,$centerLat,$centerLon);
);
(._;>;);
out skel qt ; 
            """.trimIndent()


            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://overpass-api.de/api/interpreter?data=$encodedQuery"

            // Network Connection
            val url = URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "StepCounterFYP/1.0 (Android)")
            connection.connectTimeout = 15000
            connection.readTimeout = 35000

            if (connection.responseCode != 200) {
                android.util.Log.e("OverpassError", "Blocked: ${connection.responseCode}")
                return@withContext graph
            }
            // Read and Parse JSON
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(response)
            val elements = jsonObject.getJSONArray("elements")
            android.util.Log.d("MapDebug", "Downloaded ${elements.length()} elements at $centerLat, $centerLon")

            if (elements.length() == 0) {
                android.util.Log.e("MapDebug", "Error: No map data returned!")
            } else {
                // Check if we got any ways/roads
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
    val earthRadiusMeters = 6371000.0

    val perpendicularBearing = Math.toRadians(roadBearing + 90.0)
    val angularDistance = offsetMeters / earthRadiusMeters

    val latRad = Math.toRadians(baseLat)
    val lonRad = Math.toRadians(baseLon)

    val newLatRad = kotlin.math.asin(sin(latRad) * cos(angularDistance) +
            cos(latRad) * sin(angularDistance) *cos(perpendicularBearing))

    val newLonRad = lonRad + kotlin.math.atan2(sin(perpendicularBearing) * sin(angularDistance) * cos(latRad),
        cos(angularDistance) - sin(latRad) * sin(newLatRad))

    return TrailPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad), java.time.LocalDateTime.now())
}