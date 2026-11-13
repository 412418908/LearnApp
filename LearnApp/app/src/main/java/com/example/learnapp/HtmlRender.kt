package com.example.learnapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

/** 纯 ClickableSpan，负责把图片点进全屏预览。它和 ImageSpan 同时挂在同一段字符上。 */
private class ImageClickSpan(
    private val src: String,
    private val sessionDir: File
) : ClickableSpan() {
    override fun onClick(widget: View) {
        val intent = Intent(widget.context, ImageActivity::class.java)
        intent.putExtra("path", File(sessionDir, "build/${src.removePrefix("./")}").absolutePath)
        widget.context.startActivity(intent)
    }
    override fun updateDrawState(ds: TextPaint) { /* 不画下划线 */ }
}

object HtmlRender {

    fun render(context: Context, html: String, sessionDir: File, textView: TextView) {
        val density = context.resources.displayMetrics.density
        val maxWidth = (context.resources.displayMetrics.widthPixels - 60 * density).toInt().coerceAtLeast(200)

        // 先显示"文字版"占位
        textView.text = stripImgs(html)
        thread {
            val custom = buildClickableHtml(context, html, sessionDir, maxWidth)
            textView.post {
                textView.text = custom
                textView.movementMethod = LinkMovementMethod.getInstance()
                textView.highlightColor = 0  // 去掉选中高亮背景
            }
        }
    }

    /** 正则切 <img src="..."> 之外的部分正常 Html.fromHtml；img 本身塞占位符 + ImageSpan + ClickableSpan */
    private fun buildClickableHtml(
        context: Context,
        html: String,
        sessionDir: File,
        maxWidth: Int
    ): Spanned {
        val regex = Regex("""<img[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val sb = SpannableStringBuilder()
        var cursor = 0
        for (m in regex.findAll(html)) {
            val plainBefore = html.substring(cursor, m.range.first)
            sb.append(Html.fromHtml(plainBefore, Html.FROM_HTML_MODE_LEGACY))
            val src = m.groupValues[1]
            val file = File(sessionDir, "build/${src.removePrefix("./")}")
            val bmp = loadScaledBitmap(file, maxWidth)
            val start = sb.length
            sb.append(" ")
            if (bmp != null) {
                val ispan = ImageSpan(context, bmp, ImageSpan.ALIGN_BASELINE)
                val cspan = ImageClickSpan(src, sessionDir)
                sb.setSpan(ispan, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(cspan, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = m.range.last + 1
        }
        val tail = html.substring(cursor)
        sb.append(Html.fromHtml(tail, Html.FROM_HTML_MODE_LEGACY))
        return sb
    }

    private fun loadScaledBitmap(file: File, maxWidth: Int): Bitmap? {
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) > maxWidth) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            var w = bmp.width.toFloat(); var h = bmp.height.toFloat()
            if (w > maxWidth) { h = h * maxWidth / w; w = maxWidth.toFloat() }
            Bitmap.createScaledBitmap(bmp, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true)
        } catch (_: Exception) { null }
    }

    private fun stripImgs(html: String): String =
        html.replace(Regex("<img[^>]*>"), "［图］")
}
