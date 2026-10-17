package com.eyecad.core

/**
 * Writes the floor plan as an RFC 7946 GeoJSON FeatureCollection.
 *
 * Every feature is a footprint polygon whose positions carry a third value, the base elevation above
 * the floor, plus `base_height` and `height` properties (top elevation, meters above floor). That is the
 * convention MapLibre/Mapbox `fill-extrusion`, deck.gl and QGIS 3D use, so the same file renders as a 2D
 * plan or extruded into 3D.
 *
 * With a [Georef] positions are WGS84 longitude/latitude. Without one they are local meters and the
 * top-level `eyecad.crs` says so.
 */
object GeoJsonWriter {
    fun write(plan: FloorPlan, meta: ScanMetadata): String {
        val geo = meta.georef
        fun pos(p: Vec2, z: Double): String =
            if (geo != null) {
                val ll = geo.toLonLat(p)
                "[${Format.num(ll.x, 9)},${Format.num(ll.y, 9)},${Format.num(z, 3)}]"
            } else {
                "[${Format.num(p.x, 3)},${Format.num(p.y, 3)},${Format.num(z, 3)}]"
            }

        fun polygon(ring: List<Vec2>, z: Double): String {
            val ccw = if (Geometry.signedArea(ring) < 0) ring.reversed() else ring
            val closed = ccw + ccw.first()
            return """{"type":"Polygon","coordinates":[[${closed.joinToString(",") { pos(it, z) }}]]}"""
        }

        val features = mutableListOf<String>()
        fun feature(id: String, geometry: String, props: List<Pair<String, Any>>) {
            val p = props.joinToString(",") { (k, v) ->
                val value = when (v) {
                    is Double -> Format.num(v, 3)
                    is Int -> v.toString()
                    is Boolean -> v.toString()
                    else -> Format.jsonString(v.toString())
                }
                "${Format.jsonString(k)}:$value"
            }
            features.add("""{"type":"Feature","id":${Format.jsonString(id)},"geometry":$geometry,"properties":{$p}}""")
        }

        if (plan.floor.size >= 3) {
            feature(
                "room-0", polygon(plan.floor, 0.0),
                listOf(
                    "kind" to "room", "base_height" to 0.0, "height" to 0.0,
                    "area_m2" to kotlin.math.abs(Geometry.signedArea(plan.floor)),
                    "ceiling_height_m" to plan.ceilingHeight, "ceiling_estimated" to plan.heightEstimated,
                ),
            )
        }
        plan.walls.forEachIndexed { i, w ->
            feature(
                "wall-$i", polygon(Geometry.thicken(w.start, w.end, w.thickness), 0.0),
                listOf(
                    "kind" to "wall", "base_height" to 0.0, "height" to w.height,
                    "length_m" to w.length, "thickness_m" to w.thickness, "confidence" to w.confidence,
                ),
            )
        }
        plan.openings.forEachIndexed { i, o ->
            feature(
                "${o.type.id}-$i", polygon(Geometry.thicken(o.start, o.end, 0.16), o.baseHeight),
                listOf(
                    "kind" to o.type.id, "base_height" to o.baseHeight, "height" to o.baseHeight + o.height,
                    "width_m" to o.start.distanceTo(o.end), "height_m" to o.height, "confidence" to o.confidence,
                ),
            )
        }
        plan.objects.forEachIndexed { i, o ->
            feature(
                "object-$i", polygon(Geometry.rectangle(o.center, o.width, o.depth, o.yaw), o.baseHeight),
                listOf(
                    "kind" to "object", "category" to o.category,
                    "base_height" to o.baseHeight, "height" to o.baseHeight + o.height,
                    "width_m" to o.width, "depth_m" to o.depth, "height_m" to o.height,
                    "confidence" to o.confidence, "observations" to o.observations,
                ),
            )
        }

        val header = buildList {
            add(""""version":${Format.jsonString(meta.appVersion)}""")
            add(""""capture_mode":${Format.jsonString(meta.mode.id)}""")
            add(""""device":${Format.jsonString(meta.device)}""")
            add(""""created":${Format.jsonString(meta.createdAt)}""")
            add(""""units":"meters"""")
            add(""""elevation_reference":"floor"""")
            if (geo != null) {
                add(""""crs":"EPSG:4326"""")
                add(""""origin":{"latitude":${Format.num(geo.latitude, 9)},"longitude":${Format.num(geo.longitude, 9)}${geo.altitude?.let { ",\"altitude\":${Format.num(it, 2)}" } ?: ""}}""")
                add(""""rotation_deg":${Format.num(geo.rotationDeg, 2)}""")
            } else {
                add(""""crs":"local"""")
            }
            add(""""counts":{"walls":${plan.walls.size},"openings":${plan.openings.size},"objects":${plan.objects.size}}""")
        }
        return buildString {
            append("""{"type":"FeatureCollection","eyecad":{${header.joinToString(",")}},"features":[""")
            append('\n')
            append(features.joinToString(",\n"))
            append("\n]}\n")
        }
    }
}
