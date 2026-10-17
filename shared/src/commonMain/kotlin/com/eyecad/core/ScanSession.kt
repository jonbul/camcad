package com.eyecad.core

/** Live counters for the scanning screen. */
data class ScanStats(val planes: Int, val walls: Int, val objects: Int, val points: Int, val headingSamples: Int)

/** The export files for one scan; [capture] is the raw AR data of camera scans (see [Capture]). */
data class ScanExport(val geoJson: String, val obj: String, val plan: FloorPlan, val capture: String? = null)

/**
 * The shared brain of a camera-only scan (ARCore on Android, ARKit on iPhones without LiDAR).
 *
 * The platform layer only forwards what its AR framework reports: planes as they appear and change,
 * tracked feature points, and compass readings. Call it from one thread (the AR callback thread).
 */
class CameraScanSession(private val mode: CaptureMode) {
    private val planes = LinkedHashMap<String, PlaneObservation>()
    // Latest position per tracked point id, oldest first so the cap drops stale points.
    private val points = LinkedHashMap<Long, Vec3>()
    private val heading = HeadingCalibrator()
    private var lastCameraY: Double? = null

    fun upsertPlane(plane: PlaneObservation) {
        planes[plane.id] = plane
    }

    fun removePlane(id: String) {
        planes.remove(id)
    }

    /** Adds or moves a tracked feature point (ARCore point cloud / ARKit raw feature points). */
    fun addPoint(id: Long, x: Double, y: Double, z: Double) {
        points.remove(id)
        points[id] = Vec3(x, y, z)
        if (points.size > MAX_POINTS) points.remove(points.keys.first())
    }

    fun updateCamera(position: Vec3) {
        lastCameraY = position.y
    }

    fun addHeadingSample(cameraForward: Vec3, trueBearingDeg: Double) = heading.addSample(cameraForward, trueBearingDeg)

    fun stats(): ScanStats {
        val plan = floorPlan()
        return ScanStats(planes.size, plan.walls.size, plan.objects.size, points.size, heading.samples)
    }

    fun floorPlan(): FloorPlan = FloorPlanBuilder.build(planes.values.toList(), points.values.toList(), fallbackFloorY())

    /**
     * When the AR session itself is aligned to true north (ARKit gravityAndHeading), pass [rotationDeg] = 0
     * instead of relying on compass samples. Without a location, the export uses local meters.
     */
    fun export(
        device: String,
        createdAt: String,
        latitude: Double?,
        longitude: Double?,
        altitude: Double?,
        rotationDeg: Double?,
    ): ScanExport {
        val georef = if (latitude != null && longitude != null) {
            val r = rotationDeg ?: heading.rotationDeg()
            if (r != null) Georef(latitude, longitude, r, altitude) else null
        } else null
        val capture = capture()
        return ScanExporter.export(capture.floorPlan(), ScanMetadata(mode, device, createdAt, georef))
            .copy(capture = capture.write())
    }

    fun capture(): Capture = Capture(mode, planes.values.toList(), points.values.toList(), fallbackFloorY())

    /** Before the floor is detected, assume it is ~1.4 m below the phone. */
    private fun fallbackFloorY(): Double = (lastCameraY ?: 0.0) - 1.4

    private companion object {
        const val MAX_POINTS = 60_000
    }
}

/** Helpers for the LiDAR path, where RoomPlan already produced the room. */
object ScanExporter {
    fun export(plan: FloorPlan, meta: ScanMetadata): ScanExport =
        ScanExport(GeoJsonWriter.write(plan, meta), ObjWriter.write(plan, meta), plan)

    fun exportRoomPlan(
        input: RoomPlanInput,
        heading: HeadingCalibrator,
        device: String,
        createdAt: String,
        latitude: Double?,
        longitude: Double?,
        altitude: Double?,
    ): ScanExport {
        val georef = if (latitude != null && longitude != null) heading.georef(latitude, longitude, altitude) else null
        return export(RoomPlanConverter.convert(input), ScanMetadata(CaptureMode.LIDAR_ROOMPLAN, device, createdAt, georef))
    }
}
