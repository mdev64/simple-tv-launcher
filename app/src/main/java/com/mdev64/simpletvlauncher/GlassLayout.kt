package com.mdev64.simpletvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Adaptive frosted-glass container: derives its translucent body gradient from
 * the active wallpaper/solid background color, topped with a subtle milky frost
 * sheen, fine static frost grain, and a crisp specular rim. Zero live blur capture
 * and zero hardware elevation shadows so it never flickers on Android TV.
 */
class GlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val surface = MatteSurfaceDrawable(context.resources.displayMetrics.density)

    /** Corner radius in dp (dock = 28, top pills = 25). */
    var radiusDp: Float
        get() = surface.radiusDp
        set(value) {
            surface.radiusDp = value
            invalidate()
        }

    /** Base top surface color. */
    var tintColor: Int
        get() = surface.topColor
        set(value) {
            surface.topColor = value
            invalidate()
        }

    /** Bottom gradient color for subtle depth. */
    var bottomColor: Int
        get() = surface.bottomColor
        set(value) {
            surface.bottomColor = value
            invalidate()
        }

    /** Accent color blended into the top border highlight. */
    var rimAccent: Int
        get() = surface.rimAccent
        set(value) {
            surface.rimAccent = value
            invalidate()
        }

    /**
     * Adapts the frosted surface to [baseColor] (from solid background or photo
     * dominant color) with a subtle frosted glass translucency and sheen.
     */
    fun applyAdaptiveTint(baseColor: Int, accent: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        if (s < 0.08f) {
            // Neutral dark/grey background: lifted frosted slate
            val tv = (v * 0.72f + 0.16f).coerceIn(0.20f, 0.46f)
            val bv = (v * 0.52f + 0.10f).coerceIn(0.14f, 0.34f)
            surface.topColor = Color.HSVToColor(0xD2, floatArrayOf(240f, s, tv))
            surface.bottomColor = Color.HSVToColor(0xDE, floatArrayOf(240f, s, bv))
            surface.rimAccent = accent
        } else {
            // Colored background or photo dominant hue: adaptive frosted tint
            val ts = (s * 0.66f).coerceIn(0.18f, 0.62f)
            val bs = (s * 0.78f).coerceIn(0.22f, 0.72f)
            val tv = (v * 0.76f + 0.18f).coerceIn(0.26f, 0.56f)
            val bv = (v * 0.54f + 0.08f).coerceIn(0.18f, 0.40f)
            surface.topColor = Color.HSVToColor(0xCE, floatArrayOf(h, ts, tv))
            surface.bottomColor = Color.HSVToColor(0xDC, floatArrayOf(h, bs, bv))
            val rimHueColor = Color.HSVToColor(0xFF, floatArrayOf(h, (s * 0.5f).coerceAtMost(0.5f), 0.95f))
            surface.rimAccent = rimHueColor
        }
        surface.mergedWithBackground = false
        invalidate()
    }

    /**
     * Soft frosted-glass surface for the top pinned-apps row that blends and merges
     * seamlessly into the underlying solid or photo background.
     */
    fun applyMergedGlassTint(baseColor: Int, accent: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        if (s < 0.08f) {
            val tv = (v * 0.82f + 0.12f).coerceIn(0.16f, 0.38f)
            val bv = (v * 0.75f + 0.06f).coerceIn(0.10f, 0.28f)
            surface.topColor = Color.HSVToColor(0x6E, floatArrayOf(240f, s, tv))
            surface.bottomColor = Color.HSVToColor(0x3A, floatArrayOf(240f, s, bv))
            surface.rimAccent = accent
        } else {
            val ts = (s * 0.64f).coerceIn(0.14f, 0.58f)
            val bs = (s * 0.72f).coerceIn(0.16f, 0.66f)
            val tv = (v * 0.84f + 0.12f).coerceIn(0.20f, 0.48f)
            val bv = (v * 0.74f + 0.05f).coerceIn(0.14f, 0.36f)
            surface.topColor = Color.HSVToColor(0x68, floatArrayOf(h, ts, tv))
            surface.bottomColor = Color.HSVToColor(0x36, floatArrayOf(h, bs, bv))
            val rimHueColor = Color.HSVToColor(0xFF, floatArrayOf(h, (s * 0.45f).coerceAtMost(0.45f), 0.92f))
            surface.rimAccent = rimHueColor
        }
        surface.mergedWithBackground = true
        invalidate()
    }

    /** When false the frosted surface is removed and the children sit on the bare background. */
    var glassEnabled: Boolean = true
        set(value) {
            field = value
            background = if (value) surface else null
            invalidate()
        }

    init {
        background = surface
        elevation = 0f
        clipToOutline = false
    }
}

class MatteSurfaceDrawable(private val density: Float) : Drawable() {

    companion object {
        /** Static 64x64 fine frost grain shader — allocated once (16 KB) and reused. */
        private val frostShader: BitmapShader by lazy {
            val size = 64
            val px = IntArray(size * size)
            var seed = 0x5DEECE66DL
            for (i in px.indices) {
                seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
                val r = ((seed ushr 24) and 0xFF).toInt()
                px[i] = when {
                    r < 85 -> 0x09FFFFFF
                    r > 170 -> 0x08000000
                    else -> 0x00000000
                }
            }
            val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    var radiusDp: Float = 28f
    var topColor: Int = 0xD2242432.toInt()
    var bottomColor: Int = 0xDE161620.toInt()
    var rimAccent: Int = 0xFFFFFFFF.toInt()
    var mergedWithBackground: Boolean = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = frostShader }
    private val outerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x2E000000
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val insetRect = RectF()
    private val path = Path()
    private val insetPath = Path()

    private fun radiusPx() = radiusDp * density

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val r = min(radiusPx(), min(w, h) / 2f)
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        // 1. Adaptive tinted translucent body.
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            topColor, bottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null

        // 2. Subtle milky frosted glass sheen (light top diffusion).
        val sheenColors = if (mergedWithBackground) {
            intArrayOf(0x16FFFFFF, 0x07FFFFFF, 0x02FFFFFF)
        } else {
            intArrayOf(0x26FFFFFF, 0x0AFFFFFF, 0x12FFFFFF)
        }
        sheen.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            sheenColors,
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, sheen)
        sheen.shader = null

        // 3. Fine static frost micro-texture.
        canvas.drawPath(path, grain)

        // 4. Subtle outer dark contour + crisp inner frosted specular rim.
        val outerStroke = if (mergedWithBackground) 0f else 0.8f * density
        if (outerStroke > 0f) {
            outerEdge.strokeWidth = outerStroke
            canvas.drawPath(path, outerEdge)
        }

        val stroke = if (mergedWithBackground) 0.9f * density else 1.1f * density
        val inset = outerStroke + stroke * 0.5f
        insetRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val ir = (r - inset).coerceAtLeast(0f)
        insetPath.reset()
        insetPath.addRoundRect(insetRect, ir, ir, Path.Direction.CW)

        val topRim = if (mergedWithBackground) {
            blendColor(0x2EFFFFFF, (rimAccent and 0x00FFFFFF) or 0x34000000, 0.30f)
        } else {
            blendColor(0x52FFFFFF, (rimAccent and 0x00FFFFFF) or 0x58000000, 0.35f)
        }
        val rimColors = if (mergedWithBackground) {
            intArrayOf(topRim, 0x14FFFFFF, 0x06FFFFFF)
        } else {
            intArrayOf(topRim, 0x24FFFFFF, 0x18FFFFFF)
        }
        edge.strokeWidth = stroke
        edge.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            rimColors,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(insetPath, edge)
        edge.shader = null
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        fun ch(s: Int) = (((a ushr s) and 0xFF) * inv + ((b ushr s) and 0xFF) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
