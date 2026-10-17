package com.eyecad.core

import kotlin.math.abs

/** Camera intrinsics and pose for the exact image a detector ran on. Pixels are in sensor (unrotated) image space. */
data class CameraFrame(
    val pose: Pose,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val imageWidth: Double,
    val imageHeight: Double,
) {
    /** World-space ray direction through a sensor pixel (u right, v down). */
    fun ray(u: Double, v: Double): Vec3 =
        pose.rotate(Vec3((u - cx) / fx, -(v - cy) / fy, -1.0)).normalized()
}

/**
 * One 2D detection, reduced to the four sensor-image pixels the projector needs.
 * Build it with [fromUprightBox] from what a detector returns.
 */
data class Detection2D(
    val label: String,
    val confidence: Double,
    val bottomCenter: Vec2,
    val topCenter: Vec2,
    val bottomLeft: Vec2,
    val bottomRight: Vec2,
    /** True when the box touches the image's lower edge, so the object's base isn't visible. */
    val cutOffAtBottom: Boolean,
) {
    companion object {
        /**
         * From a box in the upright image the detector saw (normalized 0..1, origin top-left), where the
         * upright image is the sensor image rotated clockwise by [rotationDeg] (0, 90, 180 or 270).
         */
        fun fromUprightBox(
            label: String,
            confidence: Double,
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
            rotationDeg: Int,
            sensorWidth: Double,
            sensorHeight: Double,
        ): Detection2D {
            fun toSensor(x: Double, y: Double) = uprightToSensor(x, y, rotationDeg, sensorWidth, sensorHeight)
            val cx = (left + right) / 2
            return Detection2D(
                label = label,
                confidence = confidence,
                bottomCenter = toSensor(cx, bottom),
                topCenter = toSensor(cx, top),
                bottomLeft = toSensor(left, bottom),
                bottomRight = toSensor(right, bottom),
                cutOffAtBottom = bottom > 0.98,
            )
        }

        /** Maps a normalized upright-image point back to sensor pixels. */
        fun uprightToSensor(x: Double, y: Double, rotationDeg: Int, w: Double, h: Double): Vec2 =
            when (((rotationDeg % 360) + 360) % 360) {
                90 -> Vec2(y * w, (1 - x) * h)
                180 -> Vec2((1 - x) * w, (1 - y) * h)
                270 -> Vec2((1 - y) * w, x * h)
                else -> Vec2(x * w, y * h)
            }
    }
}

/** A detection placed in the room, before it's merged with other sightings of the same object. */
data class Detection3D(
    val category: String,
    val center: Vec2,
    val width: Double,
    val depth: Double,
    val height: Double,
    val yaw: Double,
    val baseHeight: Double,
    val confidence: Double,
)

/** Casts a world-space ray against the AR session's detected surfaces; returns the hit point or null. */
typealias HitTester = (origin: Vec3, direction: Vec3) -> Vec3?

/**
 * Turns a 2D box into a 3D box using the floor (or the surface the object rests on) for distance,
 * then the box's angular size at that distance for width and height.
 */
object DetectionProjector {
    private const val MAX_RANGE = 6.0

    fun project(frame: CameraFrame, d: Detection2D, floorY: Double, hitTest: HitTester?): Detection3D? {
        val category = Categories.fromDetectorLabel(d.label) ?: return null
        if (d.cutOffAtBottom) return null
        val origin = frame.pose.position
        val bottomRay = frame.ray(d.bottomCenter.x, d.bottomCenter.y)

        val base: Vec3 = if (Categories.standsOnFloor(category)) {
            intersectHorizontal(origin, bottomRay, floorY) ?: return null
        } else {
            hitTest?.invoke(origin, bottomRay) ?: intersectHorizontal(origin, bottomRay, floorY) ?: return null
        }
        val toBase = base - origin
        if (toBase.length > MAX_RANGE) return null

        // Vertical plane through the base point, facing the camera.
        val n = Vec3(toBase.x, 0.0, toBase.z).normalized()
        if (n.length < 0.5) return null
        val top = intersectPlane(origin, frame.ray(d.topCenter.x, d.topCenter.y), base, n) ?: return null
        val left = intersectPlane(origin, frame.ray(d.bottomLeft.x, d.bottomLeft.y), base, n) ?: return null
        val right = intersectPlane(origin, frame.ray(d.bottomRight.x, d.bottomRight.y), base, n) ?: return null

        val height = top.y - base.y
        val widthVec = (right - left).toPlan()
        val width = widthVec.length
        if (height !in 0.05..3.0 || width !in 0.05..4.0) return null

        val depth = Categories.depthFor(category, width)
        val away = n.toPlan()
        val center = base.toPlan() + away * (depth / 2)
        return Detection3D(
            category = category,
            center = center,
            width = width,
            depth = depth,
            height = height,
            yaw = widthVec.angle,
            baseHeight = (base.y - floorY).coerceAtLeast(0.0),
            confidence = d.confidence,
        )
    }

    private fun intersectHorizontal(o: Vec3, d: Vec3, y: Double): Vec3? {
        if (d.y > -0.02) return null
        val t = (y - o.y) / d.y
        return if (t > 0) o + d * t else null
    }

    private fun intersectPlane(o: Vec3, d: Vec3, p: Vec3, n: Vec3): Vec3? {
        val denom = d dot n
        if (abs(denom) < 1e-6) return null
        val t = ((p - o) dot n) / denom
        return if (t > 0) o + d * t else null
    }
}
