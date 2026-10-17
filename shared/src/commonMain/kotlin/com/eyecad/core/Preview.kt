package com.eyecad.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One element to preview: a footprint extruded from [base] to [top] meters above the floor. */
data class PreviewSolid(val kind: String, val label: String, val footprint: List<Vec2>, val base: Double, val top: Double)

/**
 * What the preview screen shows, built from a fresh scan or read back from a saved GeoJSON or OBJ file,
 * so both apps can check any export with the same drawing code.
 */
class PreviewModel(val solids: List<PreviewSolid>, val title: String) {
    val minX = solids.flatMap { it.footprint }.minOfOrNull { it.x } ?: 0.0
    val maxX = solids.flatMap { it.footprint }.maxOfOrNull { it.x } ?: 0.0
    val minY = solids.flatMap { it.footprint }.minOfOrNull { it.y } ?: 0.0
    val maxY = solids.flatMap { it.footprint }.maxOfOrNull { it.y } ?: 0.0
    val maxTop = solids.maxOfOrNull { it.top } ?: 0.0

    /** e.g. "4 walls · 2 doors/windows · 3 objects (2 chair, 1 sofa) · 20.0 m²". */
    val summary: String
        get() {
            val walls = solids.count { it.kind == "wall" }
            val openings = solids.count { it.kind == "door" || it.kind == "window" || it.kind == "opening" }
            val objects = solids.filter { it.kind == "object" }
            val byCategory = objects.groupingBy { it.label }.eachCount().entries
                .sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
            val area = solids.firstOrNull { it.kind == "room" }?.let { kotlin.math.abs(Geometry.signedArea(it.footprint)) }
            return buildList {
                add("$walls walls")
                add("$openings doors/windows")
                add(if (objects.isEmpty()) "0 objects" else "${objects.size} objects ($byCategory)")
                if (area != null) add("${Format.num(area, 1)} m²")
            }.joinToString(" · ")
        }

    val isEmpty get() = solids.isEmpty()

    companion object {
        fun fromPlan(plan: FloorPlan, title: String): PreviewModel {
            val solids = mutableListOf<PreviewSolid>()
            if (plan.floor.size >= 3) solids += PreviewSolid("room", "room", plan.floor, 0.0, 0.0)
            plan.walls.forEach { solids += PreviewSolid("wall", "wall", Geometry.thicken(it.start, it.end, it.thickness), 0.0, it.height) }
            plan.openings.forEach {
                solids += PreviewSolid(it.type.id, it.type.id, Geometry.thicken(it.start, it.end, 0.16), it.baseHeight, it.baseHeight + it.height)
            }
            plan.objects.forEach {
                solids += PreviewSolid("object", it.category, Geometry.rectangle(it.center, it.width, it.depth, it.yaw), it.baseHeight, it.baseHeight + it.height)
            }
            return PreviewModel(solids, title)
        }

        /** Picks the reader from the file name, falling back to sniffing the content. */
        @Throws(Exception::class)
        fun fromFile(name: String, text: String): PreviewModel =
            if (name.lowercase().endsWith(".obj") || !text.trimStart().startsWith("{")) fromObj(text, name) else fromGeoJson(text, name)

        /** Reads an Eyecad GeoJSON (or any GeoJSON with polygon footprints and base_height/height). */
        @Throws(Exception::class)
        fun fromGeoJson(text: String, title: String): PreviewModel {
            val root = Json.parseToJsonElement(text).jsonObject
            val meta = root["eyecad"] as? JsonObject
            val features = (root["features"] as? JsonArray) ?: throw IllegalArgumentException("Not a GeoJSON FeatureCollection")
            fun num(o: JsonObject?, key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
            fun str(o: JsonObject?, key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

            val local = str(meta, "crs") == "local"
            val origin = meta?.get("origin") as? JsonObject
            var georef: Georef? = if (!local && origin != null) {
                Georef(num(origin, "latitude") ?: 0.0, num(origin, "longitude") ?: 0.0, num(meta, "rotation_deg") ?: 0.0)
            } else null

            val solids = mutableListOf<PreviewSolid>()
            for (f in features) {
                val fo = f as? JsonObject ?: continue
                val geometry = fo["geometry"] as? JsonObject ?: continue
                if (str(geometry, "type") != "Polygon") continue
                val ring = (geometry["coordinates"] as? JsonArray)?.firstOrNull()?.jsonArray ?: continue
                val pts = ring.mapNotNull { pos ->
                    val a = pos.jsonArray
                    val x = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val y = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    if (local) return@mapNotNull Vec2(x, y)
                    // GeoJSON from elsewhere: centre on its first position.
                    val g = georef ?: Georef(y, x, 0.0).also { georef = it }
                    g.fromLonLat(x, y)
                }.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
                if (pts.size < 3) continue
                val props = fo["properties"] as? JsonObject
                val kind = str(props, "kind") ?: "object"
                val base = num(props, "base_height") ?: 0.0
                val top = num(props, "height") ?: base
                solids += PreviewSolid(kind, str(props, "category") ?: kind, pts, base, top)
            }
            return PreviewModel(solids, title)
        }

        /** Reads an OBJ: each named group becomes a solid from its vertices' footprint and height range. */
        @Throws(Exception::class)
        fun fromObj(text: String, title: String): PreviewModel {
            val vertices = mutableListOf<Vec3>()
            val groups = LinkedHashMap<String, MutableList<Int>>()
            var current = "model"
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("v ") -> {
                        val p = line.substring(2).trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                        if (p.size >= 3) {
                            vertices += Vec3(p[0], p[1], p[2])
                            groups.getOrPut(current) { mutableListOf() } += vertices.size - 1
                        }
                    }
                    line.startsWith("o ") || line.startsWith("g ") -> current = line.substring(2).trim()
                }
            }
            if (vertices.isEmpty()) throw IllegalArgumentException("No geometry in OBJ file")
            val floorY = groups["floor"]?.minOfOrNull { vertices[it].y } ?: vertices.minOf { it.y }
            val solids = groups.mapNotNull { (name, idx) ->
                val pts = idx.map { vertices[it] }
                val plan = pts.map { it.toPlan() }
                val kind = name.substringBefore('_').let { if (it == "floor") "room" else it }
                val label = if (kind == "object") name.split('_').drop(2).joinToString("_").ifEmpty { "object" } else kind
                // The floor ring is written in order; other groups are boxes, so their hull is the footprint.
                val footprint = if (kind == "room") plan.distinct() else Geometry.convexHull(plan)
                if (footprint.size < 3) return@mapNotNull null
                PreviewSolid(kind, label, footprint, pts.minOf { it.y } - floorY, pts.maxOf { it.y } - floorY)
            }
            return PreviewModel(solids, title)
        }
    }
}

/** A filled polygon in screen pixels (y down). [shade] is 0 (dark) to 1 (lit). */
data class DrawPolygon(val points: List<Vec2>, val kind: String, val shade: Double)

data class DrawLabel(val text: String, val at: Vec2)

class PreviewFrame(val polygons: List<DrawPolygon>, val labels: List<DrawLabel>)

/**
 * Turns a [PreviewModel] into polygons both apps draw with their native canvas: a top-down 2D plan,
 * or a shaded 3D view the user can orbit. Orthographic, back-to-front, no GPU needed.
 */
object PreviewRenderer {
    private val kindOrder = listOf("room", "wall", "opening", "door", "window", "object")

    fun topDown(model: PreviewModel, width: Double, height: Double, zoom: Double): PreviewFrame {
        val cx = (model.minX + model.maxX) / 2
        val cy = (model.minY + model.maxY) / 2
        val span = max(model.maxX - model.minX, model.maxY - model.minY).coerceAtLeast(1.0)
        val scale = min(width, height) * 0.9 / span * zoom
        fun screen(p: Vec2) = Vec2(width / 2 + (p.x - cx) * scale, height / 2 - (p.y - cy) * scale)
        val polys = model.solids
            .sortedBy { kindOrder.indexOf(it.kind).let { i -> if (i < 0) kindOrder.size else i } }
            .map { DrawPolygon(it.footprint.map(::screen), it.kind, 1.0) }
        return PreviewFrame(polys, labels(model) { s -> screen(centroid(s.footprint)) })
    }

    /** [yawDeg] turns the model around the vertical axis; [pitchDeg] is 0 (side view) to 90 (straight down). */
    fun perspective(model: PreviewModel, width: Double, height: Double, yawDeg: Double, pitchDeg: Double, zoom: Double): PreviewFrame {
        val cx = (model.minX + model.maxX) / 2
        val cy = (model.minY + model.maxY) / 2
        val yaw = yawDeg * PI / 180
        val pitch = pitchDeg.coerceIn(5.0, 90.0) * PI / 180
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)
        val dx = model.maxX - model.minX
        val dy = model.maxY - model.minY
        val radius = (sqrt(dx * dx + dy * dy + model.maxTop * model.maxTop) / 2).coerceAtLeast(0.5)
        val scale = min(width, height) * 0.48 / radius * zoom
        val zMid = model.maxTop / 2
        // Light fixed to the model so turning it changes the shading.
        val light = Vec3(-0.45, -0.6, 0.66).normalized()

        class Face(val pts: List<Vec2>, val depth: Double, val kind: String, val shade: Double)

        fun rotate(p: Vec2) = Vec2((p.x - cx) * cosY - (p.y - cy) * sinY, (p.x - cx) * sinY + (p.y - cy) * cosY)
        fun screen(r: Vec2, z: Double) = Vec2(width / 2 + r.x * scale, height / 2 - (r.y * sinP + (z - zMid) * cosP) * scale)
        fun depth(r: Vec2, z: Double) = r.y * cosP - z * sinP

        val faces = mutableListOf<Face>()
        for (s in model.solids) {
            val ring = if (Geometry.signedArea(s.footprint) < 0) s.footprint.reversed() else s.footprint
            val rot = ring.map(::rotate)
            val topShade = 0.45 + 0.55 * light.z
            faces += Face(rot.map { screen(it, s.top) }, rot.sumOf { depth(it, s.top) } / rot.size, s.kind, topShade)
            if (s.top - s.base < 1e-3) continue
            for (i in ring.indices) {
                val j = (i + 1) % ring.size
                val e = ring[j] - ring[i]
                val n = Vec2(e.y, -e.x).normalized()
                val nRot = Vec2(n.x * cosY - n.y * sinY, n.x * sinY + n.y * cosY)
                if (nRot.y * cosP >= 0) continue // faces away from the viewer
                val quad = listOf(screen(rot[i], s.base), screen(rot[j], s.base), screen(rot[j], s.top), screen(rot[i], s.top))
                val d = (depth(rot[i], (s.base + s.top) / 2) + depth(rot[j], (s.base + s.top) / 2)) / 2
                val shade = 0.45 + 0.55 * max(0.0, n.x * light.x + n.y * light.y)
                faces += Face(quad, d, s.kind, shade)
            }
        }
        // The floor is always behind everything standing on it.
        val polys = faces.sortedWith(compareBy<Face> { it.kind != "room" }.thenByDescending { it.depth })
            .map { DrawPolygon(it.pts, it.kind, it.shade) }
        return PreviewFrame(polys, labels(model) { s -> screen(rotate(centroid(s.footprint)), s.top) })
    }

    private fun labels(model: PreviewModel, at: (PreviewSolid) -> Vec2) =
        model.solids.filter { it.kind == "object" }.map { DrawLabel(it.label, at(it)) }

    private fun centroid(pts: List<Vec2>) = Vec2(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)
}
