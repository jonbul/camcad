package com.eyecad.android

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.eyecad.core.CameraFrame
import com.eyecad.core.CameraScanSession
import com.eyecad.core.CaptureMode
import com.eyecad.core.Detection2D
import com.eyecad.core.PlaneKind
import com.eyecad.core.PlaneObservation
import com.eyecad.core.Pose as CorePose
import com.eyecad.core.ScanStats
import com.eyecad.core.Vec3
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Runs on the GL thread: draws the camera, forwards ARCore planes and detections to the shared
 * [CameraScanSession], and samples the compass. Everything else (geometry, export) lives in :shared.
 */
class ScanRenderer(
    private val context: Context,
    private val orientation: OrientationProvider,
    private val onStats: (ScanStats?, String) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var scanning = false
    var scan = CameraScanSession(CaptureMode.CAMERA_ARCORE)
        private set

    private val background = BackgroundRenderer()
    private val detector by lazy { ObjectDetectorHelper(context) }
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private val detectorBusy = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<Pair<CameraFrame, List<Detection2D>>>()
    private val planeIds = HashMap<Plane, String>()
    private var nextPlaneId = 0
    private var textureSet = false
    private var lastPlaneUpdate = 0L
    private var lastDetection = 0L
    private var lastStats = 0L
    private var sensorRotation: Int? = null

    fun reset() {
        scan = CameraScanSession(CaptureMode.CAMERA_ARCORE)
        planeIds.clear()
        pending.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (!textureSet) {
            session.setCameraTextureName(background.textureId)
            textureSet = true
        }
        val frame = try { session.update() } catch (e: Exception) { Log.w(TAG, "update failed", e); return }
        background.draw(frame)

        val camera = frame.camera
        val now = SystemClock.elapsedRealtime()
        if (camera.trackingState != TrackingState.TRACKING) {
            if (now - lastStats > 500) {
                lastStats = now
                onStats(null, "Move the phone slowly to start tracking…")
            }
            return
        }
        if (!scanning) {
            if (now - lastStats > 500) {
                lastStats = now
                onStats(null, "Ready. Tap Start and walk around the room.")
            }
            return
        }

        while (true) {
            val (cameraFrame, detections) = pending.poll() ?: break
            scan.addDetections(cameraFrame, detections) { o, d -> hitTest(frame, o, d) }
        }
        if (now - lastPlaneUpdate > 400) {
            lastPlaneUpdate = now
            updatePlanes(session)
            orientation.cameraTrueBearing()?.let { scan.addHeadingSample(forward(camera.pose), it) }
        }
        if (now - lastDetection > 600 && detector.available && detectorBusy.compareAndSet(false, true)) {
            lastDetection = now
            submitDetection(session, frame)
        }
        if (now - lastStats > 500) {
            lastStats = now
            onStats(scan.stats(), if (detector.available) "Scanning" else "Scanning (object detection unavailable)")
        }
    }

    private fun updatePlanes(session: Session) {
        for (plane in session.getAllTrackables(Plane::class.java)) {
            val id = planeIds.getOrPut(plane) { "p${nextPlaneId++}" }
            if (plane.subsumedBy != null || plane.trackingState == TrackingState.STOPPED) {
                scan.removePlane(id)
                continue
            }
            if (plane.trackingState != TrackingState.TRACKING) continue
            val kind = when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> PlaneKind.FLOOR
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneKind.CEILING
                Plane.Type.VERTICAL -> PlaneKind.WALL
                else -> PlaneKind.OTHER
            }
            val center = plane.centerPose
            val poly = plane.polygon.duplicate().apply { rewind() }
            val boundary = ArrayList<Vec3>(poly.remaining() / 2)
            while (poly.remaining() >= 2) {
                val p = center.transformPoint(floatArrayOf(poly.get(), 0f, poly.get()))
                boundary.add(Vec3(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()))
            }
            val n = center.yAxis
            scan.upsertPlane(PlaneObservation(id, kind, Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble()), boundary))
        }
    }

    private fun submitDetection(session: Session, frame: Frame) {
        val image = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { detectorBusy.set(false); return }
        val camera = frame.camera
        val intr = camera.imageIntrinsics
        val focal = intr.focalLength
        val principal = intr.principalPoint
        val dims = intr.imageDimensions
        val cameraFrame = CameraFrame(
            toCorePose(camera.pose),
            focal[0].toDouble(), focal[1].toDouble(), principal[0].toDouble(), principal[1].toDouble(),
            dims[0].toDouble(), dims[1].toDouble(),
        )
        val bitmap = try { ObjectDetectorHelper.yuvToBitmap(image) } finally { image.close() }
        val rotation = sensorRotation ?: sensorOrientation(session).also { sensorRotation = it }
        detectorExecutor.execute {
            try {
                val detections = detector.detect(bitmap, rotation)
                if (detections.isNotEmpty()) pending.add(cameraFrame to detections)
            } catch (e: Exception) {
                Log.w(TAG, "detection failed", e)
            } finally {
                detectorBusy.set(false)
            }
        }
    }

    private fun hitTest(frame: Frame, o: Vec3, d: Vec3): Vec3? {
        val hits = frame.hitTest(floatArrayOf(o.x.toFloat(), o.y.toFloat(), o.z.toFloat()), 0, floatArrayOf(d.x.toFloat(), d.y.toFloat(), d.z.toFloat()), 0)
        val hit = hits.firstOrNull {
            val t = it.trackable
            (t is Plane && t.isPoseInPolygon(it.hitPose)) || t is Point
        } ?: return null
        val p = hit.hitPose
        return Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
    }

    /** Degrees to rotate the CPU image clockwise so it's upright in portrait. */
    private fun sensorOrientation(session: Session): Int = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.getCameraCharacteristics(session.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Exception) {
        90
    }

    fun release() {
        detectorExecutor.shutdown()
        detector.close()
    }

    private companion object {
        const val TAG = "Eyecad"

        fun toCorePose(p: Pose): CorePose {
            fun v(a: FloatArray) = Vec3(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
            return CorePose(Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()), v(p.xAxis), v(p.yAxis), v(p.zAxis))
        }

        fun forward(p: Pose): Vec3 = p.zAxis.let { Vec3(-it[0].toDouble(), -it[1].toDouble(), -it[2].toDouble()) }
    }
}
