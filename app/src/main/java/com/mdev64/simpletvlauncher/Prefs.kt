package com.mdev64.simpletvlauncher

import android.content.Context
import android.graphics.Color

/**
 * All launcher state in one SharedPreferences file. No Room, no JSON libs —
 * pins are a CSV (order matters), hidden apps a string set.
 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("tvlauncher", Context.MODE_PRIVATE)

    // Accent presets used for the clock, status glyphs, focus rings and dock rim.
    val palette = intArrayOf(
        Color.parseColor("#FFFFFFFF"), // white (default)
        Color.parseColor("#FFC7C7CC"), // silver
        Color.parseColor("#FF8E8E93"), // graphite
        Color.parseColor("#FFFF453A"), // red
        Color.parseColor("#FFFF375F"), // pink
        Color.parseColor("#FFFF6482"), // rose
        Color.parseColor("#FFFF9F0A"), // orange
        Color.parseColor("#FFFFD60A"), // yellow
        Color.parseColor("#FFA8E05F"), // lime
        Color.parseColor("#FF30D158"), // green
        Color.parseColor("#FF2DD4BF"), // teal
        Color.parseColor("#FF64D2FF"), // cyan
        Color.parseColor("#FF0A84FF"), // blue
        Color.parseColor("#FF5E5CE6"), // indigo
        Color.parseColor("#FFBF5AF2"), // purple
        Color.parseColor("#FFDA8FFF"), // lavender
        Color.parseColor("#FFAC8E68"), // brown
        Color.parseColor("#FFE8D8B0")  // sand
    )

    // Solid background presets: a neutral row, then four rows of eight hues at
    // decreasing brightness. Vivid enough to read as colour, still dark enough
    // to keep white text legible. Index 0 is true black (the base colour).
    val solidBgs: IntArray = run {
        val hues = floatArrayOf(355f, 24f, 46f, 145f, 175f, 208f, 252f, 296f)
        // value, saturation
        val levels = arrayOf(
            floatArrayOf(0.62f, 0.78f), floatArrayOf(0.50f, 0.80f),
            floatArrayOf(0.40f, 0.85f), floatArrayOf(0.30f, 0.70f))
        val out = ArrayList<Int>(40)
        intArrayOf(0xFF000000.toInt(), 0xFF15151B.toInt(), 0xFF22222B.toInt(),
            0xFF2E3138.toInt(), 0xFF3F434C.toInt(), 0xFF51555F.toInt(),
            0xFF656974.toInt(), 0xFF7A7E8A.toInt()).forEach { out.add(it) }
        levels.forEach { (v, sat) ->
            hues.forEach { h -> out.add(Color.HSVToColor(floatArrayOf(h, sat, v))) }
        }
        out.toIntArray()
    }

    var themeIndex: Int
        get() = sp.getInt("theme", 0).coerceIn(0, palette.size - 1)
        set(v) = sp.edit().putInt("theme", v.coerceIn(0, palette.size - 1)).apply()

    val accent: Int
        get() = palette[themeIndex]

    /** 1 = solid color preset (default, index 0 is black), 2 = wallpaper image. */
    var bgMode: Int
        get() {
            val m = sp.getInt("bg_mode", 1)
            // Legacy "default dark" (0) is now solid black; photo mode needs a photo.
            return if (m == 2 && bgUri != null) 2 else 1
        }
        set(v) = sp.edit().putInt("bg_mode", v).apply()

    var bgIndex: Int
        get() = sp.getInt("bg_index", 0).coerceIn(0, solidBgs.size - 1)
        set(v) = sp.edit().putInt("bg_index", v.coerceIn(0, solidBgs.size - 1)).apply()

    var bgUri: String?
        get() = sp.getString("bg_uri", null)
        set(v) = sp.edit().putString("bg_uri", v).apply()

    var bgCachedUri: String?
        get() = sp.getString("bg_cached_uri", null)
        set(v) = sp.edit().putString("bg_cached_uri", v).apply()

    /** MediaStore bucket (folder) the last wallpaper was picked from; the picker opens there. */
    var wallpaperBucket: String?
        get() = sp.getString("wp_bucket", null)
        set(v) = sp.edit().putString("wp_bucket", v).apply()

    var bgDominant: Int
        get() = sp.getInt("bg_dominant", solidBgs[0])
        set(v) = sp.edit().putInt("bg_dominant", v).apply()

    /** Soft-blur the custom wallpaper (off by default). */
    var bgBlur: Boolean
        get() = sp.getBoolean("bg_blur", false)
        set(v) = sp.edit().putBoolean("bg_blur", v).apply()

    var clockTime: Boolean
        get() = sp.getBoolean("clock_time", true)
        set(v) = sp.edit().putBoolean("clock_time", v).apply()

    var clockDate: Boolean
        get() = sp.getBoolean("clock_date", true)
        set(v) = sp.edit().putBoolean("clock_date", v).apply()

    /** true = 24h, false = 12h with AM/PM. */
    var clock24h: Boolean
        get() = sp.getBoolean("clock_24h", false)
        set(v) = sp.edit().putBoolean("clock_24h", v).apply()

    // ---- Screen Saver ----

    /**
     * 0 = Do nothing
     * 1 = Full black screen
     * 2 = Full black screen + display time
     * 3 = Old-school DVD player bounce with custom word
     */
    var screensaverMode: Int
        get() = sp.getInt("ss_mode", 2).coerceIn(0, 3)
        set(v) = sp.edit().putInt("ss_mode", v.coerceIn(0, 3)).apply()

    /** Idle timeout in minutes before the screen saver activates. */
    val timeoutOptions = intArrayOf(1, 2, 5, 10, 15, 30, 60)

    var screensaverTimeoutMin: Int
        get() {
            val v = sp.getInt("ss_timeout_min", 10)
            return if (timeoutOptions.contains(v)) v else 10
        }
        set(v) = sp.edit().putInt("ss_timeout_min", v.coerceIn(1, 120)).apply()

    /** User-typed word/phrase for the retro DVD bounce screen saver. */
    var screensaverText: String
        get() = sp.getString("ss_text", "DVD")?.trim()?.takeIf { it.isNotEmpty() } ?: "DVD"
        set(v) {
            val cleaned = v.trim().take(24).ifEmpty { "DVD" }
            sp.edit().putString("ss_text", cleaned).apply()
        }

    // ---- Default Launcher & Boot Persistence ----

    var defaultLauncherEnabled: Boolean
        get() = sp.getBoolean("default_launcher_enabled", false)
        set(v) = sp.edit().putBoolean("default_launcher_enabled", v).apply()

    var launchOnBoot: Boolean
        get() = sp.getBoolean("launch_on_boot", true)
        set(v) = sp.edit().putBoolean("launch_on_boot", v).apply()

    var previousLauncherPkg: String?
        get() = sp.getString("prev_launcher_pkg", null)
        set(v) = sp.edit().putString("prev_launcher_pkg", v).apply()

    var previousLauncherClass: String?
        get() = sp.getString("prev_launcher_cls", null)
        set(v) = sp.edit().putString("prev_launcher_cls", v).apply()

    var previousLauncherLabel: String?
        get() = sp.getString("prev_launcher_label", null)
        set(v) = sp.edit().putString("prev_launcher_label", v).apply()

    /** Location permission (needed for the Wi-Fi SSID since targetSdk 29) was requested once. */
    var locationAsked: Boolean
        get() = sp.getBoolean("loc_perm_asked", false)
        set(v) = sp.edit().putBoolean("loc_perm_asked", v).apply()

    // ---- Pins (ordered CSV of flattened components) ----

    fun pins(): List<String> =
        sp.getString("pins", "")?.split(SEP)?.filter { it.isNotEmpty() }
            ?.take(DockAdapter.DOCK_SIZE) ?: emptyList()

    fun isPinned(key: String) = pins().contains(key)

    /** @return true when the app ended up pinned. */
    fun togglePin(key: String): Boolean {
        val list = pins().toMutableList()
        val nowPinned = if (!list.remove(key)) { list.add(key); true } else false
        sp.edit().putString("pins", list.joinToString(SEP)).apply()
        return nowPinned
    }

    fun clearPins() = sp.edit().putString("pins", "").apply()

    /** Drops pins whose app is no longer installed so they stop occupying dock slots. */
    fun prunePins(installedKeys: Set<String>) {
        val list = pins()
        val alive = list.filter { it in installedKeys }
        if (alive.size != list.size) sp.edit().putString("pins", alive.joinToString(SEP)).apply()
    }

    /** Reorders the dock: moves [key] one slot earlier/later. */
    fun movePin(key: String, up: Boolean): Boolean {
        val list = pins().toMutableList()
        val i = list.indexOf(key)
        if (i < 0) return false
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return false
        list[i] = list[j]
        list[j] = key
        sp.edit().putString("pins", list.joinToString(SEP)).apply()
        return true
    }

    // ---- Hidden (set of keys) ----

    fun hidden(): Set<String> = sp.getStringSet("hidden", emptySet()) ?: emptySet()

    fun isHidden(key: String) = hidden().contains(key)

    /** Hiding also unpins, otherwise the invisible pin keeps a dock slot occupied. */
    fun hide(key: String) {
        if (pins().contains(key)) togglePin(key)
        putHidden(hidden() + key)
    }

    fun unhide(key: String) = putHidden(hidden() - key)

    fun clearHidden() = sp.edit().remove("hidden").apply()

    private fun putHidden(set: Set<String>) =
        sp.edit().putStringSet("hidden", HashSet(set)).apply()

    companion object {
        private const val SEP = "|"
    }
}

/**
 * Pre-scales, center-crops, and contrast-enhances the selected wallpaper to the
 * exact TV screen size when selected, caching it both in memory (0 ms on resume)
 * and on disk (`wallpaper_v2.jpg`, ~10 ms on cold boot).
 */
object WallpaperStore {
    private const val CACHE_FILE = "wallpaper_v2.jpg"

    /** Full-resolution wallpaper (~8 MB at 1080p). Only kept while blur is OFF. */
    @Volatile var cachedBitmap: android.graphics.Bitmap? = null
    @Volatile var cachedUri: String? = null

    /** 160px blurred copy (~60 KB) — the only bitmap kept in memory while blur is ON. */
    @Volatile private var blurResult: android.graphics.Bitmap? = null
    @Volatile private var blurUri: String? = null

    /** True when the store still references [bmp] (so it must not be recycled). */
    fun holds(bmp: android.graphics.Bitmap): Boolean = bmp === cachedBitmap || bmp === blurResult

    /** Drops every in-memory copy (wallpaper removed). */
    @Synchronized
    fun clear() {
        cachedBitmap = null
        cachedUri = null
        blurResult = null
        blurUri = null
    }

    /** What the background ImageView should show, if already in memory (no I/O; main-thread safe). */
    fun memoryDisplay(prefs: Prefs): android.graphics.Bitmap? {
        val uri = prefs.bgUri ?: return null
        if (prefs.bgBlur) {
            val b = blurResult
            return if (b != null && blurUri == uri) b else null
        }
        return getMemoryCached(prefs)?.first
    }

    /**
     * Loads (from disk cache, or converts) what the background should show. With blur on,
     * the full-size bitmap is released after blurring; never recycled, because the
     * ImageView may still be drawing it until the caller swaps in the new drawable.
     * Background thread only.
     */
    @Synchronized
    fun loadDisplay(ctx: Context, prefs: Prefs): android.graphics.Bitmap? {
        memoryDisplay(prefs)?.let { return it }
        val uri = prefs.bgUri ?: return null
        if (!prefs.bgBlur) return loadOrConvert(ctx, prefs)?.first

        // Blur only needs ~160px: decode the disk cache 8x subsampled (240x135, ~130 KB)
        // instead of allocating the full 8 MB frame just to throw it away.
        val inMemory = getMemoryCached(prefs)?.first
        val cacheFile = java.io.File(ctx.filesDir, CACHE_FILE)
        val small = if (inMemory == null && cacheFile.exists() && prefs.bgCachedUri == uri) {
            android.graphics.BitmapFactory.decodeFile(
                cacheFile.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
            )
        } else null
        val source = small ?: inMemory ?: loadOrConvert(ctx, prefs)?.first ?: return null
        val blurred = blurFrom(source)
        if (small != null) small.recycle()
        blurResult = blurred
        blurUri = uri
        cachedBitmap = null
        cachedUri = null
        return blurred
    }

    /** Heavily blurred, low-res copy of [src] (the ImageView upscales it smoothly). */
    private fun blurFrom(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = 160
        val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlurPass(px, w, h, 2, horizontal = true)
            boxBlurPass(px, w, h, 2, horizontal = false)
        }
        val out = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        if (small !== src) small.recycle()
        return out
    }

    private fun boxBlurPass(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val line = IntArray(len)
        val div = 2 * r + 1
        for (l in 0 until lines) {
            for (i in 0 until len) {
                line[i] = if (horizontal) px[l * w + i] else px[i * w + l]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = line[k.coerceIn(0, len - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                val o = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                if (horizontal) px[l * w + i] = o else px[i * w + l] = o
                val add = line[(i + r + 1).coerceIn(0, len - 1)]
                val sub = line[(i - r).coerceIn(0, len - 1)]
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    fun getMemoryCached(prefs: Prefs): Pair<android.graphics.Bitmap, Int>? {
        val bmp = cachedBitmap
        val uri = prefs.bgUri
        if (bmp != null && !bmp.isRecycled && uri != null && cachedUri == uri) {
            return bmp to prefs.bgDominant
        }
        return null
    }

    @Synchronized
    fun loadOrConvert(ctx: Context, prefs: Prefs): Pair<android.graphics.Bitmap, Int>? {
        getMemoryCached(prefs)?.let { return it }
        val uriStr = prefs.bgUri ?: return null
        val cacheFile = java.io.File(ctx.filesDir, CACHE_FILE)
        if (cacheFile.exists() && prefs.bgCachedUri == uriStr) {
            val bmp = android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bmp != null) {
                cachedBitmap = bmp
                cachedUri = uriStr
                return bmp to prefs.bgDominant
            }
        }
        return convertAndSave(ctx, prefs, android.net.Uri.parse(uriStr))
    }

    @Synchronized
    fun convertAndSave(
        ctx: Context,
        prefs: Prefs,
        uri: android.net.Uri
    ): Pair<android.graphics.Bitmap, Int>? = try {
        val dm = ctx.resources.displayMetrics
        val targetW = dm.widthPixels.coerceAtLeast(1280)
        val targetH = dm.heightPixels.coerceAtLeast(720)

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW &&
                   bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            if (raw == null) {
                null
            } else {
                val avgLuma = measureAverageLuma(raw)
                val outBmp = renderScaledAndEnhanced(raw, targetW, targetH, avgLuma)
                if (raw !== outBmp) raw.recycle()

                val dominant = extractDominantColor(outBmp, prefs.solidBgs[0])
                val cacheFile = java.io.File(ctx.filesDir, CACHE_FILE)
                java.io.FileOutputStream(cacheFile).use { fos ->
                    outBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, fos)
                }
                val uriStr = uri.toString()
                prefs.bgDominant = dominant
                prefs.bgCachedUri = uriStr
                cachedBitmap = outBmp
                cachedUri = uriStr
                outBmp to dominant
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun measureAverageLuma(bmp: android.graphics.Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val stepX = (w / 24).coerceAtLeast(1)
        val stepY = (h / 14).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val px = bmp.getPixel(x, y)
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) sum / count else 0.5f
    }

    private fun renderScaledAndEnhanced(
        src: android.graphics.Bitmap,
        targetW: Int,
        targetH: Int,
        avgLuma: Float
    ): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(
            targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(out)
        val scale = kotlin.math.max(
            targetW.toFloat() / src.width.toFloat(),
            targetH.toFloat() / src.height.toFloat()
        )
        val dx = (targetW - src.width * scale) * 0.5f
        val dy = (targetH - src.height * scale) * 0.5f
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }

        // Boost contrast and lift dark midtones when the selected photo is dark.
        val contrast: Float
        val translate: Float
        if (avgLuma < 0.45f) {
            val darkness = ((0.45f - avgLuma) / 0.45f).coerceIn(0f, 1f)
            contrast = 1.08f + 0.24f * darkness
            val pivot = 36f
            val lift = 26f * darkness
            translate = pivot * (1f - contrast) + lift
        } else {
            contrast = 1.06f
            translate = 128f * (1f - contrast)
        }

        val cm = android.graphics.ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG
        ).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    fun extractDominantColor(bmp: android.graphics.Bitmap, fallback: Int): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return fallback

        val bins = 12
        val binWeight = FloatArray(bins)
        val binR = FloatArray(bins)
        val binG = FloatArray(bins)
        val binB = FloatArray(bins)

        var totalR = 0f
        var totalG = 0f
        var totalB = 0f
        var totalCount = 0

        val hsv = FloatArray(3)
        val stepX = (w / 24).coerceAtLeast(1)
        val stepY = (h / 14).coerceAtLeast(1)

        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val px = bmp.getPixel(x, y)
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                totalR += r
                totalG += g
                totalB += b
                totalCount++

                Color.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val v = hsv[2]
                if (sat >= 0.14f && v in 0.12f..0.95f) {
                    val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, bins - 1)
                    val weight = (0.5f + sat) * (1f - kotlin.math.abs(v - 0.55f))
                    binWeight[bin] += weight
                    binR[bin] += r * weight
                    binG[bin] += g * weight
                    binB[bin] += b * weight
                }
                x += stepX
            }
            y += stepY
        }

        var bestBin = -1
        var bestW = 0f
        for (i in 0 until bins) {
            if (binWeight[i] > bestW) {
                bestW = binWeight[i]
                bestBin = i
            }
        }

        return if (bestBin >= 0 && bestW > totalCount * 0.04f) {
            val r = (binR[bestBin] / bestW).toInt().coerceIn(0, 255)
            val g = (binG[bestBin] / bestW).toInt().coerceIn(0, 255)
            val b = (binB[bestBin] / bestW).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } else if (totalCount > 0) {
            val r = (totalR / totalCount).toInt().coerceIn(0, 255)
            val g = (totalG / totalCount).toInt().coerceIn(0, 255)
            val b = (totalB / totalCount).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } else {
            fallback
        }
    }
}
