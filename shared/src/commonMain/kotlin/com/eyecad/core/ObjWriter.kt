package com.eyecad.core

/**
 * Writes the floor plan as a Wavefront OBJ: geometry only, no materials or colors.
 *
 * Coordinates are local meters in AR world convention (+Y up), so the model opens upright in Blender,
 * MeshLab and most viewers. Each wall, opening and object is its own named group, e.g. `object_3_sofa`.
 */
object ObjWriter {
    fun write(plan: FloorPlan, meta: ScanMetadata): String {
        val sb = StringBuilder()
        sb.append("# Eyecad scan ${meta.createdAt} (${meta.mode.id}, ${meta.device})\n")
        sb.append("# Units: meters, +Y up, floor at Y=0\n")
        var vertexCount = 0

        fun v(p: Vec3) {
            sb.append("v ${Format.num(p.x, 4)} ${Format.num(p.y, 4)} ${Format.num(p.z, 4)}\n")
            vertexCount++
        }

        fun box(name: String, footprint: List<Vec2>, base: Double, top: Double) {
            sb.append("o $name\n")
            val ccw = if (Geometry.signedArea(footprint) < 0) footprint.reversed() else footprint
            val first = vertexCount + 1
            ccw.forEach { v(it.toWorld(base)) }
            ccw.forEach { v(it.toWorld(top)) }
            val n = ccw.size
            // The plan seen from above matches the world seen from above, so a plan-CCW ring faces up (+Y).
            sb.append("f ${(n - 1 downTo 0).joinToString(" ") { (first + it).toString() }}\n") // bottom, facing down
            sb.append("f ${(0 until n).joinToString(" ") { (first + n + it).toString() }}\n") // top, facing up
            for (i in 0 until n) {
                val j = (i + 1) % n
                sb.append("f ${first + i} ${first + j} ${first + n + j} ${first + n + i}\n") // side, facing out
            }
        }

        if (plan.floor.size >= 3) {
            sb.append("o floor\n")
            val first = vertexCount + 1
            plan.floor.forEach { v(it.toWorld(0.0)) }
            for (t in Geometry.triangulate(plan.floor)) {
                sb.append("f ${first + t[0]} ${first + t[1]} ${first + t[2]}\n")
            }
        }
        plan.walls.forEachIndexed { i, w -> box("wall_$i", Geometry.thicken(w.start, w.end, w.thickness), 0.0, w.height) }
        plan.openings.forEachIndexed { i, o ->
            box("${o.type.id}_$i", Geometry.thicken(o.start, o.end, 0.16), o.baseHeight, o.baseHeight + o.height)
        }
        plan.objects.forEachIndexed { i, o ->
            box("object_${i}_${o.category}", Geometry.rectangle(o.center, o.width, o.depth, o.yaw), o.baseHeight, o.baseHeight + o.height)
        }
        return sb.toString()
    }
}
