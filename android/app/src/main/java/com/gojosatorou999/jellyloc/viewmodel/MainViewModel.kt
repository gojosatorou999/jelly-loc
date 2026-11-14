package com.gojosatorou999.jellyloc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gojosatorou999.jellyloc.data.NominatimPlacesRepository
import com.gojosatorou999.jellyloc.data.PlaceSuggestion
import com.gojosatorou999.jellyloc.data.PlacesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NominatimPlacesRepository()
    private val store = PlacesStore(application)

    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedPlace = store.loadSelected(),
            favoritePlaces = store.loadFavorites(),
            recentPlaces = store.loadRecents(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
        searchJob?.cancel()

        parseCoordinates(query)?.let { (lat, lng) ->
            _uiState.update {
                it.copy(
                    isSearching = false,
                    suggestions = listOf(PlaceSuggestion("Pinned coordinates", "$lat, $lng", lat, lng)),
                )
            }
            return
        }

        if (query.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            delay(400) // debounce typing; also keeps us within Nominatim's usage policy
            runCatching { repository.search(query) }
                .onSuccess { places ->
                    _uiState.update {
                        it.copy(
                            suggestions = places,
                            isSearching = false,
                            error = if (places.isEmpty()) "No results for \"$query\"" else null,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(suggestions = emptyList(), isSearching = false, error = "Search failed. Check internet and try again.")
                    }
                }
        }
    }

    fun onSuggestionSelected(place: PlaceSuggestion) {
        _uiState.update {
            it.copy(
                selectedPlace = place,
                query = "",
                suggestions = emptyList(),
                error = null,
                recentPlaces = (listOf(place) + it.recentPlaces.filterNot { recent -> isSamePlace(recent, place) }).take(8),
            )
        }
        persist()
    }

    fun onMapPicked(latitude: Double, longitude: Double) {
        onSuggestionSelected(
            PlaceSuggestion("Dropped pin", "%.6f, %.6f".format(latitude, longitude), latitude, longitude),
        )
    }

    fun toggleFavoriteSelected() {
        _uiState.update { state ->
            val selected = state.selectedPlace ?: return@update state
            val exists = state.favoritePlaces.any { isSamePlace(it, selected) }
            val updated = if (exists) {
                state.favoritePlaces.filterNot { isSamePlace(it, selected) }
            } else {
                (listOf(selected) + state.favoritePlaces).take(20)
            }
            state.copy(favoritePlaces = updated)
        }
        persist()
    }

    private fun persist() {
        val s = _uiState.value
        store.save(s.favoritePlaces, s.recentPlaces, s.selectedPlace)
    }

    private fun parseCoordinates(input: String): Pair<Double, Double>? {
        val match = COORD_REGEX.matchEntire(input) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private companion object {
        val COORD_REGEX = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

        fun isSamePlace(a: PlaceSuggestion, b: PlaceSuggestion): Boolean =
            kotlin.math.abs(a.latitude - b.latitude) < 1e-5 && kotlin.math.abs(a.longitude - b.longitude) < 1e-5
    }
}
