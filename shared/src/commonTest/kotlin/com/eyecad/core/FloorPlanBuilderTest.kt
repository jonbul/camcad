package com.eyecad.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A 4 m x 5 m room, rotated 17° in the AR world, seen as fragmented, noisy planes like ARCore reports them. */
object SyntheticRoom {
    const val W = 4.0
    const val D = 5.0
    const val FLOOR_Y = -1.35
    val rotation = 17 * PI / 180

    /** Room-local plan point to AR world plan point. */
    fun p(x: Double, y: Double) = Vec2(x, y).rotated(rotation)

    private fun wallPlane(id: String, a: Vec2, b: Vec2, inward: Vec2, top: Double, wobble: Double): PlaneObservation {
        val wa = a + inward * wobble
        val wb = b + inward * -wobble
        val n = inward
        return PlaneObservation(
            id, PlaneKind.WALL, Vec3(n.x, 0.0, -n.y),
            listOf(
                wa.toWorld(FLOOR_Y + 0.3), wb.toWorld(FLOOR_Y + 0.3),
                wb.toWorld(FLOOR_Y + top), wa.toWorld(FLOOR_Y + top),
            ),
        )
    }

    fun planes(): List<PlaneObservation> {
        val c = listOf(p(0.0, 0.0), p(W, 0.0), p(W, D), p(0.0, D))
        val inward = listOf(Vec2(0.0, 1.0), Vec2(-1.0, 0.0), Vec2(0.0, -1.0), Vec2(1.0, 0.0)).map { it.rotated(rotation) }
        val out = mutableListOf<PlaneObservation>()
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            // Each wall seen as two pieces that stop short of the corners, slightly misaligned.
            val m = a + (b - a) * 0.55
            val n = a + (b - a) * 0.45
            out += wallPlane("w${i}a", a + (b - a).normalized() * 0.4, m, inward[i], 1.6, 0.02)
            out += wallPlane("w${i}b", n, b - (b - a).normalized() * 0.5, inward[i], 1.4, -0.03)
        }
        val floor = listOf(p(0.5, 0.5), p(3.5, 0.6), p(3.4, 4.4), p(0.6, 4.5)).map { it.toWorld(FLOOR_Y) }
        out += PlaneObservation("floor", PlaneKind.FLOOR, Vec3(0.0, 1.0, 0.0), floor)
        // A table top must not be mistaken for the floor.
        out += PlaneObservation("table", PlaneKind.FLOOR, Vec3(0.0, 1.0, 0.0),
            listOf(p(1.0, 1.0), p(2.0, 1.0), p(2.0, 2.0), p(1.0, 2.0)).map { it.toWorld(FLOOR_Y + 0.75) })
        return out
    }
}

class FloorPlanBuilderTest {
    @Test
    fun rebuildsFourCornerRoom() {
        val plan = FloorPlanBuilder.build(SyntheticRoom.planes())
        assertEquals(4, plan.walls.size, "walls: ${plan.walls}")
        val corners = listOf(
            SyntheticRoom.p(0.0, 0.0), SyntheticRoom.p(SyntheticRoom.W, 0.0),
            SyntheticRoom.p(SyntheticRoom.W, SyntheticRoom.D), SyntheticRoom.p(0.0, SyntheticRoom.D),
        )
        val ends = plan.walls.flatMap { listOf(it.start, it.end) }
        for (c in corners) {
            val nearest = ends.minOf { it.distanceTo(c) }
            assertTrue(nearest < 0.08, "corner $c is ${nearest}m from the nearest wall end")
        }
        val area = abs(Geometry.signedArea(plan.floor))
        assertTrue(abs(area - SyntheticRoom.W * SyntheticRoom.D) < 0.5, "area $area")
        assertTrue(plan.heightEstimated)
    }

    @Test
    fun floorIgnoresTableTop() {
        assertEquals(SyntheticRoom.FLOOR_Y, FloorPlanBuilder.floorHeight(SyntheticRoom.planes())!!, 1e-9)
    }

    @Test
    fun tableTopBecomesAnObject() {
        val plan = FloorPlanBuilder.build(SyntheticRoom.planes())
        val table = plan.objects.single()
        assertEquals(0.0, table.baseHeight)
        assertEquals(0.75, table.height, 1e-9)
        assertTrue(table.center.distanceTo(SyntheticRoom.p(1.5, 1.5)) < 0.15, "table at ${table.center}")
    }

    /**
     * Wall pieces from a real Pixel 8 Pro scan of a square room (local meters, location removed). ARCore
     * saw the walls in pieces plus a few furniture faces at odd angles; the result must be one closed,
     * squared room.
     */
    @Test
    fun pixelScanBecomesClosedRectangle() {
        val pieces = listOf(
            -0.44 to 1.97 to (-0.05 to 1.68), -2.55 to -0.77 to (-1.83 to 0.20), -0.63 to 0.70 to (-0.44 to 1.97),
            -3.17 to -1.32 to (-2.99 to -1.08), -1.24 to -2.91 to (-2.48 to -2.00), -0.92 to -2.68 to (-1.81 to -2.71),
            0.15 to -1.03 to (-1.24 to -2.91), -0.63 to 0.70 to (-0.01 to 0.66), -1.50 to 0.64 to (-1.06 to 1.23),
        ).mapIndexed { i, (a, b) ->
            val pa = Vec2(a.first, a.second)
            val pb = Vec2(b.first, b.second)
            val n = (pb - pa).normalized().perp()
            PlaneObservation(
                "w$i", PlaneKind.WALL, Vec3(n.x, 0.0, -n.y),
                listOf(pa.toWorld(-1.1), pb.toWorld(-1.1), pb.toWorld(0.2), pa.toWorld(0.2)),
            )
        }
        val floor = PlaneObservation(
            "floor", PlaneKind.FLOOR, Vec3(0.0, 1.0, 0.0),
            listOf(Vec2(-2.0, -2.0), Vec2(-0.5, -2.0), Vec2(-0.5, 0.5), Vec2(-2.0, 0.5)).map { it.toWorld(-1.4) },
        )
        val plan = FloorPlanBuilder.build(pieces + floor)
        assertEquals(4, plan.walls.size, "walls: ${plan.walls}")
        for (i in 0 until 4) {
            val a = plan.walls[i]
            val b = plan.walls[(i + 1) % 4]
            assertTrue(a.end.distanceTo(b.start) < 1e-6, "walls $i and ${i + 1} don't meet")
            val cos = ((a.end - a.start).normalized() dot (b.end - b.start).normalized())
            assertTrue(abs(cos) < 1e-6, "corner $i is not square")
        }
        val area = abs(Geometry.signedArea(plan.floor))
        assertTrue(area in 8.0..12.0, "area $area")
    }
}

class ObjectFinderTest {
    @Test
    fun boxOfPointsBecomesOneObjectAndWallPointsDont() {
        val points = mutableListOf<Vec3>()
        // A 1.8 x 0.9 m sofa, 0.85 m tall, in room coordinates.
        var x = 1.0
        while (x <= 2.8) {
            var y = 3.6
            while (y <= 4.5) {
                points += SyntheticRoom.p(x, y).toWorld(SyntheticRoom.FLOOR_Y + 0.85)
                points += SyntheticRoom.p(x, y).toWorld(SyntheticRoom.FLOOR_Y + 0.4)
                y += 0.1
            }
            x += 0.1
        }
        // Feature points on a wall and just above the floor must be ignored.
        for (k in 0..40) {
            points += SyntheticRoom.p(0.05 * k, 0.02).toWorld(SyntheticRoom.FLOOR_Y + 1.2)
            points += SyntheticRoom.p(3.0, 0.1 * k).toWorld(SyntheticRoom.FLOOR_Y + 0.03)
        }
        val plan = FloorPlanBuilder.build(SyntheticRoom.planes().filter { it.id != "table" }, points)
        val sofa = plan.objects.single()
        assertEquals(0.0, sofa.baseHeight)
        assertEquals(0.85, sofa.height, 0.02)
        assertEquals(1.9, sofa.width, 0.25)
        assertEquals(1.0, sofa.depth, 0.25)
        assertTrue(sofa.center.distanceTo(SyntheticRoom.p(1.9, 4.05)) < 0.15, "sofa at ${sofa.center}")
    }
}

class GeorefTest {
    @Test
    fun georefRotatesPlanToNorth() {
        // Camera looks along world -X while the compass says that's due north.
        val g = Georef.fromCameraHeading(43.0, -2.0, Vec3(-1.0, 0.0, 0.0), 0.0)
        val en = g.toEastNorth(Vec2(-10.0, 0.0))
        assertEquals(0.0, en.x, 1e-9)
        assertEquals(10.0, en.y, 1e-9)
        val ll = g.toLonLat(Vec2(-10.0, 0.0))
        assertEquals(43.0 + 10.0 / Georef.METERS_PER_DEGREE, ll.y, 1e-12)
    }

    @Test
    fun headingCalibratorAveragesAcrossNorth() {
        val h = HeadingCalibrator()
        h.addSample(Vec3(0.0, 0.0, -1.0), 358.0)
        h.addSample(Vec3(0.0, 0.0, -1.0), 4.0)
        assertEquals(1.0, h.rotationDeg()!!, 1e-6)
    }

    @Test
    fun numberFormatting() {
        assertEquals("-2.5", Format.num(-2.5, 3))
        assertEquals("0", Format.num(-0.0001, 3))
        assertEquals("43.000089831", Format.num(43.0 + 10.0 / Georef.METERS_PER_DEGREE, 9))
        assertEquals("1.05", Format.num(1.0499999, 3))
    }

    @Test
    fun triangulatesConcaveFloor() {
        val l = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0))
        val tris = Geometry.triangulate(l)
        assertEquals(4, tris.size)
        val area = tris.sumOf { abs(Geometry.signedArea(listOf(l[it[0]], l[it[1]], l[it[2]]))) }
        assertEquals(12.0, area, 1e-9)
    }
}
