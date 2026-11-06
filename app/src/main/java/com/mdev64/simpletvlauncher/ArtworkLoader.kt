package com.mdev64.simpletvlauncher

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.max

/**
 * Loads 16:9 tile artwork: activity banner -> logo -> icon, rasterised once
 * into a tile-sized bitmap and cached (10 MB byte-bounded LRU).
 *
 * Banners/logos (wide) fill the tile edge to edge. Square icons are drawn
 * contain-style, avoiding the horizontal stretching that force-filling a
 * square icon into 16:9 would cause.
 */
class ArtworkLoader(private val pm: PackageManager) {

    // Budget is in bytes: without sizeOf() LruCache counts entries, i.e. never evicts.
    private val cache = object : LruCache<String, Bitmap>(BYTES_BUDGET) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    fun load(item: AppItem, widthPx: Int, heightPx: Int): Bitmap? {
        val key = "$widthPx x $heightPx ${item.key}"
        cache.get(key)?.let { return it }
        val drawable = drawableOf(item) ?: return null
        val bitmap = rasterise(drawable, widthPx, heightPx)
        cache.put(key, bitmap)
        return bitmap
    }

    private fun drawableOf(item: AppItem): Drawable? {
        val c = item.component
        return runCatching { pm.getActivityBanner(c) }.getOrNull()
            ?: runCatching { pm.getActivityLogo(c) }.getOrNull()
            ?: runCatching { pm.getActivityIcon(c) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(c.packageName) }.getOrNull()
    }

    private fun rasterise(src: Drawable, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(
            max(1, width), max(1, height), Bitmap.Config.RGB_565
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val iw = src.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = src.intrinsicHeight.takeIf { it > 0 } ?: height
        val wide = iw.toFloat() / ih.toFloat() >= 1.25f

        if (wide) {
            src.bounds = Rect(0, 0, width, height) // banner fills the tile
        } else {
            val side = (height * 0.64f).toInt()    // icon sits centred
            val left = (width - side) / 2
            val top = (height - side) / 2
            src.bounds = Rect(left, top, left + side, top + side)
        }
        src.draw(canvas)
        return bmp
    }

    private companion object {
        // Carousel cards are ~650 KB (768x432 RGB_565), All-apps tiles ~140 KB: room for the
        // five live cards plus two screens of tiles.
        const val BYTES_BUDGET = 10 * 1024 * 1024
    }
}
