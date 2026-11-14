package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.model.RoutePillSpec
import com.mbta.tid.mbta_app.model.RouteType
import kotlin.math.roundToInt

/**
 * Draws route badges for the notification: the large icon, like the route pills in the app, and the
 * marker that moves along the progress bar.
 */
object LiveUpdateRouteBadge {
    private const val BADGE_DP = 64f
    private const val TRACKER_DP = 32f

    /** A square bitmap with the route pill in the middle. Large icons are cropped to a square. */
    fun badge(context: Context, spec: RoutePillSpec): Bitmap {
        val size = px(context, BADGE_DP)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val background = parseColor(spec.routeColor, Color.BLACK)
        val foreground = parseColor(spec.textColor, Color.WHITE)

        val pillHeight = size * 0.56f
        val text = (spec.content as? RoutePillSpec.Content.Text)?.text
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = pillHeight * 0.6f
            }
        // Shrink long labels such as "SL1" or "CR" so they fit with some padding.
        if (text != null) {
            val maxTextWidth = size * 0.8f
            val width = textPaint.measureText(text)
            if (width > maxTextWidth) textPaint.textSize *= maxTextWidth / width
        }

        val pillWidth =
            when {
                spec.width == RoutePillSpec.Width.Circle -> pillHeight
                text != null ->
                    (textPaint.measureText(text) + pillHeight * 0.7f).coerceIn(
                        pillHeight,
                        size.toFloat(),
                    )
                else -> pillHeight * 1.5f
            }
        val rect =
            RectF(
                (size - pillWidth) / 2,
                (size - pillHeight) / 2,
                (size + pillWidth) / 2,
                (size + pillHeight) / 2,
            )
        val radius =
            if (spec.shape == RoutePillSpec.Shape.Rectangle) pillHeight * 0.17f else pillHeight / 2
        canvas.drawRoundRect(
            rect,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
        )

        when (val content = spec.content) {
            is RoutePillSpec.Content.Text -> {
                val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(content.text, rect.centerX(), baseline, textPaint)
            }
            is RoutePillSpec.Content.ModeImage ->
                drawIcon(
                    context,
                    modeIcon(content.mode),
                    canvas,
                    rect,
                    pillHeight * 0.6f,
                    foreground,
                )
            RoutePillSpec.Content.Empty -> Unit
        }
        return bitmap
    }

    /** A white circle with the route-colored mode icon, for the progress bar tracker. */
    fun tracker(context: Context, routeType: RouteType, routeColor: String): Bitmap {
        val size = px(context, TRACKER_DP)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val color = parseColor(routeColor, Color.BLACK)
        val half = size / 2f
        canvas.drawCircle(
            half,
            half,
            half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
        )
        canvas.drawCircle(
            half,
            half,
            half * 0.82f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE },
        )
        drawIcon(
            context,
            modeIcon(routeType),
            canvas,
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            size * 0.5f,
            color,
        )
        return bitmap
    }

    /** Same mapping as the app's routeIcon. */
    @DrawableRes
    fun modeIcon(type: RouteType): Int =
        when (type) {
            RouteType.BUS -> R.drawable.mode_bus
            RouteType.COMMUTER_RAIL -> R.drawable.mode_cr
            RouteType.FERRY -> R.drawable.mode_ferry
            else -> R.drawable.mode_subway
        }

    private fun drawIcon(
        context: Context,
        @DrawableRes id: Int,
        canvas: Canvas,
        bounds: RectF,
        iconSize: Float,
        tint: Int,
    ) {
        val drawable = ContextCompat.getDrawable(context, id)?.mutate() ?: return
        drawable.setTint(tint)
        val aspect =
            if (drawable.intrinsicHeight > 0) {
                drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
            } else {
                1f
            }
        val height = iconSize
        val width = iconSize * aspect
        drawable.setBounds(
            (bounds.centerX() - width / 2).roundToInt(),
            (bounds.centerY() - height / 2).roundToInt(),
            (bounds.centerX() + width / 2).roundToInt(),
            (bounds.centerY() + height / 2).roundToInt(),
        )
        drawable.draw(canvas)
    }

    private fun px(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()

    private fun parseColor(hex: String, fallback: Int): Int =
        try {
            "#$hex".toColorInt()
        } catch (e: IllegalArgumentException) {
            fallback
        }
}
