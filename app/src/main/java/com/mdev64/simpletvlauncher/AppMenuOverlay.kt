package com.mdev64.simpletvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Adaptive frosted-glass long-press & confirmation overlay rendered in-activity.
 * Matches the launcher's GlassLayout surfaces and Cupertino pill focus style
 * (solid accent fill + contrast-inverted text on focus, no focus borders).
 */
class AppMenuOverlay(
    private val activity: Activity,
    private val container: FrameLayout,
    private val title: String,
    private val subtitle: String?,
    private val actions: List<Pair<String, () -> Unit>>
) {

    private val scrim = FrameLayout(activity)
    private var previousFocus: View? = null

    /** Where focus goes when the menu closes; defaults to whatever had it before. */
    var returnFocus: View? = null
    private var firstRow: View? = null

    var isShowing = false
        private set

    fun show() {
        val ctx = activity
        val prefs = Prefs(ctx)
        val accent = prefs.accent
        val baseColor = when (prefs.bgMode) {
            1 -> prefs.solidBgs[prefs.bgIndex]
            2 -> prefs.bgDominant
            else -> prefs.solidBgs[0]
        }

        previousFocus = activity.currentFocus

        scrim.setBackgroundColor(0xCC06060A.toInt())
        scrim.isClickable = true
        scrim.setOnClickListener { dismiss() }

        val glassCard = GlassLayout(ctx).apply {
            radiusDp = 26f
            applyAdaptiveTint(baseColor, accent)
            // Fully opaque and a touch darker than the other glass surfaces: tiles showing
            // through the card made the menu text hard to read.
            tintColor = solidDarker(tintColor, 0.78f)
            bottomColor = solidDarker(bottomColor, 0.70f)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }

        // Header block: Title + optional subtitle badge
        val headerBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(12))
        }

        headerBox.addView(TextView(ctx).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val subText = subtitle ?: "Options"
        headerBox.addView(TextView(ctx).apply {
            text = subText
            setTextColor(TEXT_SECONDARY)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        panel.addView(headerBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Subtle frosted divider line
        panel.addView(View(ctx).apply {
            setBackgroundColor(0x1FFFFFFF)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
            bottomMargin = dp(10)
        })

        var previousRow: View? = null
        actions.forEach { (label, action) ->
            val row = menuRow(label, accent) {
                dismiss()
                action()
            }
            // Explicit chain so D-pad always stays inside the menu.
            row.id = View.generateViewId()
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
            previousRow?.let {
                it.nextFocusDownId = row.id
                row.nextFocusUpId = it.id
            }
            previousRow = row
            if (firstRow == null) firstRow = row
            panel.addView(row, fullWidth(dp(46), dp(3)))
        }
        // Clamp the ends of the chain so UP/DOWN never leaves the panel.
        previousRow?.let { it.nextFocusDownId = it.id }
        firstRow?.let { it.nextFocusUpId = it.id }

        glassCard.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scrim.addView(glassCard, FrameLayout.LayoutParams(
            dp(360),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        scrim.alpha = 0f
        glassCard.scaleX = 0.94f
        glassCard.scaleY = 0.94f

        container.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        scrim.animate().alpha(1f).setDuration(140L).start()
        glassCard.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()

        isShowing = true
        firstRow?.post { if (isShowing) firstRow?.requestFocus() }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        val target = returnFocus ?: previousFocus
        previousFocus = null
        target?.requestFocus()
        container.removeView(scrim)
    }

    private fun menuRow(label: String, accent: Int, onClick: () -> Unit): View {
        val ctx: Context = activity
        val isDestructive = label.equals("Uninstall", ignoreCase = true) ||
            label.startsWith("Reset", ignoreCase = true) ||
            label.startsWith("Remove", ignoreCase = true)
        val isCancel = label.equals("Cancel", ignoreCase = true)

        val idleFill = when {
            isDestructive -> 0x1FFF453A
            isCancel -> 0x0FFFFFFF
            else -> 0x16FFFFFF
        }
        val focusFill = if (isDestructive) 0xFFFF453A.toInt() else accent
        val onFocusText = if (isLight(focusFill)) 0xFF141418.toInt() else 0xFFFFFFFF.toInt()
        val idleText = when {
            isDestructive -> 0xFFFF6961.toInt()
            isCancel -> TEXT_SECONDARY
            else -> TEXT_PRIMARY
        }

        val idleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(idleFill)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(focusFill)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = idleBg
            setOnClickListener { onClick() }
        }

        val titleView = TextView(ctx).apply {
            text = label
            setTextColor(idleText)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

        val hintView = TextView(ctx).apply {
            text = if (isCancel) "ESC" else "›"
            setTextColor(TEXT_SECONDARY)
            textSize = if (isCancel) 10.5f else 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            alpha = 0.65f
        }

        row.addView(titleView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(hintView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        row.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) focusedBg else idleBg
            titleView.setTextColor(if (hasFocus) onFocusText else idleText)
            titleView.typeface = Typeface.create(
                "sans-serif-medium",
                if (hasFocus) Typeface.BOLD else Typeface.NORMAL
            )
            hintView.setTextColor(if (hasFocus) onFocusText else TEXT_SECONDARY)
            hintView.alpha = if (hasFocus) 0.90f else 0.55f
        }

        return row
    }

    private fun solidDarker(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(),
        (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt()
    )

    private fun isLight(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b > 0.6f
    }

    private fun fullWidth(height: Int, margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, height
    ).apply {
        topMargin = margin
        bottomMargin = margin
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).toInt()

    private companion object {
        val TEXT_PRIMARY = 0xFFF2F2F7.toInt()
        val TEXT_SECONDARY = 0xFFB4B4BE.toInt()
    }
}
