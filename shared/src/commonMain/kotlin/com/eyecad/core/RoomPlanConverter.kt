package com.eyecad.core

/**
 * Collects what Apple's RoomPlan returns on LiDAR devices and converts it into a [FloorPlan].
 *
 * RoomPlan's CapturedRoom is Swift-only, so the iOS app passes each surface and object here as plain
 * values (world-space center, the surface's local x axis, and its dimensions).
 */
class RoomPlanInput {
    internal class Surface(val kind: String, val center: Vec3, val xAxis: Vec3, val width: Double, val height: Double, val confidence: Double, val polygon: List<Vec3>)
    internal class Obj(val category: String, val center: Vec3, val xAxis: Vec3, val width: Double, val height: Double, val depth: Double, val confidence: Double)

    internal val surfaces = mutableListOf<Surface>()
    internal val objects = mutableListOf<Obj>()

    /** [kind] is "wall", "door", "window", "opening" or "floor". [polygon] is world-space corners, may be empty. */
    fun addSurface(kind: String, center: Vec3, xAxis: Vec3, width: Double, height: Double, confidence: Double, polygon: List<Vec3>) {
        surfaces.add(Surface(kind, center, xAxis, width, height, confidence, polygon))
    }

    fun addObject(category: String, center: Vec3, xAxis: Vec3, width: Double, height: Double, depth: Double, confidence: Double) {
        objects.add(Obj(category, center, xAxis, width, height, depth, confidence))
    }
}

object RoomPlanConverter {
    /** RoomPlan's confidence levels as numbers. */
    fun confidenceValue(level: String): Double = when (level.lowercase()) {
        "high" -> 0.9
        "medium" -> 0.6
        else -> 0.3
    }

    /** RoomPlan category names mapped onto Eyecad's, so both capture modes use the same vocabulary. */
    fun category(roomPlanName: String): String = when (roomPlanName) {
        "washerDryer" -> "washer_dryer"
        else -> roomPlanName.lowercase()
    }

    fun convert(input: RoomPlanInput): FloorPlan {
        val floors = input.surfaces.filter { it.kind == "floor" }
        val walls = input.surfaces.filter { it.kind == "wall" }
        val floorY = floors.minOfOrNull { it.center.y }
            ?: walls.minOfOrNull { it.center.y - it.height / 2 }
            ?: 0.0

        fun ends(s: RoomPlanInput.Surface): Pair<Vec2, Vec2> {
            val c = s.center.toPlan()
            val half = s.xAxis.toPlan().normalized() * (s.width / 2)
            return (c - half) to (c + half)
        }

        val planWalls = walls.map { s ->
            val (a, b) = ends(s)
            Wall(a, b, height = s.height, thickness = 0.12, confidence = s.confidence)
        }
        val openings = input.surfaces.filter { it.kind == "door" || it.kind == "window" || it.kind == "opening" }.map { s ->
            val (a, b) = ends(s)
            val type = when (s.kind) {
                "door" -> OpeningType.DOOR
                "window" -> OpeningType.WINDOW
                else -> OpeningType.OPENING
            }
            Opening(type, a, b, baseHeight = (s.center.y - s.height / 2 - floorY).coerceAtLeast(0.0), height = s.height, confidence = s.confidence)
        }
        val objects = input.objects.map { o ->
            RoomObject(
                category = category(o.category),
                center = o.center.toPlan(),
                width = o.width,
                depth = o.depth,
                height = o.height,
                yaw = o.xAxis.toPlan().angle,
                baseHeight = (o.center.y - o.height / 2 - floorY).coerceAtLeast(0.0),
                confidence = o.confidence,
                observations = 1,
            )
        }

        val floorPolygon = floors.maxByOrNull { it.polygon.size }?.polygon?.map { it.toPlan() }?.takeIf { it.size >= 3 }
        val outline = if (floorPolygon != null) {
            if (Geometry.signedArea(floorPolygon) < 0) floorPolygon.reversed() else floorPolygon
        } else {
            Geometry.convexHull(planWalls.flatMap { listOf(it.start, it.end) })
        }
        val ceiling = walls.maxOfOrNull { it.height } ?: FloorPlanBuilder.DEFAULT_CEILING
        return FloorPlan(planWalls, openings, objects, outline, ceiling, heightEstimated = walls.isEmpty())
    }
}
