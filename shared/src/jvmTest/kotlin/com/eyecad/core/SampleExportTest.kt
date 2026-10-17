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
        // Feature points on a sofa (1.8 x 0.9 m, 0.85 m tall) and an armchair; the table top is a plane.
        var id = 0L
        fun box(x0: Double, y0: Double, w: Double, d: Double, h: Double) {
            var x = x0
            while (x <= x0 + w) {
                var y = y0
                while (y <= y0 + d) {
                    for (z in listOf(h, h / 2)) {
                        val p = SyntheticRoom.p(x, y).toWorld(SyntheticRoom.FLOOR_Y + z)
                        session.addPoint(id++, p.x, p.y, p.z)
                    }
                    y += 0.1
                }
                x += 0.1
            }
        }
        box(1.0, 3.6, 1.8, 0.9, 0.85)
        box(3.0, 2.0, 0.6, 0.6, 0.9)
        val forward = (SyntheticRoom.p(2.0, 3.0) - SyntheticRoom.p(2.0, 0.5)).toWorld(0.0)
        repeat(3) { session.addHeadingSample(forward, 75.0) }
        val export = session.export("Synthetic test", "2026-10-17T15:00:00Z", 43.2630, -2.9350, 20.0, null)
        assertEquals(4, export.plan.walls.size)
        assertEquals(3, export.plan.objects.size, "objects: ${export.plan.objects}")
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
