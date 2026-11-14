package com.example.learnapp

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 通用左滑露出按钮手势处理器：
 * - 向左拖动 → content 负向 translationX，露出右侧按钮层
 * - 向右拖回 → 收回
 * - FLING 快速向左 → 保持 open
 * - 点击 open 区域右侧按钮触发；点击空白区域（或原按钮）触发 onClick
 */
class SwipeTouchListener(
    private val context: Context,
    private val getOpenState: () -> Int,          // -1 未打开，0 打开
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val onClick: () -> Unit,
    private val openWidthDp: Int = 176            // 两个按钮 88*2
) : View.OnTouchListener {

    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var pressedDown = false
    private var openPx = context.resources.displayMetrics.density * openWidthDp

    private val flingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 != null && e2 != null) {
                if ((e2.x - e1.x) < -80 && abs(vx) > 800 && abs(e2.y - e1.y) < 200) {
                    onOpen(); return true
                }
                if ((e2.x - e1.x) > 80 && abs(vx) > 800) {
                    onClose(); return true
                }
            }
            return false
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onClick(); return true
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        flingDetector.onTouchEvent(event)
        val t = v.translationX
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedDown = true; moved = false
                startX = event.x; startY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressedDown) return false
                val dx = event.x - startX
                val dy = event.y - startY
                if (!moved && abs(dx) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (moved && abs(dx) > abs(dy)) {
                    val openNow = t
                    val next = (openNow + dx).coerceIn(-openPx, 0f)
                    v.translationX = next
                    startX = event.x; startY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedDown = false
                if (abs(t + openPx) < 40) {
                    v.animate().translationX(-openPx).setDuration(120).withEndAction { onOpen() }.start()
                } else if (abs(t) < 40) {
                    v.animate().translationX(0f).setDuration(120).withEndAction { onClose() }.start()
                } else {
                    if (t < -openPx / 2) {
                        v.animate().translationX(-openPx).setDuration(120).withEndAction { onOpen() }.start()
                    } else {
                        v.animate().translationX(0f).setDuration(120).withEndAction { onClose() }.start()
                    }
                }
            }
        }
        return true   // 我们消费掉
    }
}
