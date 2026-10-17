package com.eyecad.core

/** Object categories shared by both platforms, so a "sofa" means the same thing in every export. */
object Categories {
    /** Detector labels (COCO, as used by EfficientDet and YOLO) mapped to Eyecad categories. Others are ignored. */
    private val cocoToEyecad = mapOf(
        "chair" to "chair",
        "couch" to "sofa",
        "sofa" to "sofa",
        "bed" to "bed",
        "dining table" to "table",
        "diningtable" to "table",
        "toilet" to "toilet",
        "tv" to "television",
        "tvmonitor" to "television",
        "laptop" to "laptop",
        "microwave" to "microwave",
        "oven" to "oven",
        "sink" to "sink",
        "refrigerator" to "refrigerator",
        "potted plant" to "plant",
        "pottedplant" to "plant",
        "bench" to "bench",
        "bicycle" to "bicycle",
        "suitcase" to "suitcase",
        "clock" to "clock",
        "vase" to "vase",
    )

    /** Typical front-to-back depth in meters, since a single camera view can't measure it. */
    private val defaultDepth = mapOf(
        "chair" to 0.5, "sofa" to 0.9, "bed" to 2.0, "table" to 0.8, "toilet" to 0.7,
        "television" to 0.1, "laptop" to 0.25, "microwave" to 0.4, "oven" to 0.6, "sink" to 0.5,
        "refrigerator" to 0.7, "plant" to 0.4, "bench" to 0.45, "bicycle" to 0.5, "suitcase" to 0.3,
        "clock" to 0.05, "vase" to 0.15,
    )

    /** Categories that normally stand on the floor, so their base can be found by intersecting the floor. */
    private val floorStanding = setOf(
        "chair", "sofa", "bed", "table", "toilet", "oven", "refrigerator", "plant", "bench", "bicycle", "suitcase",
    )

    fun fromDetectorLabel(label: String): String? = cocoToEyecad[label.trim().lowercase()]

    fun depthFor(category: String, width: Double): Double =
        defaultDepth[category] ?: (width * 0.6).coerceIn(0.1, 1.5)

    fun standsOnFloor(category: String) = category in floorStanding
}
