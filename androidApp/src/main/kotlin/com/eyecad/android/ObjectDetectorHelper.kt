package com.eyecad.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import com.eyecad.core.Detection2D
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Runs MediaPipe's EfficientDet-Lite0 (COCO classes) on ARCore CPU images. */
class ObjectDetectorHelper(context: Context) {
    private val detector: ObjectDetector? = try {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                .setScoreThreshold(0.4f)
                .build(),
        )
    } catch (e: Exception) {
        Log.w(TAG, "Object detection disabled: ${e.message}")
        null
    }

    val available get() = detector != null

    /**
     * Detects objects in a sensor-oriented [bitmap]. The image is first rotated by [rotationDeg] so the
     * detector sees it upright; boxes are mapped back to sensor pixels.
     */
    fun detect(bitmap: Bitmap, rotationDeg: Int): List<Detection2D> {
        val d = detector ?: return emptyList()
        val upright = if (rotationDeg % 360 == 0) bitmap else
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotationDeg.toFloat()) }, true)
        val result = d.detect(BitmapImageBuilder(upright).build())
        val w = upright.width.toDouble()
        val h = upright.height.toDouble()
        return result.detections().mapNotNull { det ->
            val cat = det.categories().firstOrNull() ?: return@mapNotNull null
            val box = det.boundingBox()
            Detection2D.fromUprightBox(
                cat.categoryName(), cat.score().toDouble(),
                box.left / w, box.top / h, box.right / w, box.bottom / h,
                rotationDeg, bitmap.width.toDouble(), bitmap.height.toDouble(),
            )
        }
    }

    fun close() = detector?.close()

    companion object {
        private const val TAG = "Eyecad"
        private const val MODEL = "efficientdet_lite0.tflite"

        /** Converts a YUV_420_888 camera image to an ARGB bitmap. */
        fun yuvToBitmap(image: Image): Bitmap {
            val w = image.width
            val h = image.height
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            val yBuf = y.buffer
            val uBuf = u.buffer
            val vBuf = v.buffer
            val argb = IntArray(w * h)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val yy = (yBuf.get(row * y.rowStride + col * y.pixelStride).toInt() and 0xff)
                    val uvIndex = (row / 2) * u.rowStride + (col / 2) * u.pixelStride
                    val uu = (uBuf.get(uvIndex).toInt() and 0xff) - 128
                    val vv = (vBuf.get((row / 2) * v.rowStride + (col / 2) * v.pixelStride).toInt() and 0xff) - 128
                    val r = (yy + 1.370705f * vv).toInt().coerceIn(0, 255)
                    val g = (yy - 0.337633f * uu - 0.698001f * vv).toInt().coerceIn(0, 255)
                    val b = (yy + 1.732446f * uu).toInt().coerceIn(0, 255)
                    argb[row * w + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
