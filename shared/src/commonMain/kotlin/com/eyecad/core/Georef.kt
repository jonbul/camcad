package com.eyecad.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Places the local scan on the globe.
 *
 * The plan origin (the AR world origin) sits at [latitude]/[longitude]. [rotationDeg] is the true
 * bearing of plan +y: a plan vector with plan bearing b points to true bearing b + rotationDeg.
 */
data class Georef(
    val latitude: Double,
    val longitude: Double,
    val rotationDeg: Double,
    val altitude: Double? = null,
) {
    /** East/North offset in meters of a plan point from the origin. */
    fun toEastNorth(p: Vec2): Vec2 {
        val t = rotationDeg * PI / 180
        return Vec2(p.x * cos(t) + p.y * sin(t), -p.x * sin(t) + p.y * cos(t))
    }

    /** Longitude and latitude of a plan point (equirectangular approximation, fine within a building). */
    fun toLonLat(p: Vec2): Vec2 {
        val en = toEastNorth(p)
        val lat = latitude + en.y / METERS_PER_DEGREE
        val lon = longitude + en.x / (METERS_PER_DEGREE * cos(latitude * PI / 180))
        return Vec2(lon, lat)
    }

    /** Inverse of [toLonLat]: the plan point at a longitude/latitude. */
    fun fromLonLat(lon: Double, lat: Double): Vec2 {
        val e = (lon - longitude) * METERS_PER_DEGREE * cos(latitude * PI / 180)
        val n = (lat - latitude) * METERS_PER_DEGREE
        val t = rotationDeg * PI / 180
        return Vec2(e * cos(t) - n * sin(t), e * sin(t) + n * cos(t))
    }

    companion object {
        const val METERS_PER_DEGREE = 111_320.0

        /** Plan bearing of a plan vector, in degrees clockwise from plan +y. */
        fun planBearingDeg(v: Vec2) = atan2(v.x, v.y) * 180 / PI

        /**
         * From one simultaneous reading of the camera's world-space forward vector and the compass's
         * true bearing for that same direction.
         */
        fun fromCameraHeading(latitude: Double, longitude: Double, cameraForward: Vec3, cameraTrueBearingDeg: Double): Georef =
            Georef(latitude, longitude, cameraTrueBearingDeg - planBearingDeg(cameraForward.toPlan()))
    }
}

/**
 * Averages many (camera forward, compass bearing) pairs into one plan-to-north rotation, because a
 * single compass reading indoors can be off by tens of degrees.
 */
class HeadingCalibrator {
    private var sumSin = 0.0
    private var sumCos = 0.0
    var samples = 0
        private set

    /** Skips samples where the camera points mostly up or down, where the bearing is meaningless. */
    fun addSample(cameraForward: Vec3, trueBearingDeg: Double) {
        val f = cameraForward.normalized()
        if (f.y > 0.8 || f.y < -0.8) return
        val offset = (trueBearingDeg - Georef.planBearingDeg(f.toPlan())) * PI / 180
        sumSin += sin(offset)
        sumCos += cos(offset)
        samples++
    }

    /** Circular mean of the offsets in degrees, or null without samples. */
    fun rotationDeg(): Double? {
        if (samples == 0) return null
        return atan2(sumSin, sumCos) * 180 / PI
    }

    /** 0 (scattered readings) to 1 (all readings agree). */
    fun consistency(): Double = if (samples == 0) 0.0 else sqrt(sumSin * sumSin + sumCos * sumCos) / samples

    fun georef(latitude: Double, longitude: Double, altitude: Double?): Georef? =
        rotationDeg()?.let { Georef(latitude, longitude, it, altitude) }
}
