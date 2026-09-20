package com.gojosatorou999.jellyloc

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gojosatorou999.jellyloc.mock.MockLocationService
import com.gojosatorou999.jellyloc.ui.MainScreen
import com.gojosatorou999.jellyloc.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsState()
            val setupReady = isSelectedMockLocationApp()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }

            LaunchedEffect(Unit) {
                val permissions = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (!setupReady) {
                    SetupNeededScreen(onOpenDeveloperOptions = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    })
                } else {
                    MainScreen(
                        uiState = state,
                        setupReady = true,
                        onQueryChanged = viewModel::onQueryChanged,
                        onSuggestionSelected = viewModel::onSuggestionSelected,
                        onToggleMock = {
                            val selected = state.selectedPlace ?: return@MainScreen
                            if (state.isMocking) {
                                MockLocationService.stop(this)
                                viewModel.setMocking(false)
                            } else {
                                MockLocationService.start(this, selected.latitude, selected.longitude, selected.name)
                                viewModel.setMocking(true)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun isSelectedMockLocationApp(): Boolean {
        return runCatching {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),
                packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

@androidx.compose.runtime.Composable
private fun SetupNeededScreen(onOpenDeveloperOptions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Setup needed", style = MaterialTheme.typography.headlineSmall)
        Text("1) Enable Developer options (tap Build number 7x).")
        Text("2) Open Select mock location app and choose Jelly Loc.")
        Button(onClick = onOpenDeveloperOptions) {
            Text("Open Developer options")
        }
    }
}
