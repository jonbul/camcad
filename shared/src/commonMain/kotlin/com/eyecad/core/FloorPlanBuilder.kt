package com.eyecad.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Builds a floor plan from what a camera-only AR session sees (ARCore, or ARKit without LiDAR).
 *
 * Vertical planes become wall segments. Those along the room's two main directions are merged into wall
 * lines; when the outer lines enclose a room, it is squared up into a rectangle whose sides are the
 * walls. Short vertical planes inside the room are furniture sides, not walls. Everything that stands
 * inside the room (feature points, table and seat tops, furniture sides) becomes objects, see [ObjectFinder].
 */
object FloorPlanBuilder {
    const val DEFAULT_CEILING = 2.5
    private const val MIN_WALL_LENGTH = 0.3
    private const val SNAP_TOLERANCE = 20 * PI / 180
    private const val MERGE_OFFSET = 0.25
    // Walls are often seen in pieces with furniture in between, so pieces this far apart still merge.
    private const val MERGE_GAP = 1.2
    private const val CORNER_REACH = 0.75
    /** A wall line within this distance of the room's outer line is that side of the room. */
    private const val SIDE_TOLERANCE = 0.35
    /** Interior wall lines at least this long are partitions; shorter ones are furniture. */
    private const val PARTITION_LENGTH = 1.5

    fun floorHeight(planes: List<PlaneObservation>): Double? {
        val floors = planes.filter { it.kind == PlaneKind.FLOOR && it.boundary.size >= 3 }
        if (floors.isEmpty()) return null
        // The lowest plane that is reasonably big; small low patches are often noise.
        val big = floors.filter { area(it) >= 0.5 }.ifEmpty { floors }
        return big.minOf { p -> p.boundary.sumOf { it.y } / p.boundary.size }
    }

    fun build(
        planes: List<PlaneObservation>,
        points: List<Vec3> = emptyList(),
        fallbackFloorY: Double = 0.0,
    ): FloorPlan {
        val floorY = floorHeight(planes) ?: fallbackFloorY

        val ceilings = planes.filter { it.kind == PlaneKind.CEILING && it.boundary.isNotEmpty() }
            .map { p -> p.boundary.sumOf { it.y } / p.boundary.size - floorY }
            .filter { it in 1.8..5.0 }
        val ceiling = ceilings.maxOrNull()
        val height = ceiling ?: DEFAULT_CEILING

        val segments = planes.filter { it.kind == PlaneKind.WALL }.mapNotNull { segmentOf(it, floorY) }
        val dominant = dominantAngle(segments)
        val aligned = mutableListOf<Segment>()
        val furniture = mutableListOf<Segment>()
        for (seg in segments) {
            val snapped = snap(seg, dominant)
            when {
                dominant == null || snapped !== seg -> aligned += snapped
                // Off-axis planes: long ones are real walls, short ones are furniture or noise.
                seg.length >= PARTITION_LENGTH -> aligned += seg
                else -> furniture += seg
            }
        }
        val merged = merge(aligned)
        val floorPoints = planes
            .filter { it.kind == PlaneKind.FLOOR && abs(it.boundary.sumOf { b -> b.y } / it.boundary.size - floorY) < 0.15 }
            .flatMap { p -> p.boundary.map { it.toPlan() } }

        val walls: List<Wall>
        val outline: List<Vec2>
        val room = dominant?.let { squareUp(merged, it, floorPoints) }
        if (room != null) {
            outline = room.corners
            walls = room.sides.map { side ->
                Wall(side.a, side.b, height = max(height, side.top), thickness = 0.1, confidence = side.confidence)
            } + room.interior.filter { it.length >= PARTITION_LENGTH }.map {
                Wall(it.a, it.b, height = max(height, it.top), thickness = 0.1, confidence = it.confidence)
            }
            furniture += room.interior.filter { it.length < PARTITION_LENGTH }
        } else {
            walls = joinCorners(merged).filter { it.length >= MIN_WALL_LENGTH }.map {
                Wall(it.a, it.b, height = max(height, it.top), thickness = 0.1, confidence = it.confidence)
            }
            outline = Geometry.convexHull(floorPoints + walls.flatMap { listOf(it.start, it.end) })
        }

        val openings = planes.filter { it.kind == PlaneKind.DOOR || it.kind == PlaneKind.WINDOW }
            .mapNotNull { p -> segmentOf(p, floorY)?.let { attachToWall(p, snap(it, dominant), walls) } }

        // Horizontal surfaces clearly above the floor are tables, seats, shelves and so on.
        val tops = planes.filter { it.kind != PlaneKind.CEILING && it.kind != PlaneKind.WALL && it.normal.y > 0.7 && it.boundary.size >= 3 }
            .mapNotNull { p ->
                val h = p.boundary.sumOf { it.y } / p.boundary.size - floorY
                if (h in 0.2..(height - 0.3)) p.boundary.map { it.toPlan() } to h else null
            }
        val objects = ObjectFinder.find(
            points = points,
            surfaces = tops,
            sides = furniture.map { Triple(it.a, it.b, it.top) },
            outline = outline,
            walls = walls,
            floorY = floorY,
            ceiling = height,
            frameAngle = dominant ?: 0.0,
        )
        return FloorPlan(walls, openings, objects, outline, height, heightEstimated = ceiling == null)
    }

    internal class Room(val corners: List<Vec2>, val sides: List<Segment>, val interior: List<Segment>)

    /**
     * Fits the room as a rectangle along the dominant axes: each side is the outermost wall line on that
     * side, or the floor's extent where no wall was seen. Needs at least two real wall lines.
     */
    internal fun squareUp(segments: List<Segment>, dominant: Double, floorPoints: List<Vec2>): Room? {
        val u = Vec2.fromAngle(dominant)
        val v = u.perp()
        val alongU = segments.filter { abs(it.dir dot u) > 0.9 && it.length >= 0.4 }
        val alongV = segments.filter { abs(it.dir dot v) > 0.9 && it.length >= 0.4 }
        val allPoints = floorPoints + segments.flatMap { listOf(it.a, it.b) }
        if (allPoints.isEmpty()) return null

        fun mid(s: Segment) = (s.a + s.b) * 0.5
        // Sides from wall lines (offsets along the perpendicular axis), else from everything seen.
        val vOffsets = alongU.map { mid(it) dot v }
        val uOffsets = alongV.map { mid(it) dot u }
        var real = 0
        fun bound(offsets: List<Double>, extent: List<Double>, pickMin: Boolean): Double {
            val line = if (pickMin) offsets.minOrNull() else offsets.maxOrNull()
            val ext = if (pickMin) extent.min() else extent.max()
            // Use the wall line unless everything else lies well beyond it (it's then an interior line).
            return if (line != null && (if (pickMin) ext > line - 0.5 else ext < line + 0.5)) { real++; line } else ext
        }
        val vMin = bound(vOffsets, allPoints.map { it dot v }, true)
        val vMax = bound(vOffsets, allPoints.map { it dot v }, false)
        val uMin = bound(uOffsets, allPoints.map { it dot u }, true)
        val uMax = bound(uOffsets, allPoints.map { it dot u }, false)
        if (real < 2 || uMax - uMin < 1.0 || vMax - vMin < 1.0) return null

        fun p(a: Double, b: Double) = u * a + v * b
        val corners = listOf(p(uMin, vMin), p(uMax, vMin), p(uMax, vMax), p(uMin, vMax))
        val sideDefs = listOf(
            Triple(corners[0], corners[1], alongU.filter { abs((mid(it) dot v) - vMin) < SIDE_TOLERANCE }),
            Triple(corners[1], corners[2], alongV.filter { abs((mid(it) dot u) - uMax) < SIDE_TOLERANCE }),
            Triple(corners[2], corners[3], alongU.filter { abs((mid(it) dot v) - vMax) < SIDE_TOLERANCE }),
            Triple(corners[3], corners[0], alongV.filter { abs((mid(it) dot u) - uMin) < SIDE_TOLERANCE }),
        )
        val used = sideDefs.flatMap { it.third }.toSet()
        val sides = sideDefs.map { (a, b, seen) ->
            val coverage = seen.sumOf { it.length } / a.distanceTo(b)
            // A side no wall was seen on still closes the room, with low confidence.
            Segment(a, b, 0.0, seen.maxOfOrNull { it.top } ?: 0.0, if (seen.isEmpty()) 0.2 else coverage.coerceIn(0.3, 1.0))
        }
        return Room(corners, sides, segments.filter { it !in used })
    }
    internal data class Segment(val a: Vec2, val b: Vec2, val bottom: Double, val top: Double, val confidence: Double) {
        val length get() = a.distanceTo(b)
        val dir get() = (b - a).normalized()
    }

    /** Spans a vertical plane's boundary along its horizontal tangent. */
    internal fun segmentOf(p: PlaneObservation, floorY: Double): Segment? {
        if (p.boundary.size < 2) return null
        val n = Vec2(p.normal.x, -p.normal.z).normalized()
        if (n.length < 0.5) return null
        val t = n.perp()
        val pts = p.boundary.map { it.toPlan() }
        val offset = pts.sumOf { it dot n } / pts.size
        val tMin = pts.minOf { it dot t }
        val tMax = pts.maxOf { it dot t }
        if (tMax - tMin < 0.1) return null
        val bottom = p.boundary.minOf { it.y } - floorY
        val top = p.boundary.maxOf { it.y } - floorY
        // Planes that barely rise above the floor are furniture sides rather than walls.
        val conf = ((top - bottom) / 1.5).coerceIn(0.2, 1.0)
        return Segment(n * offset + t * tMin, n * offset + t * tMax, bottom, top, conf)
    }

    /** Length-weighted mean of segment angles modulo 90°, i.e. the room's main axis. */
    internal fun dominantAngle(segments: List<Segment>): Double? {
        if (segments.isEmpty()) return null
        fun mean(segs: List<Segment>): Double {
            var s = 0.0
            var c = 0.0
            for (seg in segs) {
                val a = 4 * seg.dir.angle
                s += sin(a) * seg.length
                c += cos(a) * seg.length
            }
            return atan2(s, c) / 4
        }
        // Refine once with only the segments near the first estimate, so odd-angle planes don't skew it.
        val first = mean(segments)
        val near = segments.filter { abs(Geometry.wrapPi(4 * (it.dir.angle - first))) < 4 * SNAP_TOLERANCE }
        return if (near.isEmpty()) first else mean(near)
    }

    /** Rotates a segment about its midpoint onto the nearest dominant direction, if it's close enough. */
    internal fun snap(seg: Segment, dominant: Double?): Segment {
        if (dominant == null) return seg
        val angle = seg.dir.angle
        val k = round((angle - dominant) / (PI / 2))
        val target = dominant + k * PI / 2
        if (abs(Geometry.wrapPi(angle - target)) > SNAP_TOLERANCE) return seg
        val mid = (seg.a + seg.b) * 0.5
        val half = Vec2.fromAngle(target) * (seg.length / 2)
        return seg.copy(a = mid - half, b = mid + half)
    }

    /** Merges segments that lie on the same line and overlap or nearly touch. */
    internal fun merge(input: List<Segment>): List<Segment> {
        val segs = input.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in segs.indices) for (j in i + 1 until segs.size) {
                val m = tryMerge(segs[i], segs[j]) ?: continue
                segs[i] = m
                segs.removeAt(j)
                changed = true
                break@loop
            }
        }
        return segs
    }

    private fun tryMerge(s1: Segment, s2: Segment): Segment? {
        val d = s1.dir
        if (abs(d cross s2.dir) > sin(5 * PI / 180)) return null
        val n = d.perp()
        val o1 = s1.a dot n
        val o2 = ((s2.a + s2.b) * 0.5) dot n
        if (abs(o1 - o2) > MERGE_OFFSET) return null
        val a1 = s1.a dot d; val b1 = s1.b dot d
        val a2 = min(s2.a dot d, s2.b dot d); val b2 = max(s2.a dot d, s2.b dot d)
        val gap = max(a2 - b1, a1 - b2)
        if (gap > MERGE_GAP) return null
        val lo = min(a1, a2)
        val hi = max(b1, b2)
        val w1 = s1.length; val w2 = s2.length
        val offset = (o1 * w1 + o2 * w2) / (w1 + w2)
        return Segment(
            n * offset + d * lo, n * offset + d * hi,
            min(s1.bottom, s2.bottom), max(s1.top, s2.top),
            max(s1.confidence, s2.confidence),
        )
    }

    /** Extends or trims wall ends to meet when two non-parallel walls end near a common corner. */
    internal fun joinCorners(input: List<Segment>): List<Segment> {
        val segs = input.toMutableList()
        for (i in segs.indices) for (j in i + 1 until segs.size) {
            val s1 = segs[i]; val s2 = segs[j]
            if (abs(s1.dir cross s2.dir) < sin(60 * PI / 180)) continue
            val x = Geometry.lineIntersection(s1.a, s1.b, s2.a, s2.b) ?: continue
            val e1 = if (s1.a.distanceTo(x) < s1.b.distanceTo(x)) 0 else 1
            val e2 = if (s2.a.distanceTo(x) < s2.b.distanceTo(x)) 0 else 1
            val d1 = if (e1 == 0) s1.a.distanceTo(x) else s1.b.distanceTo(x)
            val d2 = if (e2 == 0) s2.a.distanceTo(x) else s2.b.distanceTo(x)
            if (d1 > CORNER_REACH || d2 > CORNER_REACH) continue
            segs[i] = if (e1 == 0) s1.copy(a = x) else s1.copy(b = x)
            segs[j] = if (e2 == 0) s2.copy(a = x) else s2.copy(b = x)
        }
        return segs
    }

    private fun attachToWall(p: PlaneObservation, seg: Segment, walls: List<Wall>): Opening {
        val type = if (p.kind == PlaneKind.DOOR) OpeningType.DOOR else OpeningType.WINDOW
        var a = seg.a
        var b = seg.b
        val mid = (a + b) * 0.5
        val wall = walls.minByOrNull { distanceToLine(mid, it.start, it.end) }
        if (wall != null && distanceToLine(mid, wall.start, wall.end) < 0.3) {
            val d = (wall.end - wall.start).normalized()
            a = wall.start + d * ((a - wall.start) dot d)
            b = wall.start + d * ((b - wall.start) dot d)
        }
        val base = if (type == OpeningType.DOOR) 0.0 else seg.bottom.coerceAtLeast(0.0)
        return Opening(type, a, b, base, seg.top - base, seg.confidence)
    }

    private fun distanceToLine(p: Vec2, a: Vec2, b: Vec2): Double {
        val d = (b - a).normalized()
        return abs((p - a) cross d)
    }

    private fun area(p: PlaneObservation) = abs(Geometry.signedArea(p.boundary.map { it.toPlan() }))
}
