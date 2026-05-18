package com.lui.app.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Horizontal stacked bar showing a night's sleep stage sequence. Each segment
 * is proportional to its duration in minutes, coloured by stage type.
 *
 *   0x02 = light  (blue)
 *   0x03 = deep   (indigo)
 *   0x04 = REM    (purple)
 *   0x05 = awake  (orange)
 *
 * Falls back to a single light-sleep block if no segments are provided.
 */
class SleepTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    /** Ordered pairs of (stage type, minutes). */
    private var segments: List<Pair<Int, Int>> = emptyList()

    fun setSegments(segments: List<Pair<Int, Int>>) {
        this.segments = segments
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = h / 2
        if (segments.isEmpty()) {
            paint.color = Color.parseColor("#2A2A2A")
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, corner, corner, paint)
            return
        }
        val total = segments.sumOf { it.second }.coerceAtLeast(1)
        var x = 0f
        for ((stage, minutes) in segments) {
            val segW = w * (minutes.toFloat() / total.toFloat())
            paint.color = colourForStage(stage)
            rect.set(x, 0f, x + segW, h)
            canvas.drawRect(rect, paint)
            x += segW
        }
        // Soft rounded ends by overlaying a mask rect with corner radius — done
        // in Canvas by drawing a transparent round-rect on top isn't trivial,
        // so just clip via a path. Keep it simple: overlay rounded alpha edges.
        // (Visual nice-to-have; skip for now — rectangles are fine and legible.)
    }

    private fun colourForStage(stage: Int): Int = when (stage) {
        0x03 -> Color.parseColor("#3F51B5") // deep — indigo
        0x02 -> Color.parseColor("#42A5F5") // light — blue
        0x04 -> Color.parseColor("#9C27B0") // REM — purple
        0x05 -> Color.parseColor("#FF9800") // awake — orange
        else -> Color.parseColor("#2A2A2A")
    }
}
