package com.gojosatorou999.jellyloc.data

import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class NominatimPlacesRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun search(query: String): List<PlaceSuggestion> {
        if (query.length < 2) return emptyList()

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&addressdetails=1&limit=6")
            .header("User-Agent", "JellyLoc/1.0 (https://github.com/gojosatorou999/jelly-loc)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val body = response.body.string()
        response.close()
        val parsed = json.decodeFromString<List<NominatimResult>>(body)
        return parsed.map {
            val parts = it.displayName.split(',').map(String::trim)
            PlaceSuggestion(
                name = parts.firstOrNull().orEmpty(),
                address = parts.drop(1).joinToString(", "),
                latitude = it.lat.toDoubleOrNull() ?: 0.0,
                longitude = it.lon.toDoubleOrNull() ?: 0.0,
            )
        }
    }
}

@Serializable
private data class NominatimResult(
    @SerialName("display_name")
    val displayName: String,
    val lat: String,
    val lon: String,
)
