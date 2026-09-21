package com.gojosatorou999.jellyloc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gojosatorou999.jellyloc.data.NominatimPlacesRepository
import com.gojosatorou999.jellyloc.data.PlaceSuggestion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: NominatimPlacesRepository = NominatimPlacesRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
        searchJob?.cancel()

        parseCoordinates(query)?.let { (lat, lng) ->
            _uiState.update {
                it.copy(
                    isSearching = false,
                    suggestions = listOf(
                        PlaceSuggestion(
                            name = "Pinned coordinates",
                            address = "$lat, $lng",
                            latitude = lat,
                            longitude = lng,
                        ),
                    ),
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
            delay(300)
            runCatching { repository.search(query) }
                .onSuccess { places ->
                    _uiState.update { it.copy(suggestions = places, isSearching = false) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            suggestions = emptyList(),
                            isSearching = false,
                            error = "Search failed. Check internet and try again.",
                        )
                    }
                }
        }
    }

    fun onSuggestionSelected(place: PlaceSuggestion) {
        _uiState.update {
            it.copy(
                selectedPlace = place,
                query = place.name,
                suggestions = emptyList(),
                recentPlaces = (listOf(place) + it.recentPlaces.filterNot { recent -> isSamePlace(recent, place) }).take(6),
            )
        }
    }

    fun onMapLongPressed(latitude: Double, longitude: Double) {
        onSuggestionSelected(
            PlaceSuggestion(
                name = "Dropped pin",
                address = "$latitude, $longitude",
                latitude = latitude,
                longitude = longitude,
            ),
        )
    }

    fun setMocking(active: Boolean) {
        _uiState.update { it.copy(isMocking = active) }
    }

    fun toggleFavoriteSelected() {
        _uiState.update { state ->
            val selected = state.selectedPlace ?: return@update state
            val exists = state.favoritePlaces.any { isSamePlace(it, selected) }
            val updated = if (exists) {
                state.favoritePlaces.filterNot { isSamePlace(it, selected) }
            } else {
                (listOf(selected) + state.favoritePlaces.filterNot { isSamePlace(it, selected) }).take(10)
            }
            state.copy(favoritePlaces = updated)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun parseCoordinates(input: String): Pair<Double, Double>? {
        val pattern = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
        val match = pattern.matchEntire(input) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun isSamePlace(a: PlaceSuggestion, b: PlaceSuggestion): Boolean {
        return a.latitude == b.latitude && a.longitude == b.longitude
    }
}
