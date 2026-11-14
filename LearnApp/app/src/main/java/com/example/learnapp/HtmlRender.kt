package com.example.learnapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** 支持的图片后缀 */
private val SUPPORTED_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif")

/** 决定图片磁盘路径（兼容多种 zip 结构） */
private fun resolveImageFile(sessionDir: File, src: String): File? {
    val clean = src.trim().removePrefix("./").removePrefix("/")
    val candidates = listOf(
        File(sessionDir, "build/$clean"),
        File(sessionDir, clean),
        File(sessionDir.parentFile ?: return null, clean),
        File(sessionDir.parentFile ?: return null, "build/$clean")
    )
    for (c in candidates) {
        if (c.exists()) {
            Log.d("HtmlRender", "resolveImageFile hit (candidate): src=$src → ${c.absolutePath}")
            return c
        }
    }
    // 兜底：递归搜
    val name = File(clean).name
    sessionDir.walkTopDown().forEach {
        if (it.isFile && it.name == name) {
            Log.d("HtmlRender", "resolveImageFile hit (walk): src=$src → ${it.absolutePath}")
            return it
        }
    }
    Log.w("HtmlRender", "resolveImageFile MISS: src=$src  sessionDir=${sessionDir.absolutePath}")
    return null
}

/** 点图片 → 全屏预览 */
private class ImageClickSpan(private val absPath: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        widget.context.startActivity(
            Intent(widget.context, ImageActivity::class.java).putExtra("path", absPath))
    }
    override fun updateDrawState(ds: TextPaint) {}
}

object HtmlRender {
    const val TAG = "HtmlRender"

    /**
     * 计算图片最大允许宽度（像素）。
     * 左侧 bot 头像 40dp + 间距 6dp + 箭头 6dp（重叠 1dp）
     * = 总共约 51dp 要从屏幕宽里扣掉。
     * 再额外保险扣 8dp，防止 TextView 的 lineWidth 超出。
     */
    private fun calcMaxImageWidth(context: Context, textView: TextView): Int {
        val dm = context.resources.displayMetrics
        val padLr = (textView.paddingLeft + textView.paddingRight).toFloat()
        val outsideDp = 40f + 6f + 6f
        val safeDp = 8f
        val allowPx = dm.widthPixels - (padLr + (outsideDp + safeDp) * dm.density)
        return allowPx.toInt().coerceAtLeast(120)
    }

    fun render(context: Context, html: String, sessionDir: File, textView: TextView) {
        val maxW = calcMaxImageWidth(context, textView)

        // 先文字占位
        textView.text = stripImgs(html)

        // 如果根本没 <img>，直接跑主线程构建结果（不走后台线程，避免闪烁）
        if (!html.contains("<img", ignoreCase = true)) {
            val spanned = buildRich(context, html, sessionDir, maxW)
            textView.text = spanned
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.highlightColor = 0
            return
        }

        thread {
            val spanned = buildRich(context, html, sessionDir, maxW)
            textView.post {
                textView.text = spanned
                textView.movementMethod = LinkMovementMethod.getInstance()
                textView.highlightColor = 0
            }
        }
    }

    private fun buildRich(ctx: Context, html: String, sessionDir: File, maxW: Int): Spanned {
        val regex = Regex("""<img[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val sb = SpannableStringBuilder()
        var cursor = 0
        var okCount = 0; var errCount = 0

        for (m in regex.findAll(html)) {
            val plainBefore = html.substring(cursor, m.range.first)
            sb.append(Html.fromHtml(plainBefore, Html.FROM_HTML_MODE_LEGACY))
            val src = m.groupValues[1]
            val abs = resolveImageFile(sessionDir, src)?.absolutePath ?: ""
            val ext = File(src).extension.lowercase()

            val start = sb.length
            sb.append(" ")

            if (ext !in SUPPORTED_EXTS) {
                Log.w(TAG, "unsupported image ext: $src")
                // 画一个灰色占位提示
                sb.setSpan(ImagePlaceholderSpan("不支持的图片格式 .$ext"),
                    start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                errCount++
            } else {
                val bmp = if (abs.isNotBlank()) loadScaled(File(abs), maxW) else null
                if (bmp != null) {
                    // 二次保险：宽度仍超过 maxW 再缩一次
                    val finalBmp = if (bmp.width > maxW) {
                        val ratio = maxW.toFloat() / bmp.width
                        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(bmp, maxW, h, true).also {
                            if (it !== bmp) bmp.recycle()
                        }
                    } else bmp
                    // 把 60% 透明度烘焙进位图像素：
                    // 直接给 Drawable 设 alpha 在部分机型与 ImageSpan 组合会渲染成空白
                    val faded = Bitmap.createBitmap(
                        finalBmp.width, finalBmp.height, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(faded).drawBitmap(
                        finalBmp, 0f, 0f, android.graphics.Paint().apply { alpha = 153 })
                    if (faded !== finalBmp) finalBmp.recycle()
                    val d = android.graphics.drawable.BitmapDrawable(ctx.resources, faded).apply {
                        setBounds(0, 0, faded.width, faded.height)
                    }
                    sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_CENTER),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ImageClickSpan(abs),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    okCount++
                } else {
                    Log.w(TAG, "decode failed: src=$src abs=$abs")
                    sb.setSpan(ImagePlaceholderSpan("图片加载失败"),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    errCount++
                }
            }
            cursor = m.range.last + 1
        }
        val tail = html.substring(cursor)
        sb.append(Html.fromHtml(tail, Html.FROM_HTML_MODE_LEGACY))
        if (errCount > 0 || okCount > 0)
            Log.d(TAG, "buildRich: ok=$okCount err=$errCount")
        return sb
    }

    private fun loadScaled(file: File, maxW: Int): Bitmap? {
        if (!file.exists()) { Log.w(TAG, "loadScaled: file not exist ${file.absolutePath}"); return null }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w(TAG, "loadScaled: inJustDecodeBounds failed ${file.name}"); return null
            }
            var sample = 1
            while (opts.outWidth / (sample * 2) > maxW) sample *= 2
            val full = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample })
            if (full == null) {
                // 兜底：不传 inSampleSize 再来一次
                Log.w(TAG, "loadScaled: sampled decode failed, retry no sample ${file.name}")
                val b = BitmapFactory.decodeFile(file.absolutePath)
                if (b == null) {
                    Log.w(TAG, "loadScaled: full decode ALSO failed ${file.name}"); return null
                }
                var w = b.width.toFloat(); var h = b.height.toFloat()
                if (w > maxW) { h = h * maxW / w; w = maxW.toFloat() }
                Bitmap.createScaledBitmap(b, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true)
            } else {
                var w = full.width.toFloat(); var h = full.height.toFloat()
                if (w > maxW) { h = h * maxW / w; w = maxW.toFloat() }
                Bitmap.createScaledBitmap(full, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadScaled exception for ${file.absolutePath}: ${e.message}", e)
            null
        }
    }

    private fun stripImgs(html: String) =
        html.replace(Regex("<img[^>]*>"), "［图］")
}

/** 找不到图片时的占位 span */
private class ImagePlaceholderSpan(private val label: String) : android.text.style.ReplacementSpan() {
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val w = 80f; val h = 40f
        val c = android.graphics.Paint(paint).apply {
            color = 0xFFDDDDDD.toInt()
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(x, top.toFloat(), x + w, top + h, c)
        android.graphics.Paint(paint).apply {
            color = 0xFF888888.toInt()
            textSize = 22f
            isAntiAlias = true
        }.let { canvas.drawText(label, x + 4f, top + h / 2f + 8f, it) }
    }
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int = 80
}
