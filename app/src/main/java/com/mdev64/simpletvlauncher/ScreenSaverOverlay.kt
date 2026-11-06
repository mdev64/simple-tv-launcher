package com.mdev64.simpletvlauncher

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen idle screen saver controller & view.
 *
 * Modes:
 * - [MODE_NONE] (0): Do nothing
 * - [MODE_BLACK] (1): Full black screen
 * - [MODE_CLOCK] (2): Full black screen + current time (with subtle anti-burn-in drift)
 * - [MODE_DVD] (3): Old-school DVD player style bouncing animation with user-typed word
 */
class ScreenSaverOverlay(
    private val activity: Activity,
    private val root: FrameLayout,
    private val prefs: Prefs
) {

    private val handler = Handler(Looper.getMainLooper())
    private var saverView: SaverCanvasView? = null
    private var priorFocus: View? = null
    private val priorFocusability = ArrayList<Pair<ViewGroup, Int>>()
    private var swallowNextKeyUp = false

    val isShowing: Boolean
        get() = saverView != null

    private val idleRunnable = Runnable {
        if (!activity.isFinishing && !activity.isDestroyed && !isShowing) {
            val mode = prefs.screensaverMode
            if (mode != MODE_NONE) {
                start(mode)
            }
        }
    }

    private val clockTickRunnable = object : Runnable {
        override fun run() {
            saverView?.invalidate()
            if (isShowing) {
                handler.postDelayed(this, 15_000L)
            }
        }
    }

    /** Call in Activity.onResume() and whenever screen saver settings change. */
    fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        updateKeepScreenOnFlag()
        if (isShowing) return
        val mode = prefs.screensaverMode
        if (mode != MODE_NONE) {
            val timeoutMs = prefs.screensaverTimeoutMin.coerceAtLeast(1) * 60_000L
            handler.postDelayed(idleRunnable, timeoutMs)
        }
    }

    /** Call in Activity.onPause(). */
    fun stopIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(clockTickRunnable)
        if (isShowing) {
            dismiss(restoreFocus = false)
        }
    }

    /**
     * Intercepts key events while the screen saver is active so pressing any key
     * wakes the screen cleanly without clicking whatever button was focused underneath.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                swallowNextKeyUp = true
                dismiss(restoreFocus = true)
                resetIdleTimer()
            }
            return true
        }
        if (swallowNextKeyUp && event.action == KeyEvent.ACTION_UP) {
            swallowNextKeyUp = false
            resetIdleTimer()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            swallowNextKeyUp = false
        }
        resetIdleTimer()
        return false
    }

    /** Starts the screen saver immediately (either from idle timeout or Settings preview). */
    fun start(modeOverride: Int? = null) {
        val mode = modeOverride ?: prefs.screensaverMode
        if (mode == MODE_NONE) return
        if (isShowing) dismiss(restoreFocus = false)

        handler.removeCallbacks(idleRunnable)
        priorFocus = activity.currentFocus

        // Block focus on sibling containers while screen saver covers the screen
        priorFocusability.clear()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) {
                priorFocusability.add(child to child.descendantFocusability)
                child.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }

        val view = SaverCanvasView(activity, mode, prefs).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            alpha = 0f
            setOnClickListener {
                dismiss(restoreFocus = true)
                resetIdleTimer()
            }
        }

        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        saverView = view
        updateKeepScreenOnFlag()

        view.requestFocus()
        view.animate().alpha(1f).setDuration(240L).start()

        if (mode == MODE_CLOCK) {
            handler.removeCallbacks(clockTickRunnable)
            handler.postDelayed(clockTickRunnable, 15_000L)
        }
    }

    fun dismiss(restoreFocus: Boolean = true) {
        handler.removeCallbacks(clockTickRunnable)
        val view = saverView ?: return
        saverView = null

        priorFocusability.forEach { (vg, mode) -> vg.descendantFocusability = mode }
        priorFocusability.clear()

        if (restoreFocus) {
            priorFocus?.requestFocus()
        }
        priorFocus = null
        root.removeView(view)
        updateKeepScreenOnFlag()
    }

    private fun updateKeepScreenOnFlag() {
        if (isShowing || prefs.screensaverMode != MODE_NONE) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // -------------------------------------------------------------- canvas view

    private class SaverCanvasView(
        ctx: Activity,
        private val mode: Int,
        private val prefs: Prefs
    ) : View(ctx) {

        private val dm = resources.displayMetrics
        private fun dp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
        private fun sp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

        // Clock paints
        private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = sp(68f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8CFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        // DVD paints
        private val dvdWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        }
        private val dvdSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.28f
        }
        private val dvdDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dvdHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }

        private val discRect = RectF()
        private val holeRect = RectF()
        private val timeBounds = Rect()
        private val dateBounds = Rect()

        private val dvdPalette = intArrayOf(
            Color.parseColor("#FF00E5FF"), // Cyan
            Color.parseColor("#FFFF2D95"), // Magenta
            Color.parseColor("#FFFFD60A"), // Yellow
            Color.parseColor("#FF30D158"), // Lime Green
            Color.parseColor("#FFFF9F0A"), // Orange
            Color.parseColor("#FF64D2FF"), // Sky Blue
            Color.parseColor("#FFFF453A"), // Coral Red
            Color.parseColor("#FFBF5AF2")  // Electric Purple
        )

        private var colorIndex = 0
        private var dvdInitialized = false
        private var posX = 0f
        private var posY = 0f
        private var velX = dp(165f)
        private var velY = dp(122f)
        private var lastFrameNanos = 0L
        private val customWord: String = prefs.screensaverText

        override fun onDraw(canvas: Canvas) {
            // Pure black background for all active screen saver modes
            canvas.drawColor(Color.BLACK)

            when (mode) {
                MODE_BLACK -> {
                    // Pure black screen — nothing else to draw
                }
                MODE_CLOCK -> drawClock(canvas)
                MODE_DVD -> drawDvdBounce(canvas)
            }
        }

        private fun drawClock(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val now = Date(System.currentTimeMillis())
            val timeFmt = SimpleDateFormat(
                if (prefs.clock24h) "HH:mm" else "h:mm a",
                Locale.getDefault()
            )
            val timeStr = timeFmt.format(now)
            clockPaint.getTextBounds(timeStr, 0, timeStr.length, timeBounds)
            val timeH = timeBounds.height().toFloat()

            val cx = w * 0.5f
            val cy = h * 0.5f

            if (prefs.clockDate) {
                val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                val dateStr = dateFmt.format(now)
                datePaint.getTextBounds(dateStr, 0, dateStr.length, dateBounds)
                val dateH = dateBounds.height().toFloat()
                val gap = dp(14f)
                val totalH = timeH + gap + dateH

                val topY = cy - totalH * 0.5f
                val timeBaseline = topY - timeBounds.top
                val dateBaseline = topY + timeH + gap - dateBounds.top

                canvas.drawText(timeStr, cx, timeBaseline, clockPaint)
                canvas.drawText(dateStr, cx, dateBaseline, datePaint)
            } else {
                val timeBaseline = cy + timeH * 0.5f - timeBounds.bottom
                canvas.drawText(timeStr, cx, timeBaseline, clockPaint)
            }
        }

        private fun drawDvdBounce(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // Measure lockup dimensions dynamically based on user's word
            var wordSize = sp(52f)
            dvdWordPaint.textSize = wordSize
            val maxAllowedW = w * 0.42f
            var measuredW = dvdWordPaint.measureText(customWord)
            if (measuredW > maxAllowedW && measuredW > 0f) {
                wordSize *= (maxAllowedW / measuredW)
                dvdWordPaint.textSize = wordSize
                measuredW = dvdWordPaint.measureText(customWord)
            }

            val boxW = measuredW.coerceAtLeast(dp(136f)) + dp(24f)
            val wordFm = dvdWordPaint.fontMetrics
            val wordH = wordFm.descent - wordFm.ascent
            val discH = dp(24f)
            val gap = dp(4f)
            val subH = dp(14f)
            val boxH = wordH + gap + discH + subH

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (!dvdInitialized) {
                dvdInitialized = true
                posX = ((w - boxW) * 0.27f).coerceAtLeast(0f)
                posY = ((h - boxH) * 0.31f).coerceAtLeast(0f)
                lastFrameNanos = nowNanos
            } else {
                val dt = ((nowNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameNanos = nowNanos

                posX += velX * dt
                posY += velY * dt

                val maxX = (w - boxW).coerceAtLeast(0f)
                val maxY = (h - boxH).coerceAtLeast(0f)
                var bounced = false

                if (posX <= 0f) {
                    posX = 0f
                    velX = kotlin.math.abs(velX)
                    bounced = true
                } else if (posX >= maxX) {
                    posX = maxX
                    velX = -kotlin.math.abs(velX)
                    bounced = true
                }

                if (posY <= 0f) {
                    posY = 0f
                    velY = kotlin.math.abs(velY)
                    bounced = true
                } else if (posY >= maxY) {
                    posY = maxY
                    velY = -kotlin.math.abs(velY)
                    bounced = true
                }

                if (bounced) {
                    colorIndex = (colorIndex + 1) % dvdPalette.size
                }
            }

            val activeColor = dvdPalette[colorIndex]
            dvdWordPaint.color = activeColor
            dvdDiscPaint.color = activeColor

            val centerX = posX + boxW * 0.5f
            val wordBaseline = posY - wordFm.ascent
            canvas.drawText(customWord, centerX, wordBaseline, dvdWordPaint)

            // Classic DVD player elliptical disc & center spindle cutout underneath
            val discTop = posY + wordH + gap
            val discBottom = discTop + discH
            discRect.set(posX + dp(4f), discTop, posX + boxW - dp(4f), discBottom)
            canvas.drawOval(discRect, dvdDiscPaint)

            val holeW = boxW * 0.24f
            val holeH = discH * 0.34f
            val discCenterY = (discTop + discBottom) * 0.5f
            holeRect.set(
                centerX - holeW * 0.5f,
                discCenterY - holeH * 0.5f,
                centerX + holeW * 0.5f,
                discCenterY + holeH * 0.5f
            )
            canvas.drawOval(holeRect, dvdHolePaint)

            // Subtle "VIDEO" caption inside/below the disc ring
            dvdSubPaint.textSize = sp(9.5f)
            dvdSubPaint.color = activeColor
            canvas.drawText("VIDEO", centerX, discBottom + dp(12f), dvdSubPaint)

            postInvalidateOnAnimation()
        }
    }

    companion object {
        const val MODE_NONE = 0
        const val MODE_BLACK = 1
        const val MODE_CLOCK = 2
        const val MODE_DVD = 3
    }
}
