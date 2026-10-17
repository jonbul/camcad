package com.eyecad.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
    private lateinit var scanButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var previewButton: Button
    private lateinit var saveLocation: SaveLocation
    private lateinit var renderer: ScanRenderer
    private lateinit var orientation: OrientationProvider
    private var session: Session? = null
    private var installRequested = false
    private var scanning = false
    /** Files of the last finished scan; Save, Share and Preview stay available until a new scan starts. */
    private var lastFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientation = OrientationProvider(this)
        saveLocation = SaveLocation(this)
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
        scanButton = Button(this).apply { text = "Start scan"; setOnClickListener { if (scanning) finishScan() else startScan() } }
        saveButton = Button(this).apply { text = "Save"; setOnClickListener { askWhereToSave() } }
        shareButton = Button(this).apply { text = "Share"; setOnClickListener { share(lastFiles) } }
        previewButton = Button(this).apply { text = "Preview"; setOnClickListener { previewLast() } }
        val openButton = Button(this).apply { text = "Open…"; setOnClickListener { openFile() } }
        updateButtons()
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 72)
            addView(row(scanButton), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(row(saveButton, shareButton, previewButton, openButton), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

    private fun updateButtons() {
        scanButton.text = when {
            scanning -> "Finish"
            lastFiles.isNotEmpty() -> "New scan"
            else -> "Start scan"
        }
        val hasScan = !scanning && lastFiles.isNotEmpty()
        saveButton.isEnabled = hasScan
        shareButton.isEnabled = hasScan
        previewButton.isEnabled = hasScan
    }

    private fun startScan() {
        glView.queueEvent {
            renderer.reset()
            renderer.scanning = true
        }
        scanning = true
        lastFiles = emptyList()
        updateButtons()
    }

    private fun finishScan() {
        scanButton.isEnabled = false
        val loc = orientation.location
        glView.queueEvent {
            renderer.scanning = false
            val export = renderer.scan.export(
                "${Build.MANUFACTURER} ${Build.MODEL}",
                Instant.now().toString(),
                loc?.latitude, loc?.longitude, loc?.takeIf { it.hasAltitude() }?.altitude,
                null,
            )
            runOnUiThread { finished(export) }
        }
    }

    private fun finished(export: ScanExport) {
        // Kept in the cache until the next scan, so it can be saved, shared or previewed any number of times.
        val dir = File(cacheDir, "scans").apply { deleteRecursively(); mkdirs() }
        val name = "scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        lastFiles = listOf(
            File(dir, "$name.geojson").apply { writeText(export.geoJson) },
            File(dir, "$name.obj").apply { writeText(export.obj) },
        )
        scanning = false
        scanButton.isEnabled = true
        updateButtons()
        status.text = "Mapped ${export.plan.walls.size} walls and ${export.plan.objects.size} objects."
        askWhereToSave()
    }

    private fun askWhereToSave() {
        AlertDialog.Builder(this)
            .setTitle("Save scan")
            .setMessage("Save ${lastFiles.joinToString(" and ") { it.name }} to ${saveLocation.label}?")
            .setPositiveButton("Save here") { _, _ -> saveToDefault() }
            .setNeutralButton("Choose folder…") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun saveToDefault() {
        try {
            val where = saveLocation.save(lastFiles)
            status.text = "Saved to $where"
            Toast.makeText(this, "Saved to $where", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // A folder that disappeared or lost its permission falls back to the built-in default.
            saveLocation.resetToDefault()
            Toast.makeText(this, "Couldn't save: ${e.message}. Default folder reset to ${saveLocation.label}.", Toast.LENGTH_LONG).show()
        }
    }

    private fun previewLast() {
        val geo = lastFiles.firstOrNull { it.name.endsWith(".geojson") } ?: return
        startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_PATH, geo.absolutePath))
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    @Deprecated("Activity result API needs AndroidX activity; plain Activity uses this")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_FOLDER -> {
                saveLocation.rememberFolder(uri)
                saveToDefault()
                Toast.makeText(this, "${saveLocation.label} is now the default folder", Toast.LENGTH_SHORT).show()
            }
            REQUEST_OPEN -> startActivity(Intent(this, PreviewActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
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
        const val REQUEST_FOLDER = 2
        const val REQUEST_OPEN = 3
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
