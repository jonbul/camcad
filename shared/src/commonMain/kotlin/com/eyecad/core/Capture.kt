package com.eyecad.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The raw AR data behind a camera scan (planes and feature points, in AR world meters, no location).
 * Saved next to the exports as `*-capture.json` so a scan can be rebuilt later with improved code.
 */
class Capture(
    val mode: CaptureMode,
    val planes: List<PlaneObservation>,
    val points: List<Vec3>,
    val fallbackFloorY: Double,
) {
    fun floorPlan(): FloorPlan = FloorPlanBuilder.build(planes, points, fallbackFloorY)

    fun write(): String = buildString {
        fun xyz(v: Vec3) = "${Format.num(v.x, 3)},${Format.num(v.y, 3)},${Format.num(v.z, 3)}"
        append("{\"eyecad_capture\":1,\"mode\":").append(Format.jsonString(mode.id))
        append(",\"fallback_floor_y\":").append(Format.num(fallbackFloorY, 3))
        append(",\"planes\":[")
        planes.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"id\":").append(Format.jsonString(p.id)).append(",\"kind\":\"").append(p.kind.name).append('"')
            append(",\"normal\":[").append(xyz(p.normal)).append("],\"boundary\":[")
            append(p.boundary.joinToString(",") { xyz(it) }).append("]}")
        }
        append("],\"points\":[")
        append(points.joinToString(",") { xyz(it) })
        append("]}")
    }

    companion object {
        fun isCapture(text: String) = text.trimStart().startsWith("{\"eyecad_capture\"")

        @Throws(Exception::class)
        fun parse(text: String): Capture {
            val root = Json.parseToJsonElement(text).jsonObject
            fun triples(a: JsonArray): List<Vec3> {
                val d = a.map { it.jsonPrimitive.double }
                return (0 until d.size / 3).map { Vec3(d[3 * it], d[3 * it + 1], d[3 * it + 2]) }
            }
            val modeId = root["mode"]?.jsonPrimitive?.content
            val planes = root["planes"]!!.jsonArray.map { e ->
                val o = e as JsonObject
                PlaneObservation(
                    o["id"]!!.jsonPrimitive.content,
                    PlaneKind.valueOf(o["kind"]!!.jsonPrimitive.content),
                    triples(o["normal"]!!.jsonArray).first(),
                    triples(o["boundary"]!!.jsonArray),
                )
            }
            return Capture(
                CaptureMode.entries.firstOrNull { it.id == modeId } ?: CaptureMode.CAMERA_ARCORE,
                planes,
                triples(root["points"]!!.jsonArray),
                root["fallback_floor_y"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
    }
}
