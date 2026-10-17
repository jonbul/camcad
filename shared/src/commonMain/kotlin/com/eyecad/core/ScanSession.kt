package com.eyecad.core

/** Live counters for the scanning screen. */
data class ScanStats(val planes: Int, val walls: Int, val objects: Int, val headingSamples: Int)

/** Both export files for one scan. */
data class ScanExport(val geoJson: String, val obj: String, val plan: FloorPlan)

/**
 * The shared brain of a camera-only scan (ARCore on Android, ARKit on iPhones without LiDAR).
 *
 * The platform layer only forwards what its AR framework reports: planes as they appear and change,
 * object detections with the camera frame they came from, and compass readings. Call it from one
 * thread (the AR callback thread).
 */
class CameraScanSession(private val mode: CaptureMode) {
    private val planes = LinkedHashMap<String, PlaneObservation>()
    private val tracker = ObjectTracker(minObservations = 2)
    private val heading = HeadingCalibrator()
    private var lastCameraY: Double? = null

    fun upsertPlane(plane: PlaneObservation) {
        planes[plane.id] = plane
    }

    fun removePlane(id: String) {
        planes.remove(id)
    }

    /** Projects detections from one frame into the room. Returns how many were placed. */
    fun addDetections(frame: CameraFrame, detections: List<Detection2D>, hitTest: HitTester?): Int {
        lastCameraY = frame.pose.position.y
        val floorY = currentFloorY()
        var placed = 0
        for (d in detections) {
            val d3 = DetectionProjector.project(frame, d, floorY, hitTest) ?: continue
            tracker.add(d3)
            placed++
        }
        return placed
    }

    fun addHeadingSample(cameraForward: Vec3, trueBearingDeg: Double) = heading.addSample(cameraForward, trueBearingDeg)

    fun stats(): ScanStats {
        val plan = FloorPlanBuilder.build(planes.values.toList(), emptyList(), currentFloorY())
        return ScanStats(planes.size, plan.walls.size, tracker.objects().size, heading.samples)
    }

    fun floorPlan(): FloorPlan = FloorPlanBuilder.build(planes.values.toList(), tracker.objects(), currentFloorY())

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
        return ScanExporter.export(floorPlan(), ScanMetadata(mode, device, createdAt, georef))
    }

    /** Before the floor is detected, assume it is ~1.4 m below the phone. */
    private fun currentFloorY(): Double =
        FloorPlanBuilder.floorHeight(planes.values.toList()) ?: ((lastCameraY ?: 0.0) - 1.4)
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
