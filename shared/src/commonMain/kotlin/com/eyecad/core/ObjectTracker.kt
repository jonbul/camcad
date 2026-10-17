package com.eyecad.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Merges repeated sightings of the same object across frames into one [RoomObject], weighting each
 * sighting by detector confidence. Objects seen fewer than [minObservations] times are dropped as noise.
 */
class ObjectTracker(private val minObservations: Int = 2) {
    private class Track(var d: Detection3D) {
        var weight = d.confidence
        var count = 1
        // Yaw is averaged on doubled angles because a box rotated by 180° is the same box.
        var yawSin = sin(2 * d.yaw) * d.confidence
        var yawCos = cos(2 * d.yaw) * d.confidence
        var bestConfidence = d.confidence
    }

    private val tracks = mutableListOf<Track>()

    fun add(d: Detection3D) {
        val match = tracks
            .filter { it.d.category == d.category }
            .minByOrNull { it.d.center.distanceTo(d.center) }
            ?.takeIf { it.d.center.distanceTo(d.center) < max(0.4, 0.5 * max(it.d.width, d.width)) }
        if (match == null) {
            tracks.add(Track(d))
            return
        }
        val w0 = match.weight
        val w1 = d.confidence
        fun avg(a: Double, b: Double) = (a * w0 + b * w1) / (w0 + w1)
        match.yawSin += sin(2 * d.yaw) * w1
        match.yawCos += cos(2 * d.yaw) * w1
        match.d = Detection3D(
            category = d.category,
            center = Vec2(avg(match.d.center.x, d.center.x), avg(match.d.center.y, d.center.y)),
            width = avg(match.d.width, d.width),
            depth = avg(match.d.depth, d.depth),
            height = avg(match.d.height, d.height),
            yaw = atan2(match.yawSin, match.yawCos) / 2,
            baseHeight = avg(match.d.baseHeight, d.baseHeight),
            confidence = avg(match.d.confidence, d.confidence),
        )
        match.weight = w0 + w1
        match.count++
        match.bestConfidence = max(match.bestConfidence, d.confidence)
    }

    fun objects(): List<RoomObject> = tracks
        .filter { it.count >= minObservations }
        .map {
            RoomObject(
                category = it.d.category,
                center = it.d.center,
                width = it.d.width,
                depth = it.d.depth,
                height = it.d.height,
                yaw = it.d.yaw,
                baseHeight = it.d.baseHeight,
                confidence = it.bestConfidence,
                observations = it.count,
            )
        }

    fun clear() = tracks.clear()
}
