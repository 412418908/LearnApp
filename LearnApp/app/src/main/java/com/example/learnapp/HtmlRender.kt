package com.example.learnapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spanned
import kotlin.concurrent.thread

object HtmlRender {

    /** 将 HTML（含 <img src="assets/xx.png">）渲染到 TextView，图片从 sessionDir/build 下加载 */
    fun render(context: Context, html: String, sessionDir: File, textView: android.widget.TextView) {
        val density = context.resources.displayMetrics.density
        val maxWidth = (context.resources.displayMetrics.widthPixels - 60 * density).toInt().coerceAtLeast(200)

        val imageGetter = Html.ImageGetter { src ->
            val file = File(sessionDir, "build/${src.removePrefix("./")}")
            decodeDrawable(context, file, maxWidth) ?: placeholder()
        }

        // 文字先显示，带图版本后台加载后替换
        textView.text = Html.fromHtml(stripImgs(html), Html.FROM_HTML_MODE_LEGACY)
        thread {
            val withImgs: Spanned = Html.fromHtml(html, imageGetter, null, Html.FROM_HTML_MODE_LEGACY)
            textView.post { textView.text = withImgs }
        }
    }

    private fun decodeDrawable(context: Context, file: File, maxWidth: Int): Drawable? {
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
            val d = BitmapDrawable(context.resources,
                Bitmap.createScaledBitmap(bmp, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), true))
            d.setBounds(0, 0, w.toInt(), h.toInt())
            d
        } catch (_: Exception) { null }
    }

    private fun placeholder(): Drawable = object : Drawable() {
        override fun draw(c: Canvas) {}
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    }.apply { setBounds(0, 0, 1, 1) }

    private fun stripImgs(html: String): String =
        html.replace(Regex("<img[^>]*>"), "［图］")
}
