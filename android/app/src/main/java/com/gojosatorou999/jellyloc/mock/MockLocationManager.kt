package com.gojosatorou999.jellyloc.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

class MockLocationManager(context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun start() {
        addOrEnableProvider(LocationManager.GPS_PROVIDER)
        addOrEnableProvider(LocationManager.NETWORK_PROVIDER)
        fusedClient.setMockMode(true).await()
    }

    @SuppressLint("MissingPermission")
    suspend fun push(latitude: Double, longitude: Double) {
        val location = buildLocation(LocationManager.GPS_PROVIDER, latitude, longitude)
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)

        val networkLocation = buildLocation(LocationManager.NETWORK_PROVIDER, latitude, longitude)
        locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, networkLocation)

        fusedClient.setMockLocation(location).await()
    }

    suspend fun stop() {
        runCatching { fusedClient.setMockMode(false).await() }
        removeProvider(LocationManager.GPS_PROVIDER)
        removeProvider(LocationManager.NETWORK_PROVIDER)
    }

    private fun addOrEnableProvider(provider: String) {
        runCatching {
            locationManager.addTestProvider(
                provider,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }
        runCatching { locationManager.setTestProviderEnabled(provider, true) }
    }

    private fun removeProvider(provider: String) {
        runCatching { locationManager.removeTestProvider(provider) }
    }

    private fun buildLocation(provider: String, latitude: Double, longitude: Double): Location {
        return Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 7f
            altitude = 0.0
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 1f
                speedAccuracyMetersPerSecond = 0.1f
                verticalAccuracyMeters = 1f
            }
        }
    }
}
