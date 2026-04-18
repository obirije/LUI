package com.lui.app.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight line chart for a single time-series metric. No axes, no labels —
 * just a stroke from oldest to newest with min/max dots, sized to fit the row.
 *
 * Set values via [setData]. Values <= 0 are treated as gaps and skipped (the
 * stroke continues from the last valid sample).
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: FloatArray = FloatArray(0)
    private var lineColor: Int = Color.parseColor("#4CAF50")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        color = Color.parseColor("#2A2A2A")
    }

    fun setData(values: FloatArray, color: Int) {
        this.values = values
        this.lineColor = color
        linePaint.color = color
        fillPaint.color = (color and 0x00FFFFFF) or 0x33000000  // 20% alpha
        dotPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padX = dp(2f)
        val padY = dp(4f)
        val plotW = w - padX * 2
        val plotH = h - padY * 2

        val nonZero = values.filter { it > 0f }
        if (nonZero.size < 2) return

        val minV = nonZero.min()
        val maxV = nonZero.max()
        val range = (maxV - minV).coerceAtLeast(1e-3f)

        // Baseline at the bottom
        canvas.drawLine(padX, h - padY, w - padX, h - padY, baselinePaint)

        val stepX = plotW / (values.size - 1)
        fun yFor(v: Float): Float = padY + plotH - ((v - minV) / range) * plotH

        // Build stroke path, breaking at gaps (value <= 0)
        val linePath = Path()
        val fillPath = Path()
        var started = false
        var minIdx = 0
        var maxIdx = 0
        for (i in values.indices) {
            val v = values[i]
            if (v <= 0f) {
                started = false
                continue
            }
            val x = padX + stepX * i
            val y = yFor(v)
            if (!started) {
                linePath.moveTo(x, y)
                if (fillPath.isEmpty) fillPath.moveTo(x, h - padY)
                fillPath.lineTo(x, y)
                started = true
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (v < values[minIdx] || values[minIdx] <= 0f) minIdx = i
            if (v > values[maxIdx]) maxIdx = i
        }

        if (!fillPath.isEmpty) {
            // Close the fill path to baseline
            val lastX = padX + stepX * values.indices.last { values[it] > 0f }
            fillPath.lineTo(lastX, h - padY)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
        }
        canvas.drawPath(linePath, linePaint)

        // Min/max dots
        if (values[minIdx] > 0f) {
            canvas.drawCircle(padX + stepX * minIdx, yFor(values[minIdx]), dp(2.5f), dotPaint)
        }
        if (values[maxIdx] > 0f && maxIdx != minIdx) {
            canvas.drawCircle(padX + stepX * maxIdx, yFor(values[maxIdx]), dp(2.5f), dotPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
