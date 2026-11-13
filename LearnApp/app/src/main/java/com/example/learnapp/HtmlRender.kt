package com.example.learnapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** 决定图片磁盘路径（兼容多种 zip 结构） */
private fun resolveImageFile(sessionDir: File, src: String): File? {
    val clean = src.trim().removePrefix("./").removePrefix("/")
    val candidates = listOf(
        File(sessionDir, "build/$clean"),
        File(sessionDir, clean),
        File(sessionDir.parentFile!!, clean),
        File(sessionDir.parentFile!!, "build/$clean")
    )
    for (c in candidates) if (c.exists()) return c
    // 最后兜底：递归搜 assets/xxx.png
    val name = File(clean).name
    val found = sessionDir.walkTopDown().firstOrNull { it.name == name && it.extension in listOf("png", "jpg", "jpeg", "webp", "gif") }
    return found
}

/** 点图片 → 全屏预览 */
private class ImageClickSpan(
    private val absolutePath: String
) : ClickableSpan() {
    override fun onClick(widget: View) {
        if (absolutePath.isNotBlank()) {
            widget.context.startActivity(
                Intent(widget.context, ImageActivity::class.java)
                    .putExtra("path", absolutePath)
            )
        }
    }
    override fun updateDrawState(ds: TextPaint) { /* no underline */ }
}

object HtmlRender {

    fun render(context: Context, html: String, sessionDir: File, textView: TextView) {
        val density = context.resources.displayMetrics.density
        val maxW = (context.resources.displayMetrics.widthPixels - 64 * density).toInt().coerceAtLeast(200)

        // 先给纯文字占位（含"［图］"标记）
        textView.text = stripImgs(html)
        thread {
            val spanned = buildRich(context, html, sessionDir, maxW)
            textView.post {
                textView.text = spanned
                textView.movementMethod = LinkMovementMethod.getInstance()
                textView.highlightColor = 0
            }
        }
    }

    private fun buildRich(context: Context, html: String, sessionDir: File, maxW: Int): Spanned {
        val regex = Regex("""<img[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val sb = SpannableStringBuilder()
        var cursor = 0
        for (m in regex.findAll(html)) {
            val plainBefore = html.substring(cursor, m.range.first)
            sb.append(Html.fromHtml(plainBefore, Html.FROM_HTML_MODE_LEGACY))
            val src = m.groupValues[1]
            val f = resolveImageFile(sessionDir, src)
            val abs = f?.absolutePath ?: ""
            val bmp = f?.let { loadScaled(it, maxW) }
            val start = sb.length
            sb.append(" ")
            if (bmp != null) {
                val d = BitmapDrawable(context.resources, bmp).apply {
                    setBounds(0, 0, bmp.width, bmp.height)
                }
                sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ImageClickSpan(abs), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // 找不到文件 —— 留个灰色占位
                sb.setSpan(ImagePlaceholderSpan(), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = m.range.last + 1
        }
        val tail = html.substring(cursor)
        sb.append(Html.fromHtml(tail, Html.FROM_HTML_MODE_LEGACY))
        return sb
    }

    private fun loadScaled(file: File, maxW: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) > maxW) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            var w = bmp.width.toFloat(); var h = bmp.height.toFloat()
            if (w > maxW) { h = h * maxW / w; w = maxW.toFloat() }
            Bitmap.createScaledBitmap(bmp, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true)
        } catch (_: Exception) { null }
    }

    private fun stripImgs(html: String): String =
        html.replace(Regex("<img[^>]*>"), "［图］")
}

/** 找不到图片时的占位 span（画个灰色方块） */
private class ImagePlaceholderSpan : android.text.style.ReplacementSpan() {
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val w = 40f; val h = 40f
        val c = android.graphics.Paint(paint).apply { color = 0xFFDDDDDD.toInt(); style = android.graphics.Paint.Style.FILL }
        canvas.drawRect(x, top.toFloat(), x + w, top + h, c)
    }
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int = 40
}
