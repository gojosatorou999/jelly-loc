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
            )
        }
    }

    fun setMocking(active: Boolean) {
        _uiState.update { it.copy(isMocking = active) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
