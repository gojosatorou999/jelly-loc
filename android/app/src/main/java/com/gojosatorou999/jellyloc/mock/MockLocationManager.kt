package com.gojosatorou999.jellyloc.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.tasks.await

/**
 * Feeds a fake location into Android's GPS + network test providers and the Google fused provider
 * (which is what WhatsApp, Google Maps etc. read). Requires Jelly Loc to be selected under
 * Developer options → "Select mock location app".
 */
class MockLocationManager(context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    /** @throws SecurityException if Jelly Loc is not the selected mock location app. */
    @SuppressLint("MissingPermission")
    suspend fun start() {
        providers.forEach { addOrEnableProvider(it) }
        runCatching { fusedClient.setMockMode(true).await() }
    }

    @SuppressLint("MissingPermission")
    suspend fun push(latitude: Double, longitude: Double) {
        // A few metres of natural drift so the location doesn't look frozen
        val (lat, lng) = jitter(latitude, longitude, meters = 3.0)
        val accuracy = 4f + Random.nextFloat() * 4f
        providers.forEach { provider ->
            runCatching { locationManager.setTestProviderLocation(provider, buildLocation(provider, lat, lng, accuracy)) }
        }
        runCatching { fusedClient.setMockLocation(buildLocation("fused", lat, lng, accuracy)).await() }
    }

    fun stop() {
        runCatching { fusedClient.setMockMode(false) }
        providers.forEach { provider -> runCatching { locationManager.removeTestProvider(provider) } }
    }

    private fun addOrEnableProvider(provider: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    provider,
                    ProviderProperties.Builder()
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider, false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE,
                )
            }
        } catch (e: IllegalArgumentException) {
            // Provider already exists as a test provider — fine
        }
        locationManager.setTestProviderEnabled(provider, true)
    }

    private fun jitter(lat: Double, lng: Double, meters: Double): Pair<Double, Double> {
        val d = Random.nextDouble() * meters
        val a = Random.nextDouble() * 2 * PI
        val dLat = d * cos(a) / 111_320.0
        val dLng = d * sin(a) / (111_320.0 * cos(lat * PI / 180))
        return (lat + dLat) to (lng + dLng)
    }

    private fun buildLocation(provider: String, latitude: Double, longitude: Double, acc: Float): Location {
        return Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = acc
            altitude = 0.0
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            bearingAccuracyDegrees = 1f
            speedAccuracyMetersPerSecond = 0.1f
            verticalAccuracyMeters = 3f
        }
    }
}
