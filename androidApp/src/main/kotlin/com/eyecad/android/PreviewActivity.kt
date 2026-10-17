package com.eyecad.android

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.eyecad.core.PreviewFrame
import com.eyecad.core.PreviewModel
import com.eyecad.core.PreviewRenderer
import java.io.File

/** Opens a scan (the one just made, or any .geojson/.obj file) and shows it as a 2D plan or an orbitable 3D view. */
class PreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = try {
            load()
        } catch (e: Exception) {
            setContentView(TextView(this).apply {
                text = "Couldn't open this file: ${e.message}"
                setPadding(48, 160, 48, 48)
            })
            return
        }

        val canvas = PreviewView(this, model)
        val title = TextView(this).apply {
            text = "${model.title}\n${model.summary}"
            setTextColor(Color.WHITE)
            setPadding(32, 96, 32, 16)
            textSize = 14f
        }
        val toggle = Button(this).apply {
            text = "Show 2D plan"
            setOnClickListener {
                canvas.threeD = !canvas.threeD
                text = if (canvas.threeD) "Show 2D plan" else "Show 3D view"
            }
        }
        val hint = TextView(this).apply {
            text = "Drag to turn · pinch to zoom"
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 28, 36))
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(canvas, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(24, 8, 24, 72) })
        })
    }

    private fun load(): PreviewModel {
        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            val f = File(path)
            return PreviewModel.fromFile(f.name, f.readText())
        }
        val uri = intent.data ?: error("no file")
        val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        return PreviewModel.fromFile(displayName(uri), text)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "scan"

    companion object {
        const val EXTRA_PATH = "path"
    }
}

private class PreviewView(context: Context, private val model: PreviewModel) : View(context) {
    var threeD = true
        set(value) { field = value; invalidate() }
    private var yaw = 30.0
    private var pitch = 40.0
    private var zoom = 1.0
    private var lastX = 0f
    private var lastY = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(140, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.3, 8.0)
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        if (model.isEmpty) {
            canvas.drawText("Nothing to show in this file", width / 2f, height / 2f, text)
            return
        }
        val w = width.toDouble()
        val h = height.toDouble()
        val frame: PreviewFrame = if (threeD) PreviewRenderer.perspective(model, w, h, yaw, pitch, zoom)
        else PreviewRenderer.topDown(model, w, h, zoom)
        for (p in frame.polygons) {
            if (p.points.size < 3) continue
            val path = Path().apply {
                moveTo(p.points[0].x.toFloat(), p.points[0].y.toFloat())
                for (i in 1 until p.points.size) lineTo(p.points[i].x.toFloat(), p.points[i].y.toFloat())
                close()
            }
            fill.color = shaded(colorFor(p.kind), p.shade)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
        for (l in frame.labels) canvas.drawText(l.text, l.at.x.toFloat(), l.at.y.toFloat(), text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress && threeD) {
                yaw += (e.x - lastX) * 0.4
                pitch = (pitch + (e.y - lastY) * 0.3).coerceIn(5.0, 90.0)
                lastX = e.x; lastY = e.y
                invalidate()
            }
        }
        return true
    }

    private fun colorFor(kind: String) = when (kind) {
        "room" -> Color.rgb(226, 232, 240)
        "wall" -> Color.rgb(113, 128, 150)
        "door" -> Color.rgb(221, 107, 32)
        "window" -> Color.rgb(66, 153, 225)
        "opening" -> Color.rgb(160, 174, 192)
        else -> Color.rgb(72, 187, 120)
    }

    private fun shaded(c: Int, s: Double): Int {
        val k = s.coerceIn(0.0, 1.0)
        return Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
    }
}
