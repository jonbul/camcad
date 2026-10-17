package com.eyecad.core

/** How the scan was captured. Written into the export so the receiver knows how much to trust it. */
enum class CaptureMode(val id: String) {
    LIDAR_ROOMPLAN("lidar-roomplan"),
    CAMERA_ARKIT("camera-arkit"),
    CAMERA_ARCORE("camera-arcore"),
}

/** A wall as a centerline on the plan. Heights are relative to the floor. */
data class Wall(
    val start: Vec2,
    val end: Vec2,
    val height: Double,
    val thickness: Double = 0.1,
    val confidence: Double = 1.0,
) {
    val length get() = start.distanceTo(end)
}

enum class OpeningType(val id: String) { DOOR("door"), WINDOW("window"), OPENING("opening") }

/** A door, window or open passage, as a span along a wall. */
data class Opening(
    val type: OpeningType,
    val start: Vec2,
    val end: Vec2,
    val baseHeight: Double,
    val height: Double,
    val confidence: Double = 1.0,
)

/**
 * A detected object, as an oriented box standing on [baseHeight] above the floor.
 * [yaw] is the direction of the width axis in radians from plan +x.
 */
data class RoomObject(
    val category: String,
    val center: Vec2,
    val width: Double,
    val depth: Double,
    val height: Double,
    val yaw: Double,
    val baseHeight: Double = 0.0,
    val confidence: Double = 1.0,
    val observations: Int = 1,
)

data class FloorPlan(
    val walls: List<Wall>,
    val openings: List<Opening>,
    val objects: List<RoomObject>,
    /** Floor outline, counter-clockwise. */
    val floor: List<Vec2>,
    val ceilingHeight: Double,
    /** True when the ceiling height is a default rather than measured. */
    val heightEstimated: Boolean,
)

data class ScanMetadata(
    val mode: CaptureMode,
    val device: String,
    /** ISO-8601 timestamp. */
    val createdAt: String,
    /** Null when no location fix was available; the export then uses local meters. */
    val georef: Georef?,
    val appVersion: String = "1.0.0",
)

enum class PlaneKind { FLOOR, CEILING, WALL, DOOR, WINDOW, TABLE, SEAT, OTHER }

/** A plane reported by ARKit or ARCore, with its boundary already in world space. */
data class PlaneObservation(
    val id: String,
    val kind: PlaneKind,
    val normal: Vec3,
    val boundary: List<Vec3>,
)
