package salah.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale

sealed class LocationSearchException(message: String) : Exception(message) {
    class NoResults(query: String) : LocationSearchException("No places found for “$query”.")
    class Failed(reason: String) : LocationSearchException("Location search failed: $reason")
}

/**
 * City search and coordinate lookups. Windows has no system geocoder like Apple's CLGeocoder, so
 * Salah uses Open-Meteo's free geocoding API (no key, no account) for city search and time zones,
 * and BigDataCloud's free client API to name a coordinate. Only the query or the coordinate is sent.
 */
object LocationSearch {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    fun search(query: String): List<SavedLocation> {
        val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json")
        val results = (json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray
        val out = results.orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val lat = o.num("latitude") ?: return@mapNotNull null
            val lon = o.num("longitude") ?: return@mapNotNull null
            val tz = o.str("timezone")?.takeIf { SavedLocation.zoneOrNull(it) != null } ?: return@mapNotNull null
            SavedLocation(
                name = o.str("name") ?: "Custom location", latitude = lat, longitude = lon, timeZone = tz,
                countryCode = o.str("country_code"), source = SavedLocation.Source.MANUAL,
            ) to listOfNotNull(o.str("admin1"), o.str("country")).joinToString(", ")
        }
        if (out.isEmpty()) throw LocationSearchException.NoResults(query)
        out.forEach { (loc, region) -> regions[loc] = region }
        return out.map { it.first }
    }

    /** Names a coordinate and finds its time zone. Returns null when offline or nothing matches. */
    fun reverse(latitude: Double, longitude: Double, source: SavedLocation.Source): SavedLocation? {
        val lat = String.format(Locale.ROOT, "%.4f", latitude)
        val lon = String.format(Locale.ROOT, "%.4f", longitude)
        val tz = runCatching {
            val b = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=1")
            (json.parseToJsonElement(b) as JsonObject).str("timezone")
        }.getOrNull()?.takeIf { SavedLocation.zoneOrNull(it) != null && it != "GMT" } ?: return null
        var name: String? = null
        var country: String? = null
        runCatching {
            val b = get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en")
            val o = json.parseToJsonElement(b) as JsonObject
            name = listOf("city", "locality", "principalSubdivision", "countryName").firstNotNullOfOrNull { o.str(it)?.takeIf(String::isNotBlank) }
            country = o.str("countryCode")?.takeIf(String::isNotBlank)
        }
        return SavedLocation(name ?: "Custom location", latitude, longitude, tz, country, source)
    }

    /** "Kuala Lumpur, Malaysia" region text for a search result, when known. */
    fun region(location: SavedLocation): String? = regions[location]?.takeIf { it.isNotBlank() }

    /** A readable disambiguation line for search results. */
    fun detail(location: SavedLocation): String =
        listOfNotNull(location.name, region(location), location.timeZone, location.coordinateDescription).joinToString(" · ")

    private val regions = java.util.concurrent.ConcurrentHashMap<SavedLocation, String>()

    private fun get(url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Salah/${SalahInfo.VERSION} (Windows prayer times)")
            .GET().build()
        val res = try {
            client.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LocationSearchException.Failed(e.message ?: "network unavailable")
        }
        if (res.statusCode() !in 200..299) throw LocationSearchException.Failed("HTTP ${res.statusCode()}")
        return res.body()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}
