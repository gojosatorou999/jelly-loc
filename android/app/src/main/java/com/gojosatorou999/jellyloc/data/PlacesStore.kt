package com.gojosatorou999.jellyloc.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists favorites and recent places across app restarts. */
class PlacesStore(context: Context) {
    private val prefs = context.getSharedPreferences("jelly_loc_places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlaceSuggestion.serializer())

    fun loadFavorites(): List<PlaceSuggestion> = load(KEY_FAVORITES)
    fun loadRecents(): List<PlaceSuggestion> = load(KEY_RECENTS)
    fun loadSelected(): PlaceSuggestion? = load(KEY_SELECTED).firstOrNull()

    fun save(favorites: List<PlaceSuggestion>, recents: List<PlaceSuggestion>, selected: PlaceSuggestion?) {
        prefs.edit()
            .putString(KEY_FAVORITES, json.encodeToString(serializer, favorites))
            .putString(KEY_RECENTS, json.encodeToString(serializer, recents))
            .putString(KEY_SELECTED, json.encodeToString(serializer, listOfNotNull(selected)))
            .apply()
    }

    private fun load(key: String): List<PlaceSuggestion> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val KEY_SELECTED = "selected"
    }
}
