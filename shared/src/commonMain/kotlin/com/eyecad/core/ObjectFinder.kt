package com.eyecad.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Finds objects without recognising them: anything that stands inside the room, off the walls, is an
 * object. Evidence comes from AR feature points, horizontal surfaces above the floor (table and seat
 * tops) and short vertical planes (furniture sides). It is binned into a 10 cm grid aligned with the
 * room, and each connected blob of occupied cells becomes one box.
 */
object ObjectFinder {
    private const val CELL = 0.1
    /** Points closer than this to a wall belong to the wall. */
    private const val WALL_MARGIN = 0.2
    private const val MIN_HEIGHT = 0.08
    /** AR point clouds are sparse, so single points count; a blob needs this many to be an object. */
    private const val MIN_POINTS = 12
    /** Cells this many apart still count as one object (bridges sparse point coverage). */
    private const val LINK = 2

    private class Cell {
        val heights = mutableListOf<Double>()
        var hits = 0
        var surface = false
    }

    fun find(
        points: List<Vec3>,
        surfaces: List<Pair<List<Vec2>, Double>>,
        sides: List<Triple<Vec2, Vec2, Double>>,
        outline: List<Vec2>,
        walls: List<Wall>,
        floorY: Double,
        ceiling: Double,
        frameAngle: Double,
    ): List<RoomObject> {
        val u = Vec2.fromAngle(frameAngle)
        val v = u.perp()
        val grid = HashMap<Long, Cell>()
        fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        fun cellOf(p: Vec2) = floor((p dot u) / CELL).toInt() to floor((p dot v) / CELL).toInt()
        fun center(i: Int, j: Int) = u * ((i + 0.5) * CELL) + v * ((j + 0.5) * CELL)
        fun inRoom(p: Vec2) =
            (outline.size < 3 || Geometry.contains(outline, p)) &&
                walls.none { Geometry.distanceToSegment(p, it.start, it.end) < WALL_MARGIN + it.thickness / 2 }

        for (pt in points) {
            val h = pt.y - floorY
            if (h < MIN_HEIGHT || h > ceiling - 0.15) continue
            val p = pt.toPlan()
            if (!inRoom(p)) continue
            val (i, j) = cellOf(p)
            grid.getOrPut(key(i, j)) { Cell() }.apply { heights += h; hits++ }
        }
        for ((poly, h) in surfaces) {
            if (poly.size < 3) continue
            val su = poly.map { it dot u }
            val sv = poly.map { it dot v }
            for (i in floor(su.min() / CELL).toInt()..ceil(su.max() / CELL).toInt())
                for (j in floor(sv.min() / CELL).toInt()..ceil(sv.max() / CELL).toInt()) {
                    val c = center(i, j)
                    if (!Geometry.contains(poly, c) || !inRoom(c)) continue
                    grid.getOrPut(key(i, j)) { Cell() }.apply { heights += h; surface = true }
                }
        }
        for ((a, b, top) in sides) {
            val steps = max(1, (a.distanceTo(b) / (CELL / 2)).toInt())
            for (k in 0..steps) {
                val p = a + (b - a) * (k.toDouble() / steps)
                if (!inRoom(p)) continue
                val (i, j) = cellOf(p)
                grid.getOrPut(key(i, j)) { Cell() }.apply { heights += top; surface = true }
            }
        }

        val occupied = grid.filterValues { it.surface || it.hits > 0 }
        val seen = HashSet<Long>()
        val objects = mutableListOf<RoomObject>()
        for (start in occupied.keys) {
            if (!seen.add(start)) continue
            val blob = mutableListOf(start)
            var q = 0
            while (q < blob.size) {
                val k = blob[q++]
                val ci = (k shr 32).toInt()
                val cj = k.toInt()
                for (di in -LINK..LINK) for (dj in -LINK..LINK) {
                    val n = key(ci + di, cj + dj)
                    if (n in occupied && seen.add(n)) blob += n
                }
            }
            val cells = blob.map { occupied.getValue(it) }
            val pointCount = cells.sumOf { it.hits }
            if (pointCount < MIN_POINTS && cells.none { it.surface }) continue
            val heights = cells.flatMap { it.heights }.sorted()
            val top = heights[(heights.size * 0.95).toInt().coerceAtMost(heights.size - 1)]
            val low = heights[(heights.size * 0.05).toInt()]
            // Points mostly land on top surfaces, so anything whose lowest evidence is below ~0.9 m stands on
            // the floor; higher blobs are wall shelves, TVs and the like.
            val base = if (low < 0.9) 0.0 else low
            val height = if (base > 0) max(top - base, 0.1) else top
            if (height < 0.1) continue
            val iMin = blob.minOf { (it shr 32).toInt() }; val iMax = blob.maxOf { (it shr 32).toInt() }
            val jMin = blob.minOf { it.toInt() }; val jMax = blob.maxOf { it.toInt() }
            val width = (iMax - iMin + 1) * CELL
            val depth = (jMax - jMin + 1) * CELL
            val c = u * ((iMin + iMax + 1) * CELL / 2) + v * ((jMin + jMax + 1) * CELL / 2)
            val evidence = pointCount + cells.count { it.surface } * 5
            objects += RoomObject(
                category = "object",
                center = c,
                width = width,
                depth = depth,
                height = height,
                yaw = frameAngle,
                baseHeight = base,
                confidence = min(1.0, evidence / 40.0),
                observations = pointCount,
            )
        }
        return objects.sortedByDescending { it.width * it.depth }
    }
}
