package com.eyecad.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs both capture paths end to end on synthetic data and writes the results to samples/. */
class SampleExportTest {
    private val samples = File(System.getProperty("eyecad.samples") ?: "build/samples").apply { mkdirs() }

    @Test
    fun cameraModeExport() {
        val session = CameraScanSession(CaptureMode.CAMERA_ARCORE)
        SyntheticRoom.planes().forEach(session::upsertPlane)
        val frame = CameraFrame(
            Pose.lookingAlong(SyntheticRoom.p(2.0, 0.5).toWorld(SyntheticRoom.FLOOR_Y + 1.4), (SyntheticRoom.p(2.0, 3.0) - SyntheticRoom.p(2.0, 0.5)).toWorld(-0.5)),
            500.0, 500.0, 320.0, 240.0, 640.0, 480.0,
        )
        fun pixel(p: Vec3): Vec2 {
            val d = p - frame.pose.position
            val z = d dot frame.pose.back
            return Vec2(frame.cx + frame.fx * (d dot frame.pose.right) / -z, frame.cy - frame.fy * (d dot frame.pose.up) / -z)
        }
        fun sighting(label: String, front: Vec2, width: Double, height: Double): Detection2D {
            val f = front.toWorld(SyntheticRoom.FLOOR_Y)
            val half = frame.pose.right * (width / 2)
            return Detection2D(label, 0.8, pixel(f), pixel(f + Vec3(0.0, height, 0.0)), pixel(f - half), pixel(f + half), false)
        }
        repeat(3) {
            session.addDetections(
                frame,
                listOf(sighting("couch", SyntheticRoom.p(2.0, 3.6), 1.8, 0.85), sighting("chair", SyntheticRoom.p(1.0, 2.2), 0.5, 0.9)),
                null,
            )
            session.addHeadingSample(frame.pose.forward, 75.0)
        }
        val export = session.export("Synthetic test", "2026-10-17T15:00:00Z", 43.2630, -2.9350, 20.0, null)
        assertEquals(4, export.plan.walls.size)
        assertEquals(setOf("sofa", "chair"), export.plan.objects.map { it.category }.toSet())
        File(samples, "camera-room.geojson").writeText(export.geoJson)
        File(samples, "camera-room.obj").writeText(export.obj)
        assertTrue(export.geoJson.contains("\"crs\":\"EPSG:4326\""))
    }

    @Test
    fun lidarModeExport() {
        val input = RoomPlanInput()
        val h = 2.6
        // 3.5 m x 4 m room, axis aligned, floor at y = -1.5.
        val fy = -1.5
        fun wall(cx: Double, cz: Double, ax: Vec3, w: Double) =
            input.addSurface("wall", Vec3(cx, fy + h / 2, cz), ax, w, h, 0.9, emptyList())
        wall(0.0, 0.0, Vec3(1.0, 0.0, 0.0), 3.5)
        wall(1.75, -2.0, Vec3(0.0, 0.0, -1.0), 4.0)
        wall(0.0, -4.0, Vec3(-1.0, 0.0, 0.0), 3.5)
        wall(-1.75, -2.0, Vec3(0.0, 0.0, 1.0), 4.0)
        input.addSurface("door", Vec3(0.8, fy + 1.0, 0.0), Vec3(1.0, 0.0, 0.0), 0.9, 2.0, 0.9, emptyList())
        input.addSurface("window", Vec3(1.75, fy + 1.5, -2.0), Vec3(0.0, 0.0, -1.0), 1.2, 1.1, 0.6, emptyList())
        input.addSurface(
            "floor", Vec3(0.0, fy, -2.0), Vec3(1.0, 0.0, 0.0), 3.5, 4.0, 0.9,
            listOf(Vec3(-1.75, fy, 0.0), Vec3(1.75, fy, 0.0), Vec3(1.75, fy, -4.0), Vec3(-1.75, fy, -4.0)),
        )
        input.addObject("bed", Vec3(-0.7, fy + 0.25, -2.5), Vec3(1.0, 0.0, 0.0), 1.6, 0.5, 2.0, 0.9)
        input.addObject("table", Vec3(1.2, fy + 0.375, -0.8), Vec3(0.0, 0.0, 1.0), 1.0, 0.75, 0.6, 0.6)

        val heading = HeadingCalibrator().apply { addSample(Vec3(0.0, 0.0, -1.0), 10.0) }
        val export = ScanExporter.exportRoomPlan(input, heading, "Synthetic LiDAR", "2026-10-17T15:00:00Z", 43.2630, -2.9350, null)
        assertEquals(4, export.plan.walls.size)
        assertEquals(2, export.plan.openings.size)
        assertEquals(0.95, export.plan.openings.first { it.type == OpeningType.WINDOW }.baseHeight, 1e-9)
        assertEquals(14.0, kotlin.math.abs(Geometry.signedArea(export.plan.floor)), 1e-9)
        File(samples, "lidar-room.geojson").writeText(export.geoJson)
        File(samples, "lidar-room.obj").writeText(export.obj)
    }
}
