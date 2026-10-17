package com.eyecad.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads the committed sample exports back, the way the preview screen opens saved files. */
class PreviewTest {
    private val samples = File(System.getProperty("eyecad.samples") ?: "../samples")

    private fun counts(m: PreviewModel) = m.solids.groupingBy { it.kind }.eachCount()

    @Test
    fun geoJsonAndObjReadBackTheSameRoom() {
        val geo = PreviewModel.fromFile("lidar-room.geojson", File(samples, "lidar-room.geojson").readText())
        val obj = PreviewModel.fromFile("lidar-room.obj", File(samples, "lidar-room.obj").readText())
        val expected = mapOf("room" to 1, "wall" to 4, "door" to 1, "window" to 1, "object" to 2)
        assertEquals(expected, counts(geo))
        assertEquals(expected, counts(obj))
        // Georeferenced GeoJSON maps back to the same local meters as the OBJ (3.5 m x 4 m room).
        assertEquals(geo.maxX - geo.minX, obj.maxX - obj.minX, 0.01)
        assertEquals(geo.maxY - geo.minY, obj.maxY - obj.minY, 0.01)
        assertEquals(3.64, obj.maxX - obj.minX, 0.01) // 3.5 m plus the west wall and the window box poking out east
        assertEquals(setOf("bed", "table"), obj.solids.filter { it.kind == "object" }.map { it.label }.toSet())
        val window = geo.solids.first { it.kind == "window" }
        assertEquals(0.95, window.base, 1e-6)
        assertTrue(geo.summary.startsWith("4 walls · 2 doors/windows · 2 objects"), geo.summary)
    }

    @Test
    fun rendersBothViews() {
        val m = PreviewModel.fromFile("camera-room.geojson", File(samples, "camera-room.geojson").readText())
        val plan = PreviewRenderer.topDown(m, 400.0, 600.0, 1.0)
        assertEquals(m.solids.size, plan.polygons.size)
        assertTrue(plan.polygons.flatMap { it.points }.all { it.x in 0.0..400.0 && it.y in 0.0..600.0 })
        val view = PreviewRenderer.perspective(m, 400.0, 600.0, 30.0, 35.0, 1.0)
        // Tops of everything plus at least one visible side per standing box.
        assertTrue(view.polygons.size >= m.solids.size + (m.solids.size - 1))
        assertEquals("room", view.polygons.first().kind)
        assertEquals(m.solids.count { it.kind == "object" }, view.labels.size) // every object shows its height
    }

    @Test
    fun captureRebuildsTheSameRoom() {
        val session = CameraScanSession(CaptureMode.CAMERA_ARCORE)
        SyntheticRoom.planes().forEach(session::upsertPlane)
        var id = 0L
        for (x in 10..28) for (y in 36..45) session.addPoint(id++, SyntheticRoom.p(x / 10.0, y / 10.0).x, SyntheticRoom.FLOOR_Y + 0.85, -SyntheticRoom.p(x / 10.0, y / 10.0).y)
        val export = session.export("test", "2026-10-17T00:00:00Z", null, null, null, null)
        val text = export.capture!!
        assertTrue(Capture.isCapture(text))
        val again = Capture.parse(text).floorPlan()
        assertEquals(export.plan.walls.size, again.walls.size)
        assertEquals(export.plan.objects.size, again.objects.size)
        assertEquals(counts(PreviewModel.fromPlan(export.plan, "a")), counts(PreviewModel.fromFile("x-capture.json", text)))
    }
}
