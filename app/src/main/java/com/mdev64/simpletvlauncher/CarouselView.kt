package com.mdev64.simpletvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

/**
 * Infinite carousel of pinned apps.
 *
 * The centred app is a full "app card": a gradient in colours picked from its artwork,
 * the artwork itself at a size it stays sharp at, and the app's name + details.
 * Its neighbours are small placeholder tiles, fully on screen: a two-tone gradient
 * picked from the app's artwork colours with the app's initial and name. No masks,
 * clipping, blur or offscreen layers — only translation/scale/alpha + plain shapes,
 * which this TV can animate.
 *
 * Every recycled card holds both faces and cross-fades between them while it moves
 * ([Card.peek]: 0 = centred card face, 1 = placeholder face).
 *
 * Five cards are recycled (offsets -2..+2); the ±2 cards sit off screen so a step
 * slides the next app in from the edge. Draw order is by distance from the centre
 * (no translationZ, so no TV shadows). The view itself holds focus; OK / long-press
 * reach the normal click listeners.
 */
class CarouselView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onCenterChanged(index: Int, item: AppItem?)

        /** Returns the cached artwork, or null and later calls [onReady] on the main thread. */
        fun loadArtwork(item: AppItem, widthPx: Int, heightPx: Int, onReady: (Bitmap) -> Unit): Bitmap?

        /** One line under the app name, e.g. "Video · System app · v2.4". */
        fun describe(item: AppItem): String
    }

    var listener: Listener? = null

    private var items: List<AppItem> = emptyList()

    /** Index of the centred app in [items]. */
    var index = 0
        private set

    val centerItem: AppItem? get() = items.getOrNull(index)

    val cardWidth = dp(CARD_W)
    val cardHeight = dp(CARD_H)

    /**
     * How much of a side tile is on screen, in the tile's own (unscaled) px: the gap
     * between the centre card and the screen edge, divided by the tile's scale.
     */
    private val sideVisible =
        (resources.displayMetrics.widthPixels / 2f - cardWidth / 2f - dp(GAP)) / SIDE_SCALE

    /** Artwork raster size (the poster inside each card). */
    val artWidth = dp(POSTER_W)
    val artHeight = dp(POSTER_H)

    /** Placeholder colours picked from an app's artwork. */
    private class Palette(val start: Int, val end: Int, val text: Int)

    // Declared before [cards]: Card's constructor reads these.
    private val neutral = Palette(0xFF3A3D4A.toInt(), 0xFF22242D.toInt(), Color.WHITE)
    private val palettes = HashMap<String, Palette>()

    /** Blurred crop of the wallpaper behind the centred card (null = solid background). */
    private var frost: Bitmap? = null
    private val interpolator = DecelerateInterpolator(1.6f)

    private val cards = Array(SLOTS) { Card(context) }
    private val offsets = IntArray(SLOTS) { it - SLOTS / 2 }
    private val emptyCard = TextView(context)
    private val emptyBg = FrostDrawable(resources.displayMetrics.density)

    /**
     * A FrameLayout whose alpha is applied per child instead of through an offscreen
     * buffer. Card-sized buffers on every animation frame cost ~50 ms/frame on this TV.
     */
    private class FlatLayout(ctx: Context) : FrameLayout(ctx) {
        override fun hasOverlappingRendering() = false
    }

    private inner class Card(ctx: Context) : FrameLayout(ctx) {
        override fun hasOverlappingRendering() = false

        // Centre face.
        val face = FlatLayout(ctx)
        val faceBg = FrostDrawable(resources.displayMetrics.density).also { it.backdrop = frost }
        val poster = FrameLayout(ctx)
        val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val name = TextView(ctx)
        val details = TextView(ctx)

        // Side face (drawn at card size, shown scaled down to SIDE_SCALE).
        val tile = FlatLayout(ctx)
        val tileBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0))
        val tileContent = LinearLayout(ctx)
        val initial = TextView(ctx)
        val tileName = TextView(ctx)

        var key: String? = null
        var animator: ValueAnimator? = null
        private var side = 0

        var peek = 0f
            set(v) {
                field = v
                val t = abs(v)
                // Short overlap window: only one big face is drawn for most of a step.
                val fa = ((SWAP_TO - t) / (SWAP_TO - SWAP_FROM)).coerceIn(0f, 1f)
                val ta = ((t - SWAP_FROM) / (SWAP_TO - SWAP_FROM)).coerceIn(0f, 1f)
                face.alpha = fa
                tile.alpha = ta
                face.visibility = if (fa <= 0.001f) View.INVISIBLE else View.VISIBLE
                tile.visibility = if (ta <= 0.001f) View.INVISIBLE else View.VISIBLE
            }

        init {
            // No clipToOutline anywhere on the card: every layer draws its own rounded
            // shape. Clipping an animated card cost ~10 ms/frame on this TV.

            // ---- centre face: frosted glass tinted with the app's colours (see FrostDrawable).
            faceBg.radius = dp(RADIUS).toFloat()
            face.background = faceBg
            poster.apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(POSTER_RADIUS).toFloat()
                    setColor(0xFF0E0E14.toInt())
                }
                foreground = GradientDrawable().apply {
                    cornerRadius = dp(POSTER_RADIUS).toFloat()
                    setColor(0)
                    setStroke(dp(1f).coerceAtLeast(1), 0x33FFFFFF)
                }
                // No clipToOutline: the artwork is drawn as a rounded bitmap-shader rect
                // (RoundedArt). A rounded clip on a moving card cost ~10 ms/frame.
                addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            name.apply {
                textSize = 23f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = cardWidth - dp(64f)
            }
            details.apply {
                textSize = 12.5f
                setTextColor(0xB8FFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = cardWidth - dp(64f)
                letterSpacing = 0.02f
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER // poster + text block sits in the middle of the card
                addView(poster, LinearLayout.LayoutParams(artWidth, artHeight))
                addView(name, LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20f) })
                addView(details, LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) })
            }
            face.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            // ---- side face: sizes are pre-divided by SIDE_SCALE so they read right on screen
            // Pre-divided so the corners read as TILE_RADIUS once scaled down.
            tileBg.cornerRadius = dp(TILE_RADIUS / SIDE_SCALE).toFloat()
            tile.background = tileBg
            initial.apply {
                textSize = 52f / SIDE_SCALE
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
                gravity = Gravity.CENTER
            }
            tileName.apply {
                textSize = 13.5f / SIDE_SCALE
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                // maxLines, not singleLine: singleLine lays text out on a very wide scrolling
                // line and shifts the canvas, which throws the fade shader off completely.
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                maxWidth = (sideVisible * TEXT_SPAN).toInt()
            }
            // Initial + name sit in the on-screen part of the tile (see [setSide]).
            tileContent.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(initial, LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ))
                addView(tileName, LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10f / SIDE_SCALE) })
            }
            tile.addView(tileContent, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            applyPalette(neutral)
        }

        /**
         * Points the tile at its side of the screen: content moves into the visible part.
         * (The edge fade is not the tile's job: static `edge_fade_*` overlays in the home
         * layout dim anything near the screen edges, moving or not.)
         */
        fun setSide(dir: Int) {
            if (dir == 0 || dir == side) return
            side = dir
            tileContent.translationX = -dir * (cardWidth / 2f - sideVisible * TEXT_CENTRE)
        }

        fun bind(item: AppItem, detailLine: String) {
            key = item.key
            name.text = item.label
            details.text = detailLine
            tileName.text = item.label
            initial.text = monogram(item.label)
        }

        fun clear() {
            key = null
            art.setImageDrawable(null)
            name.text = ""
            details.text = ""
            tileName.text = ""
            initial.text = ""
            applyPalette(neutral)
        }

        fun clearArtwork() {
            art.setImageDrawable(null)
            applyPalette(neutral)
        }

        fun setArtwork(itemKey: String, bmp: Bitmap) {
            if (key != itemKey) return
            art.setImageDrawable(RoundedArt(bmp, dp(POSTER_RADIUS).toFloat()))
            applyPalette(palettes.getOrPut(itemKey) { paletteOf(bmp) })
        }

        private fun applyPalette(p: Palette) {
            tileBg.colors = intArrayOf(p.start, p.end)
            // Centre card: the same colours pre-darkened (one layer instead of colour + shade).
            // Translucent so the (blurred) wallpaper reads through; darker at the bottom for text.
            faceBg.setTint(
                (shade(p.start, 0.55f) and 0x00FFFFFF) or FROST_TOP_ALPHA,
                (shade(p.end, 0.30f) and 0x00FFFFFF) or FROST_BOTTOM_ALPHA
            )
            initial.setTextColor(p.text)
            tileName.setTextColor((p.text and 0x00FFFFFF) or 0xD0000000.toInt())
        }

        fun moveTo(tx: Float, scale: Float, alpha: Float, peekTo: Float, animate: Boolean) {
            animator?.cancel()
            animator = null
            if (!animate) {
                translationX = tx
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                peek = peekTo
                hideIfClear()
                return
            }
            val tx0 = translationX
            val s0 = scaleX
            val a0 = this.alpha
            val p0 = peek
            if (alpha > 0f) visibility = View.VISIBLE
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STEP_MS
                interpolator = this@CarouselView.interpolator
                addUpdateListener {
                    val f = it.animatedValue as Float
                    translationX = tx0 + (tx - tx0) * f
                    val s = s0 + (scale - s0) * f
                    scaleX = s
                    scaleY = s
                    this@Card.alpha = a0 + (alpha - a0) * f
                    peek = p0 + (peekTo - p0) * f
                }
                doOnEnd { hideIfClear() }
                start()
            }
        }

        /**
         * A fully transparent card is still drawn in full (its faces skip offscreen alpha
         * layers, so alpha is applied per draw), and the ±2 cards now wait ON screen at the
         * side spot — so take them out of the draw entirely.
         */
        private fun hideIfClear() {
            visibility = if (alpha <= 0.001f) View.INVISIBLE else View.VISIBLE
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        isChildrenDrawingOrderEnabled = true
        for (c in cards) {
            addView(c, LayoutParams(cardWidth, cardHeight, Gravity.CENTER))
        }
        emptyCard.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xC7FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(48f), 0, dp(48f), 0)
            setLineSpacing(0f, 1.3f)
            val title = "Your home row is empty"
            text = android.text.SpannableStringBuilder(title).apply {
                setSpan(android.text.style.RelativeSizeSpan(1.6f), 0, title.length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, title.length, 0)
                append("\n\nPress OK or ▼ to open Other apps, then hold OK on an app\nand choose “Add to home”.")
            }
            // Same frosted surface as an app card, in a neutral tint.
            background = emptyBg.apply {
                radius = dp(RADIUS).toFloat()
                setTint(0xB32A2C38.toInt(), 0xD6181922.toInt())
            }
            visibility = View.GONE
        }
        addView(emptyCard, LayoutParams(cardWidth, cardHeight, Gravity.CENTER))
        layoutCards(animate = false)
    }

    /**
     * Replaces the list. The centre stays on [keepKey] when it is still present,
     * otherwise on the same index (clamped).
     */
    fun submit(next: List<AppItem>, keepKey: String? = centerItem?.key) {
        val sameList = next.map { it.key } == items.map { it.key }
        items = next
        val keep = keepKey?.let { k -> next.indexOfFirst { it.key == k } } ?: -1
        index = when {
            next.isEmpty() -> 0
            keep >= 0 -> keep
            else -> index.coerceIn(0, next.size - 1)
        }
        emptyCard.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE
        if (!sameList) for (i in 0 until SLOTS) bindSlot(i)
        layoutCards(animate = false)
        listener?.onCenterChanged(index, centerItem)
    }

    /** Re-requests artwork + details for every card (e.g. after the cache was warmed). */
    fun rebindArtwork() {
        for (i in 0 until SLOTS) bindSlot(i)
    }

    /** Re-centres on [target] without animation (e.g. BACK = return to the first app). */
    fun jumpTo(target: Int) {
        if (items.isEmpty()) return
        index = wrap(target)
        for (i in 0 until SLOTS) bindSlot(i)
        layoutCards(animate = false)
        listener?.onCenterChanged(index, centerItem)
    }

    /**
     * Sets the frosted backdrop for the centre card: a small, already-blurred crop of the
     * wallpaper exactly behind [centerCardOnScreen]. Stretched over the card it reads as
     * frosted glass for the cost of one bitmap fill.
     */
    fun setFrost(bmp: Bitmap?) {
        frost = bmp
        for (c in cards) c.faceBg.backdrop = bmp
        emptyBg.backdrop = bmp
    }

    /** Where the centred card sits on screen (at rest, focused), in px. */
    fun centerCardOnScreen(): android.graphics.Rect? {
        if (width == 0 || height == 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val l = loc[0] + (width - cardWidth) / 2
        val t = loc[1] + (height - cardHeight) / 2
        return android.graphics.Rect(l, t, l + cardWidth, t + cardHeight)
    }

    fun step(dir: Int) {
        if (items.size < 2) return
        index = wrap(index + dir)
        val half = SLOTS / 2
        for (i in 0 until SLOTS) {
            val moved = offsets[i] - dir
            var o = moved
            if (o < -half) o += SLOTS
            if (o > half) o -= SLOTS
            offsets[i] = o
            if (o != moved) {
                // Recycled card jumps to the far side invisibly, then slides in from there.
                applyLayout(cards[i], o, animate = false)
                bindSlot(i)
            } else {
                // Short lists hide some offsets (2 apps: no left neighbour), so a card can
                // move into a spot that shows a different app than it was bound to. A card
                // heading for a hidden spot keeps its content while it fades out.
                val want = itemAtOffset(o)
                if (want != null && cards[i].key != want.key) bindSlot(i)
            }
        }
        layoutCards(animate = true)
        listener?.onCenterChanged(index, centerItem)
    }

    private fun wrap(i: Int): Int {
        val n = items.size
        return if (n == 0) 0 else ((i % n) + n) % n
    }

    private fun bindSlot(slot: Int) {
        val card = cards[slot]
        val item = itemAtOffset(offsets[slot])
        if (item == null) {
            card.clear()
            return
        }
        card.bind(item, listener?.describe(item).orEmpty())
        val cached = listener?.loadArtwork(item, artWidth, artHeight) { bmp ->
            card.setArtwork(item.key, bmp)
        }
        if (cached != null) card.setArtwork(item.key, cached) else card.clearArtwork()
    }

    private fun itemAtOffset(o: Int): AppItem? {
        val n = items.size
        if (n == 0) return null
        if (o != 0 && n == 1) return null // a lone app has no neighbours
        // With two apps the other app shows on BOTH sides: LEFT and RIGHT both lead to it.
        return items[wrap(index + o)]
    }

    /**
     * Card colours: the most vivid colour in a 12×7 sample of the artwork, fading into a
     * darker shade of it; grey-only artwork falls back to a neutral slate.
     */
    private fun paletteOf(art: Bitmap): Palette {
        val small = Bitmap.createScaledBitmap(art, 12, 7, true)
        val hsv = FloatArray(3)
        var best = 0
        var bestScore = 0f
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (hsv[2] > 0.2f && score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (small !== art) small.recycle()
        if (bestScore < 0.18f) return neutral
        Color.colorToHSV(best, hsv)
        // Rich but not neon, and never so bright that white text washes out.
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.78f)
        val start = Color.HSVToColor(hsv)
        hsv[0] = (hsv[0] + 14f) % 360f
        hsv[2] *= 0.45f
        val end = Color.HSVToColor(hsv)
        return Palette(start, end, Color.WHITE)
    }

    /** [c] scaled towards black; [keep] = fraction of its brightness kept. */
    private fun shade(c: Int, keep: Float): Int = Color.rgb(
        (Color.red(c) * keep).toInt(), (Color.green(c) * keep).toInt(), (Color.blue(c) * keep).toInt()
    )

    private fun monogram(label: String): String {
        val first = label.trim().firstOrNull { it.isLetterOrDigit() } ?: return "•"
        return first.uppercaseChar().toString()
    }

    private fun layoutCards(animate: Boolean) {
        for (i in 0 until SLOTS) applyLayout(cards[i], offsets[i], animate)
        invalidate()
    }

    private fun applyLayout(card: Card, o: Int, animate: Boolean) {
        val n = items.size
        val visible = itemAtOffset(o) != null
        val a = abs(o)
        val dir = sign(o.toFloat())
        val scale = if (a == 0) (if (isFocused) 1f else IDLE_SCALE) else SIDE_SCALE
        val alpha = if (!visible) 0f else when (a) { 0 -> 1f; 1 -> SIDE_ALPHA; else -> 0f }
        val side = cardWidth * SIDE_SCALE
        // ±2 cards wait just off screen, so a step slides the next one in through the
        // (dimmed) screen edge.
        val tx = dir * when (a) {
            0 -> 0f
            1 -> cardWidth / 2f + dp(GAP) + side / 2f
            else -> cardWidth / 2f + dp(GAP) + side + dp(GAP) + side / 2f
        }
        card.setSide(dir.toInt())
        card.moveTo(tx, scale, alpha, if (a == 0) 0f else 1f, animate)
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        // Cards farthest from the centre first; the empty-state card (non-card child) last.
        val order = (0 until childCount).sortedWith(compareBy<Int> { i ->
            if (i < SLOTS) -abs(offsets[i]) else 1
        })
        return order[drawingPosition]
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val center = cards.indices.firstOrNull { offsets[it] == 0 } ?: return
        val s = if (gainFocus) 1f else IDLE_SCALE
        cards[center].animate().scaleX(s).scaleY(s).setDuration(150L).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private companion object {
        const val SLOTS = 5
        const val CARD_W = 576f
        const val CARD_H = 324f
        const val POSTER_W = 256f
        const val POSTER_H = 144f
        const val RADIUS = 22f
        const val POSTER_RADIUS = 12f
        /** On-screen corner radius of a side tile. */
        const val TILE_RADIUS = 14f
        const val GAP = 24f
        /** Side tiles are 80 % of a card; the screen edge cuts off their outer part. */
        const val SIDE_SCALE = 0.8f
        /** Tile text: centred at 25 % of the visible part, ahead of most of the edge fade. */
        const val TEXT_CENTRE = 0.25f
        const val TEXT_SPAN = 0.46f
        /** Centre card frost: body opacity at the top / bottom. */
        const val FROST_TOP_ALPHA = 0xB3000000.toInt()
        const val FROST_BOTTOM_ALPHA = 0xD6000000.toInt()
        /** Card ↔ tile cross-fade happens only between these [Card.peek] values. */
        const val SWAP_FROM = 0.42f
        const val SWAP_TO = 0.58f
        const val SIDE_ALPHA = 1f
        const val IDLE_SCALE = 0.95f
        const val STEP_MS = 300L
    }
}

/**
 * Frosted-glass card surface, drawn as ONE rounded-rect fill whose shader stacks:
 * blurred wallpaper backdrop → translucent app-colour tint → milky top sheen → fine grain;
 * plus a 1dp light rim. One fill instead of MatteSurfaceDrawable's five keeps the animated
 * card cheap on this TV (five card-sized fills cost ~10 ms/frame).
 */
private class FrostDrawable(private val density: Float) : Drawable() {

    var radius = 0f
    var backdrop: Bitmap? = null
        set(v) {
            field = v
            dirty = true
            invalidateSelf()
        }

    private var top = 0xB3242432.toInt()
    private var bottom = 0xD6161620.toInt()
    private var dirty = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val rimRect = RectF()

    fun setTint(topColor: Int, bottomColor: Int) {
        if (topColor == top && bottomColor == bottom) return
        top = topColor
        bottom = bottomColor
        dirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        dirty = true
    }

    private fun rebuild() {
        rect.set(bounds)
        val y0 = rect.top
        val y1 = rect.bottom
        // Tint with the milky top sheen folded in (white mixed into the top stop) — one
        // gradient instead of tint + sheen layers.
        var shader: Shader = LinearGradient(
            0f, y0, 0f, y1,
            intArrayOf(milky(top, 0.16f), milky(blend(top, bottom, 0.45f), 0.05f), bottom),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        backdrop?.takeIf { !it.isRecycled }?.let { bd ->
            val bs = BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // Shaders sample nearest-neighbour by default: the 72px backdrop would show as
            // blocks. Linear sampling turns the stretch into a smooth blur (API 33+).
            if (android.os.Build.VERSION.SDK_INT >= 33) bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
            bs.setLocalMatrix(Matrix().apply {
                setScale(rect.width() / bd.width, rect.height() / bd.height)
                postTranslate(rect.left, rect.top)
            })
            shader = ComposeShader(bs, shader, PorterDuff.Mode.SRC_OVER)
        }
        fill.shader = ComposeShader(shader, grain, PorterDuff.Mode.SRC_OVER)

        rim.strokeWidth = density
        rim.shader = LinearGradient(
            0f, y0, 0f, y1,
            intArrayOf(0x59FFFFFF, 0x1FFFFFFF, 0x0FFFFFFF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val h = density / 2f
        rimRect.set(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h)
        dirty = false
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (dirty) rebuild()
        canvas.drawRoundRect(rect, radius, radius, fill)
        val r = (radius - density / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** [c] with [t] of white mixed in (alpha nudged up so it reads as milky, not paler). */
    private fun milky(c: Int, t: Float): Int {
        val m = blend(c or 0xFF000000.toInt(), Color.WHITE, t) and 0x00FFFFFF
        val a = ((c ushr 24) + (255 - (c ushr 24)) * t * 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or m
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        fun ch(s: Int) = (((a ushr s) and 0xFF) * inv + ((b ushr s) and 0xFF) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        /** 64×64 static frost grain, shared by every card. */
        val grain: BitmapShader by lazy {
            val size = 64
            val px = IntArray(size * size)
            var seed = 0x2545F491L
            for (i in px.indices) {
                seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
                val r = ((seed ushr 24) and 0xFF).toInt()
                px[i] = when {
                    r < 80 -> 0x0BFFFFFF
                    r > 176 -> 0x0A000000
                    else -> 0
                }
            }
            val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}

private inline fun android.animation.Animator.doOnEnd(crossinline block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}

/** Poster artwork with rounded corners drawn by its own shader — no view clipping needed. */
private class RoundedArt(private val bmp: Bitmap, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (android.os.Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply {
                setScale(rect.width() / bmp.width, rect.height() / bmp.height)
                postTranslate(rect.left, rect.top)
            })
        }
    }

    override fun draw(canvas: Canvas) {
        if (!bmp.isRecycled && !rect.isEmpty) canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun getIntrinsicWidth() = bmp.width
    override fun getIntrinsicHeight() = bmp.height
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
