package com.gojosatorou999.jellyloc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gojosatorou999.jellyloc.data.PlaceSuggestion
import com.gojosatorou999.jellyloc.viewmodel.MainUiState
import java.io.File
import kotlin.math.abs
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@Composable
fun MainScreen(
    uiState: MainUiState,
    batteryUnrestricted: Boolean,
    onFixBattery: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSuggestionSelected: (PlaceSuggestion) -> Unit,
    onMapPicked: (Double, Double) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleMock: () -> Unit,
) {
    val selected = uiState.selectedPlace
    val isFavorite = selected != null && uiState.favoritePlaces.any {
        abs(it.latitude - selected.latitude) < 1e-5 && abs(it.longitude - selected.longitude) < 1e-5
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search a place or paste lat, lng") },
            singleLine = true,
            trailingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            },
        )

        if (uiState.suggestions.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(horizontal = 12.dp),
            ) {
                LazyColumn {
                    items(uiState.suggestions) { place ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(place) }
                                .padding(12.dp),
                        ) {
                            Text(place.name, style = MaterialTheme.typography.bodyLarge)
                            Text(place.address, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            OsmMap(selected = selected, onMapPicked = onMapPicked)
        }

        if (!batteryUnrestricted) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(
                        "Battery saver may stop mocking in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp),
                    )
                    TextButton(onClick = onFixBattery) { Text("Fix") }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (uiState.isMocking) "Mocking active" else "Idle") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                )

                if (selected != null) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text("%.6f, %.6f".format(selected.latitude, selected.longitude), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Search, or tap the map to pick a place")
                }

                uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (uiState.favoritePlaces.isNotEmpty()) {
                    QuickPlacesRow(title = "Favorites", places = uiState.favoritePlaces, onPick = onSuggestionSelected)
                }
                if (uiState.recentPlaces.isNotEmpty()) {
                    QuickPlacesRow(title = "Recent", places = uiState.recentPlaces, onPick = onSuggestionSelected)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleMock,
                        enabled = selected != null,
                        modifier = Modifier.weight(1f),
                        colors = if (uiState.isMocking) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(if (uiState.isMocking) "Stop mocking" else "Start mocking")
                    }
                    OutlinedButton(onClick = onToggleFavorite, enabled = selected != null) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                        )
                    }
                }

                if (uiState.isMocking) {
                    Text(
                        "You can leave the app now — mocking keeps running until you tap Stop here or in the notification.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun OsmMap(selected: PlaceSuggestion?, onMapPicked: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onPick = rememberUpdatedState(onMapPicked)

    val mapView = remember {
        // osmdroid needs a user agent and a writable cache dir (app cache — no storage permission needed)
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setMultiTouchControls(true)
            controller.setZoom(if (selected != null) 16.0 else 3.0)
            controller.setCenter(GeoPoint(selected?.latitude ?: 20.0, selected?.longitude ?: 78.0))
            overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            p ?: return false
                            onPick.value(p.latitude, p.longitude)
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = singleTapConfirmedHelper(p)
                    },
                ),
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            view.overlays.removeAll { it is Marker }
            if (selected != null) {
                val point = GeoPoint(selected.latitude, selected.longitude)
                if (view.zoomLevelDouble < 12.0) view.controller.setZoom(16.0)
                view.controller.animateTo(point)
                view.overlays.add(
                    Marker(view).apply {
                        position = point
                        title = selected.name
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    },
                )
            }
            view.invalidate()
        },
    )
}

@Composable
private fun QuickPlacesRow(title: String, places: List<PlaceSuggestion>, onPick: (PlaceSuggestion) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEach { place ->
                AssistChip(
                    onClick = { onPick(place) },
                    label = { Text(place.name.ifBlank { "%.4f, %.4f".format(place.latitude, place.longitude) }) },
                )
            }
        }
    }
}
