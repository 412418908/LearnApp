package com.example.learnapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

/** 题库里的图片点击后 → 全屏可缩放浏览 */
class ImageActivity : AppCompatActivity() {

    private lateinit var iv: ImageView
    private var baseW = 0f; var baseH = 0f
    private var scale = 1f
    private val MIN_SCALE = 0.5f; val MAX_SCALE = 8f
    private val translate = PointF()
    private var lastDragX = 0f; private var lastDragY = 0f
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        iv = findViewById(R.id.ivZoom)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = java.io.File(path)
        if (!file.exists()) { Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show(); finish(); return }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth * (sample * 2) > 2048 || opts.outHeight * (sample * 2) > 2048) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: run { finish(); return }
        baseW = bmp.width.toFloat(); baseH = bmp.height.toFloat()
        iv.setImageBitmap(bmp)
        iv.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                iv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                reset()
            }
        })

        val doubleTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scale = if (scale > 1.2f) 1f else 2.5f
                translate.set(0f, 0f)
                applyMatrix()
                return true
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val prev = scale
                scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                val fx = detector.focusX; val fy = detector.focusY
                translate.x += (fx - (iv.width / 2f)) * (1 - scale / prev)
                translate.y += (fy - (iv.height / 2f)) * (1 - scale / prev)
                applyMatrix()
                return true
            }
        })

        iv.setOnTouchListener { _, ev ->
            doubleTap.onTouchEvent(ev)
            pinch.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastDragX = ev.x; lastDragY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging && ev.pointerCount == 1 && !pinch.isInProgress && scale > 1f) {
                        translate.x += ev.x - lastDragX
                        translate.y += ev.y - lastDragY
                        lastDragX = ev.x; lastDragY = ev.y
                        applyMatrix()
                    } else if (ev.pointerCount > 1) {
                        dragging = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }

    private fun reset() {
        val w = iv.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = iv.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        scale = min(w / baseW, h / baseH)
        translate.set(0f, 0f)
        applyMatrix()
    }

    private fun applyMatrix() {
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (iv.width - baseW * scale) / 2f + translate.x,
            (iv.height - baseH * scale) / 2f + translate.y
        )
        @Suppress("DEPRECATION") iv.imageMatrix = matrix
    }
}
