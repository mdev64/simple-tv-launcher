package com.mdev64.simpletvlauncher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Dashboard tile visuals (favourites and the app grid share them).
 *
 *   idle    : artwork on a dark slate plate, 1dp hairline, flat
 *   focused : tile scales up, a soft top sheen brightens it, and an accent
 *             pill appears under it (drawn on the tile's PARENT, which keeps
 *             10dp of bottom padding for it — see item_tile.xml)
 *
 * No hardware elevation: TV compositors draw harsh halos around shadows.
 */
object TileStyler {

    const val FAVOURITE_SCALE = 1.08f
    const val APP_SCALE = 1.13f
    private const val FOCUS_MS = 110L
    private const val CORNER_DP = 14f

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun radiusPx(ctx: Context) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, CORNER_DP, ctx.resources.displayMetrics)

    private fun plate(ctx: Context) = GradientDrawable().apply {
        cornerRadius = radiusPx(ctx)
        setColor(0xFF1A1A24.toInt())
    }

    private fun hairline(ctx: Context) = GradientDrawable().apply {
        cornerRadius = radiusPx(ctx)
        setColor(0)
        setStroke(dp(ctx, 1f).coerceAtLeast(1), 0x1FFFFFFF)
    }

    private fun sheen(ctx: Context) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x33FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)
    ).apply { cornerRadius = radiusPx(ctx) }

    private fun indicator(ctx: Context, accent: Int): LayerDrawable {
        val pill = GradientDrawable().apply {
            cornerRadius = dp(ctx, 2f).toFloat()
            setColor(accent)
        }
        return LayerDrawable(arrayOf(pill)).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayerSize(0, dp(ctx, 34f), dp(ctx, 3f))
        }
    }

    fun attach(tile: View, accent: Int, focusScale: Float) {
        val ctx = tile.context
        val idleFg = hairline(ctx)
        val focusFg = LayerDrawable(arrayOf(sheen(ctx), hairline(ctx)))
        val pill = indicator(ctx, accent)

        tile.background = plate(ctx)
        tile.foreground = if (tile.isFocused) focusFg else idleFg
        tile.clipToOutline = true
        tile.outlineProvider = ViewOutlineProvider.BACKGROUND
        tile.elevation = 0f
        tile.stateListAnimator = null

        tile.setOnFocusChangeListener { v, hasFocus ->
            v.foreground = if (hasFocus) focusFg else idleFg
            (v.parent as? View)?.let { p ->
                p.background = if (hasFocus) pill else null
                // Lift the whole item above its neighbours without casting a shadow.
                p.translationZ = if (hasFocus) 10f else 0f
            }
            val s = if (hasFocus) focusScale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_MS).start()
        }
    }
}
