package com.gojosatorou999.jellyloc.data

import kotlinx.serialization.Serializable

@Serializable
data class PlaceSuggestion(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)
