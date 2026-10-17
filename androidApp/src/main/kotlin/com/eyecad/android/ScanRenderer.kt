package com.eyecad.android

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.eyecad.core.CameraScanSession
import com.eyecad.core.CaptureMode
import com.eyecad.core.PlaneKind
import com.eyecad.core.PlaneObservation
import com.eyecad.core.ScanStats
import com.eyecad.core.Vec3
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Runs on the GL thread: draws the camera, forwards ARCore planes and feature points to the shared
 * [CameraScanSession], and samples the compass. Everything else (walls, objects, export) lives in :shared.
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
    private val planeIds = HashMap<Plane, String>()
    private var nextPlaneId = 0
    private var textureSet = false
    private var lastPlaneUpdate = 0L
    private var lastStats = 0L

    fun reset() {
        scan = CameraScanSession(CaptureMode.CAMERA_ARCORE)
        planeIds.clear()
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

        if (now - lastPlaneUpdate > 300) {
            lastPlaneUpdate = now
            updatePlanes(session)
            frame.acquirePointCloud().use { cloud ->
                val pts = cloud.points
                val ids = cloud.ids
                while (pts.remaining() >= 4 && ids.hasRemaining()) {
                    val x = pts.get(); val y = pts.get(); val z = pts.get(); val confidence = pts.get()
                    val id = ids.get()
                    if (confidence >= 0.3f) scan.addPoint(id.toLong(), x.toDouble(), y.toDouble(), z.toDouble())
                }
            }
            scan.updateCamera(Vec3(camera.pose.tx().toDouble(), camera.pose.ty().toDouble(), camera.pose.tz().toDouble()))
            orientation.cameraTrueBearing()?.let { scan.addHeadingSample(forward(camera.pose), it) }
        }
        if (now - lastStats > 1000) {
            lastStats = now
            onStats(scan.stats(), "Scanning")
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

    private companion object {
        const val TAG = "Eyecad"

        fun forward(p: Pose): Vec3 = p.zAxis.let { Vec3(-it[0].toDouble(), -it[1].toDouble(), -it[2].toDouble()) }
    }
}
