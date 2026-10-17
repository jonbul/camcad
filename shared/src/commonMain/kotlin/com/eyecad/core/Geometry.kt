package com.eyecad.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point or vector on the floor plan, in meters. +y is "forward" (AR world -Z). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    val length get() = sqrt(x * x + y * y)
    fun normalized(): Vec2 = length.let { if (it < 1e-9) Vec2(0.0, 0.0) else Vec2(x / it, y / it) }
    /** Rotated 90° counter-clockwise. */
    fun perp() = Vec2(-y, x)
    fun rotated(rad: Double) = Vec2(x * cos(rad) - y * sin(rad), x * sin(rad) + y * cos(rad))
    fun distanceTo(o: Vec2) = (this - o).length
    val angle get() = atan2(y, x)

    companion object {
        fun fromAngle(rad: Double) = Vec2(cos(rad), sin(rad))
    }
}

/** A point in AR world space (ARKit / ARCore convention): meters, +Y up, camera looks along -Z. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 = length.let { if (it < 1e-9) Vec3(0.0, 0.0, 0.0) else Vec3(x / it, y / it, z / it) }

    /** Drops height and maps world (x, z) onto the plan, with plan +y = world -Z. */
    fun toPlan() = Vec2(x, -z)
}

/** Plan point plus height back into AR world space (used for the OBJ export). */
fun Vec2.toWorld(height: Double) = Vec3(x, height, -y)

/** Rigid camera pose: position plus rotation given as the world-space directions of the camera axes. */
data class Pose(val position: Vec3, val right: Vec3, val up: Vec3, val back: Vec3) {
    /** Rotates a camera-space direction into world space. */
    fun rotate(v: Vec3) = right * v.x + up * v.y + back * v.z
    val forward get() = back * -1.0

    companion object {
        /** From a column-major 4x4 matrix as used by ARKit simd_float4x4 and ARCore Pose.toMatrix. */
        fun fromColumnMajor(m: FloatArray): Pose {
            require(m.size == 16)
            fun col(c: Int) = Vec3(m[c * 4].toDouble(), m[c * 4 + 1].toDouble(), m[c * 4 + 2].toDouble())
            return Pose(col(3), col(0), col(1), col(2))
        }

        /** Builds a pose from a position and forward/up hints (used by tests and simple callers). */
        fun lookingAlong(position: Vec3, forward: Vec3, upHint: Vec3 = Vec3(0.0, 1.0, 0.0)): Pose {
            val back = (forward * -1.0).normalized()
            val right = (upHint cross back).normalized()
            val up = back cross right
            return Pose(position, right, up, back)
        }
    }
}

object Geometry {
    /** Wraps an angle to (-π, π]. */
    fun wrapPi(a: Double): Double {
        var r = a % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    /** Signed area, positive when counter-clockwise. */
    fun signedArea(poly: List<Vec2>): Double {
        var a = 0.0
        for (i in poly.indices) a += poly[i] cross poly[(i + 1) % poly.size]
        return a / 2
    }

    /** Andrew's monotone chain. Returns a counter-clockwise hull without the repeated first point. */
    fun convexHull(points: List<Vec2>): List<Vec2> {
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        fun turn(o: Vec2, a: Vec2, b: Vec2) = (a - o) cross (b - o)
        val lower = mutableListOf<Vec2>()
        for (p in pts) {
            while (lower.size >= 2 && turn(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<Vec2>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && turn(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** Intersection of the infinite lines a1-a2 and b1-b2, or null when (nearly) parallel. */
    fun lineIntersection(a1: Vec2, a2: Vec2, b1: Vec2, b2: Vec2): Vec2? {
        val r = a2 - a1
        val s = b2 - b1
        val d = r cross s
        if (abs(d) < 1e-9) return null
        val t = ((b1 - a1) cross s) / d
        return a1 + r * t
    }

    /** Ear-clipping triangulation of a simple polygon. Returns index triples into [poly]. */
    fun triangulate(poly: List<Vec2>): List<IntArray> {
        if (poly.size < 3) return emptyList()
        val idx = poly.indices.toMutableList()
        if (signedArea(poly) < 0) idx.reverse()
        val tris = mutableListOf<IntArray>()
        fun inside(p: Vec2, a: Vec2, b: Vec2, c: Vec2) =
            ((b - a) cross (p - a)) >= 0 && ((c - b) cross (p - b)) >= 0 && ((a - c) cross (p - c)) >= 0
        var guard = 0
        while (idx.size > 3 && guard++ < 10_000) {
            var clipped = false
            for (i in idx.indices) {
                val ia = idx[(i + idx.size - 1) % idx.size]
                val ib = idx[i]
                val ic = idx[(i + 1) % idx.size]
                val a = poly[ia]; val b = poly[ib]; val c = poly[ic]
                if (((b - a) cross (c - b)) <= 1e-12) continue // reflex or degenerate
                val blocked = idx.any { it != ia && it != ib && it != ic && inside(poly[it], a, b, c) }
                if (blocked) continue
                tris.add(intArrayOf(ia, ib, ic))
                idx.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break // not simple; fall back to a fan for the remainder
        }
        for (i in 1 until idx.size - 1) tris.add(intArrayOf(idx[0], idx[i], idx[i + 1]))
        return tris
    }

    /** Even-odd point-in-polygon test. */
    fun contains(poly: List<Vec2>, p: Vec2): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val len2 = ab dot ab
        if (len2 < 1e-12) return p.distanceTo(a)
        val t = (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    /** Corners of a rectangle centered at [c] whose width axis points along [yaw] (radians from plan +x). */
    fun rectangle(c: Vec2, width: Double, depth: Double, yaw: Double): List<Vec2> {
        val u = Vec2.fromAngle(yaw) * (width / 2)
        val v = Vec2.fromAngle(yaw).perp() * (depth / 2)
        return listOf(c - u - v, c + u - v, c + u + v, c - u + v)
    }

    /** A wall-like segment thickened into a rectangle. */
    fun thicken(a: Vec2, b: Vec2, thickness: Double): List<Vec2> {
        val dir = (b - a).normalized()
        return rectangle((a + b) * 0.5, a.distanceTo(b), thickness, dir.angle)
    }
}
