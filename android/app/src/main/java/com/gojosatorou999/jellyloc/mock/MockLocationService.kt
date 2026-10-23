package com.gojosatorou999.jellyloc.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gojosatorou999.jellyloc.MainActivity
import com.gojosatorou999.jellyloc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MockLocationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mockLocationManager: MockLocationManager
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        mockLocationManager = MockLocationManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMocking()
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification(name))
                startMocking(lat, lng)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopMocking()
        super.onTaskRemoved(rootIntent)
    }

    private fun startMocking(lat: Double, lng: Double) {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            runCatching { mockLocationManager.start() }
            while (isActive) {
                runCatching { mockLocationManager.push(lat, lng) }
                delay(1_000)
            }
        }
    }

    private fun stopMocking() {
        loopJob?.cancel()
        serviceScope.launch {
            runCatching { mockLocationManager.stop() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopMocking()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(placeName: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mock location",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LNG = "extra_lng"
        private const val EXTRA_NAME = "extra_name"

        const val ACTION_START = "com.gojosatorou999.jellyloc.action.START"
        const val ACTION_STOP = "com.gojosatorou999.jellyloc.action.STOP"

        fun start(context: Context, lat: Double, lng: Double, name: String) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
