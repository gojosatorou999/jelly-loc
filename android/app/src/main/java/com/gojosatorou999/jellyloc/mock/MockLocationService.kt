package com.gojosatorou999.jellyloc.mock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gojosatorou999.jellyloc.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A place currently being reported as the device location. */
data class MockTarget(val latitude: Double, val longitude: Double, val name: String)

/**
 * Foreground service that keeps feeding the mock location to Android every second,
 * so apps like WhatsApp (live location) keep receiving it while Jelly Loc is in the background.
 */
class MockLocationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var mockLocationManager: MockLocationManager
    private var loopJob: Job? = null
    @Volatile private var target: MockTarget? = null

    override fun onCreate() {
        super.onCreate()
        mockLocationManager = MockLocationManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val newTarget = MockTarget(
                    latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0),
                    longitude = intent.getDoubleExtra(EXTRA_LNG, 0.0),
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                )
                if (!hasLocationPermission() || !goForeground(newTarget.name)) {
                    stopMocking()
                    return START_NOT_STICKY
                }
                target = newTarget
                _running.value = newTarget
                if (loopJob?.isActive != true) startLoop()
            }
            else -> stopSelf()
        }
        // If Android kills the service, restart it with the last start intent (same place).
        return START_REDELIVER_INTENT
    }

    private fun goForeground(placeName: String): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(placeName), type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not start foreground service", e)
        false
    }

    private fun startLoop() {
        loopJob = serviceScope.launch {
            try {
                mockLocationManager.start()
            } catch (e: SecurityException) {
                Log.e(TAG, "Not selected as mock location app", e)
                _lastError.value = "Select Jelly Loc as the mock location app in Developer options."
                stopMocking()
                return@launch
            }
            while (isActive) {
                target?.let { t ->
                    runCatching { mockLocationManager.push(t.latitude, t.longitude) }
                        .onFailure { Log.w(TAG, "push failed", it) }
                }
                delay(1_000)
            }
        }
    }

    private fun stopMocking() {
        loopJob?.cancel()
        loopJob = null
        target = null
        _running.value = null
        mockLocationManager.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        if (target != null) mockLocationManager.stop()
        _running.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(placeName: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mock location active")
            .setContentText(placeName.ifBlank { "Jelly Loc" })
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "JellyLoc"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LNG = "extra_lng"
        private const val EXTRA_NAME = "extra_name"

        const val ACTION_START = "com.gojosatorou999.jellyloc.action.START"
        const val ACTION_STOP = "com.gojosatorou999.jellyloc.action.STOP"

        private val _running = MutableStateFlow<MockTarget?>(null)
        /** The place currently being mocked, or null when idle. */
        val running: StateFlow<MockTarget?> = _running.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun clearError() { _lastError.value = null }

        /** Starts mocking, or moves the mocked location if already running. */
        fun start(context: Context, lat: Double, lng: Double, name: String) {
            _lastError.value = null
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
