package com.eyecad.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.eyecad.core.ScanExport
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var finishButton: Button
    private lateinit var renderer: ScanRenderer
    private lateinit var orientation: OrientationProvider
    private var session: Session? = null
    private var installRequested = false
    private var lastExport: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientation = OrientationProvider(this)
        renderer = ScanRenderer(this, orientation) { stats, message ->
            runOnUiThread {
                status.text = if (stats == null) message else
                    "$message\nWalls: ${stats.walls}   Objects: ${stats.objects}   Surfaces: ${stats.planes}"
            }
        }

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(32, 96, 32, 24)
            textSize = 15f
            text = "Starting camera…"
        }
        startButton = Button(this).apply { text = "Start"; setOnClickListener { startScan() } }
        finishButton = Button(this).apply { text = "Finish & share"; isEnabled = false; setOnClickListener { finishScan() } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 72)
            addView(startButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(finishButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(buttons, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })

        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Eyecad needs the camera to scan."
            return
        }
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                session = Session(this).also { s ->
                    s.configure(Config(s).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                    })
                }
            } catch (e: UnavailableException) {
                status.text = "ARCore is not available on this device: ${e.javaClass.simpleName}"
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            status.text = "Camera not available. Close other camera apps and try again."
            session = null
            return
        }
        renderer.session = session
        glView.onResume()
        orientation.start(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    override fun onPause() {
        super.onPause()
        orientation.stop()
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.release()
        session?.close()
        session = null
    }

    private fun startScan() {
        glView.queueEvent {
            renderer.reset()
            renderer.scanning = true
        }
        startButton.isEnabled = false
        finishButton.isEnabled = true
    }

    private fun finishScan() {
        finishButton.isEnabled = false
        val loc = orientation.location
        glView.queueEvent {
            renderer.scanning = false
            val export = renderer.scan.export(
                "${Build.MANUFACTURER} ${Build.MODEL}",
                Instant.now().toString(),
                loc?.latitude, loc?.longitude, loc?.takeIf { it.hasAltitude() }?.altitude,
                null,
            )
            runOnUiThread { save(export) }
        }
    }

    private fun save(export: ScanExport) {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "scans").apply { mkdirs() }
        val name = "scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val geo = File(dir, "$name.geojson").apply { writeText(export.geoJson) }
        val obj = File(dir, "$name.obj").apply { writeText(export.obj) }
        lastExport = listOf(geo, obj)
        startButton.isEnabled = true
        startButton.text = "New scan"
        status.text = "Saved ${export.plan.walls.size} walls and ${export.plan.objects.size} objects to ${dir.absolutePath}"
        share(lastExport)
    }

    private fun share(files: List<File>) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Send scan"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app to share with; files are in ${files.first().parent}", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
