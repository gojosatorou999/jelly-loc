package com.gojosatorou999.jellyloc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gojosatorou999.jellyloc.data.PlaceSuggestion
import com.gojosatorou999.jellyloc.viewmodel.MainUiState
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MainScreen(
    uiState: MainUiState,
    setupReady: Boolean,
    onQueryChanged: (String) -> Unit,
    onSuggestionSelected: (PlaceSuggestion) -> Unit,
    onToggleMock: () -> Unit,
) {
    val context = LocalContext.current
    val selected = uiState.selectedPlace

    DisposableEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
        onDispose { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            placeholder = { Text("Search place or coordinates") },
            singleLine = true,
            trailingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            },
        )

        if (uiState.suggestions.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
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
            val mapView = remember {
                MapView(context).apply {
                    setMultiTouchControls(true)
                    controller.setZoom(14.0)
                    controller.setCenter(GeoPoint(17.385f.toDouble(), 78.4867))
                }
            }

            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { view ->
                    if (selected != null) {
                        val point = GeoPoint(selected.latitude, selected.longitude)
                        view.controller.animateTo(point)
                        view.overlays.removeAll { it is Marker }
                        val marker = Marker(view).apply {
                            position = point
                            title = selected.name
                            icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                        }
                        view.overlays.add(marker)
                    }
                    view.invalidate()
                },
            )
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = when {
                    !setupReady -> "Setup needed"
                    uiState.isMocking -> "Mocking"
                    else -> "Idle"
                }
                AssistChip(
                    onClick = {},
                    label = { Text(status) },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Default.LocationOn, contentDescription = null) },
                )

                if (selected != null) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text("${selected.latitude}, ${selected.longitude}")
                } else {
                    Text("Pick a place to continue")
                }

                uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleMock,
                        enabled = setupReady && selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (uiState.isMocking) "Stop" else "Set Location")
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                if (!setupReady) {
                    Text(
                        "Enable Developer options and select Jelly Loc as mock app.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}
