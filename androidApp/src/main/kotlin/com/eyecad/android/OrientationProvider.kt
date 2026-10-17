package com.eyecad.android

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlin.math.atan2

/** Latest GPS fix and the true-north bearing of the back camera. */
class OrientationProvider(context: Context) : SensorEventListener, LocationListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val rotation = FloatArray(9)
    @Volatile private var hasRotation = false
    @Volatile var location: Location? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(locationAllowed: Boolean) {
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (!locationAllowed) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!locations.isProviderEnabled(provider)) continue
            locations.getLastKnownLocation(provider)?.let(::onLocationChanged)
            locations.requestLocationUpdates(provider, 2000L, 0f, this, Looper.getMainLooper())
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        locations.removeUpdates(this)
    }

    /** True bearing in degrees the back camera points to, or null without a rotation sensor. */
    fun cameraTrueBearing(): Double? {
        if (!hasRotation) return null
        // Rotation maps device to world (x east, y north, z up); the back camera looks along device -Z.
        val r = synchronized(rotation) { rotation.copyOf() }
        val east = -r[2]
        val north = -r[5]
        val magnetic = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        val declination = location?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
        } ?: 0f
        return magnetic + declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(rotation) { SensorManager.getRotationMatrixFromVector(rotation, event.values) }
        hasRotation = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(l: Location) {
        val current = location
        if (current == null || l.accuracy <= current.accuracy || l.time - current.time > 30_000) location = l
    }

    @Deprecated("Required on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
