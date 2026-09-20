package com.gojosatorou999.jellyloc.viewmodel

import com.gojosatorou999.jellyloc.data.PlaceSuggestion

data class MainUiState(
    val query: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val selectedPlace: PlaceSuggestion? = null,
    val isMocking: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null,
)
