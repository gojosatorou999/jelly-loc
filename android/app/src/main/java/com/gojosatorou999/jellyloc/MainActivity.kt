package com.gojosatorou999.jellyloc

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gojosatorou999.jellyloc.data.PlaceSuggestion
import com.gojosatorou999.jellyloc.mock.MockLocationService
import com.gojosatorou999.jellyloc.ui.MainScreen
import com.gojosatorou999.jellyloc.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val running by MockLocationService.running.collectAsStateWithLifecycle()
                val serviceError by MockLocationService.lastError.collectAsStateWithLifecycle()
                var setupReady by remember { mutableStateOf(isSelectedMockLocationApp()) }
                var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

                // Re-check every time the user comes back (e.g. from Developer options / battery settings)
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    setupReady = isSelectedMockLocationApp()
                    batteryUnrestricted = isIgnoringBatteryOptimizations()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true &&
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] != true
                    ) {
                        Toast.makeText(this, "Location permission is required to mock location", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasLocationPermission()) permissionLauncher.launch(requiredPermissions())
                }

                // Activity recreated while the service kept running: show what's being mocked
                LaunchedEffect(running) {
                    val r = running
                    if (r != null && state.selectedPlace == null) {
                        viewModel.onSuggestionSelected(PlaceSuggestion(r.name, "${r.latitude}, ${r.longitude}", r.latitude, r.longitude))
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!setupReady) {
                        SetupNeededScreen(
                            onOpenDeveloperOptions = ::openDeveloperOptions,
                            onOpenAboutPhone = { safeStart(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
                        )
                    } else {
                        MainScreen(
                            uiState = state.copy(
                                isMocking = running != null,
                                error = serviceError ?: state.error,
                            ),
                            batteryUnrestricted = batteryUnrestricted,
                            onFixBattery = ::requestIgnoreBatteryOptimizations,
                            onQueryChanged = viewModel::onQueryChanged,
                            onSuggestionSelected = { place ->
                                viewModel.onSuggestionSelected(place)
                                // Already mocking? Move the fake location right away.
                                if (running != null) {
                                    MockLocationService.start(this, place.latitude, place.longitude, place.name)
                                }
                            },
                            onMapPicked = { lat, lng ->
                                viewModel.onMapPicked(lat, lng)
                                if (running != null) MockLocationService.start(this, lat, lng, "Dropped pin")
                            },
                            onToggleFavorite = viewModel::toggleFavoriteSelected,
                            onToggleMock = {
                                if (running != null) {
                                    MockLocationService.stop(this)
                                } else {
                                    val selected = state.selectedPlace
                                    when {
                                        selected == null -> Unit
                                        !hasLocationPermission() -> permissionLauncher.launch(requiredPermissions())
                                        else -> MockLocationService.start(this, selected.latitude, selected.longitude, selected.name)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openDeveloperOptions() {
        val enabled = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        if (enabled) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } else {
            Toast.makeText(this, "Developer options are off — tap Build number 7 times first", Toast.LENGTH_LONG).show()
            safeStart(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isSelectedMockLocationApp(): Boolean {
        return runCatching {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

@Composable
private fun SetupNeededScreen(onOpenDeveloperOptions: () -> Unit, onOpenAboutPhone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("One-time setup", style = MaterialTheme.typography.headlineSmall)
        Text("1. Settings → About phone → tap \"Build number\" 7 times to unlock Developer options.")
        Text("2. Developer options → \"Select mock location app\" → choose Jelly Loc.")
        Text("3. Come back here — the app opens automatically once it's selected.")
        OutlinedButton(onClick = onOpenAboutPhone) { Text("Open About phone") }
        Button(onClick = onOpenDeveloperOptions) { Text("Open Developer options") }
    }
}
