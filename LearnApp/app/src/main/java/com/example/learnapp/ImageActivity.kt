package com.example.learnapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.min

/** 全屏可缩放图片浏览 —— 用原生 Activity 避免 AppCompat + 系统主题 崩溃 */
class ImageActivity : Activity() {

    companion object {
        const val TAG = "ImageActivity"
    }

    private lateinit var iv: ImageView
    private var baseW = 0f; var baseH = 0f
    private var scale = 1f
    private val translate = PointF()
    private var dragging = false
    private var lastDragX = 0f; private var lastDragY = 0f
    private val MIN_SCALE = 0.5f; private val MAX_SCALE = 10f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_image)
        } catch (t: Throwable) {
            Log.e(TAG, "setContentView failed", t)
            finish(); return
        }

        iv = findViewById(R.id.ivZoom)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = java.io.File(path)
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在: $path", Toast.LENGTH_LONG).show()
            finish(); return
        }

        try {
            // 采样
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Toast.makeText(this, "无法读取图片（格式损坏）", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            var sample = 1
            while (opts.outWidth / (sample * 2) > 2048 || opts.outHeight / (sample * 2) > 2048) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample })
                ?: BitmapFactory.decodeFile(file.absolutePath)
                ?: run {
                    Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show()
                    finish(); return
                }

            baseW = bmp.width.toFloat(); baseH = bmp.height.toFloat()
            iv.setImageBitmap(bmp)
            Log.d(TAG, "loaded ${file.name} base=${baseW}×${baseH} sample=$sample")
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            Toast.makeText(this, "加载图片异常", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // 等待布局好之后 reset
        iv.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                iv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                try { reset() } catch (t: Throwable) { Log.e(TAG, "reset failed", t) }
            }
        })

        // 手势：双击切换缩放、双指捏合、单指拖动
        val doubleTap = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = if (scale > 1.2f) 1f else 2.5f
                    translate.set(0f, 0f)
                    try { applyMatrix() } catch (_: Throwable) {}
                    return true
                }
            })

        val pinch = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prev = scale
                    scale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    val fx = detector.focusX; val fy = detector.focusY
                    if (iv.width > 0 && iv.height > 0) {
                        translate.x += (fx - iv.width / 2f) * (1 - scale / prev)
                        translate.y += (fy - iv.height / 2f) * (1 - scale / prev)
                    }
                    try { applyMatrix() } catch (_: Throwable) {}
                    return true
                }
            })

        iv.setOnTouchListener { _, ev ->
            try {
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
                        } else if (ev.pointerCount > 1) dragging = false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
            } catch (t: Throwable) { Log.e(TAG, "touch failed", t) }
            true
        }
    }

    private fun reset() {
        val w = iv.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = iv.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        if (baseW <= 0f || baseH <= 0f) return
        scale = min(w / baseW, h / baseH)
        translate.set(0f, 0f)
        applyMatrix()
        Log.d(TAG, "reset: screen=${w}×${h} base=${baseW}×${baseH} scale=$scale")
    }

    private fun applyMatrix() {
        if (iv.width <= 0 || iv.height <= 0) return
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (iv.width - baseW * scale) / 2f + translate.x,
            (iv.height - baseH * scale) / 2f + translate.y
        )
        iv.imageMatrix = matrix
        iv.invalidate()
    }
}
