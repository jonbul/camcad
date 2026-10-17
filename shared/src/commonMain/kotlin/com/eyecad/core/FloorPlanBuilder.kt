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
 * Builds a floor plan from the planes a camera-only AR session detects (ARCore, or ARKit without LiDAR).
 *
 * Vertical planes become wall segments, which are snapped to the room's dominant directions, merged
 * when they're pieces of the same wall, and joined at corners.
 */
object FloorPlanBuilder {
    const val DEFAULT_CEILING = 2.5
    private const val MIN_WALL_LENGTH = 0.3
    private const val SNAP_TOLERANCE = 20 * PI / 180
    private const val MERGE_OFFSET = 0.2
    private const val MERGE_GAP = 0.4
    private const val CORNER_REACH = 0.75

    fun floorHeight(planes: List<PlaneObservation>): Double? {
        val floors = planes.filter { it.kind == PlaneKind.FLOOR && it.boundary.size >= 3 }
        if (floors.isEmpty()) return null
        // The lowest plane that is reasonably big; small low patches are often noise.
        val big = floors.filter { area(it) >= 0.5 }.ifEmpty { floors }
        return big.minOf { p -> p.boundary.sumOf { it.y } / p.boundary.size }
    }

    fun build(planes: List<PlaneObservation>, objects: List<RoomObject>, fallbackFloorY: Double = 0.0): FloorPlan {
        val floorY = floorHeight(planes) ?: fallbackFloorY

        val ceilings = planes.filter { it.kind == PlaneKind.CEILING && it.boundary.isNotEmpty() }
            .map { p -> p.boundary.sumOf { it.y } / p.boundary.size - floorY }
            .filter { it in 1.8..5.0 }
        val ceiling = ceilings.maxOrNull()

        val segments = planes.filter { it.kind == PlaneKind.WALL }.mapNotNull { segmentOf(it, floorY) }
        val dominant = dominantAngle(segments)
        val snapped = segments.map { snap(it, dominant) }
        val merged = joinCorners(merge(snapped))
        val height = ceiling ?: DEFAULT_CEILING
        val walls = merged.filter { it.length >= MIN_WALL_LENGTH }.map {
            Wall(it.a, it.b, height = max(height, it.top), thickness = 0.1, confidence = it.confidence)
        }

        val openings = planes.filter { it.kind == PlaneKind.DOOR || it.kind == PlaneKind.WINDOW }
            .mapNotNull { p -> segmentOf(p, floorY)?.let { attachToWall(p, snap(it, dominant), walls) } }

        val floorPoints = planes
            .filter { it.kind == PlaneKind.FLOOR && abs(it.boundary.sumOf { b -> b.y } / it.boundary.size - floorY) < 0.15 }
            .flatMap { p -> p.boundary.map { it.toPlan() } } +
            walls.flatMap { listOf(it.start, it.end) }
        val floor = Geometry.convexHull(floorPoints)

        return FloorPlan(walls, openings, objects, floor, height, heightEstimated = ceiling == null)
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
        var s = 0.0
        var c = 0.0
        for (seg in segments) {
            val a = 4 * seg.dir.angle
            s += sin(a) * seg.length
            c += cos(a) * seg.length
        }
        return atan2(s, c) / 4
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
