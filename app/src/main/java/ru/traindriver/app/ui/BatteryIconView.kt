package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Значок уровня заряда телефона в шапке (машинист попросил добавить рядом с уровнем сигнала
 * SIM-карт) — как настоящая батарейка в телефоне: ВЕРТИКАЛЬНО, контактный "носик" сверху,
 * заполнение снизу вверх по [level] (0..100). Цвет заполнения — не белый, а плавный переход
 * зелёный (полный заряд) → жёлтый (середина) → красный (мало заряда), через HSV-градус (120°
 * зелёный → 0° красный), а не фиксированные пороги — машинист прямо попросил "через жёлтый
 * цвет", то есть непрерывный переход, а не скачок с одного цвета на другой.
 * Свой рисованный значок, а не emoji — тот же приём, что у StopwatchIconView: emoji "⏱" раньше
 * не отображался на телефоне машиниста вообще, так что для надёжности здесь тоже Canvas-рисунок.
 */
class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    var level: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            updateFillColor()
            invalidate()
        }

    private val outlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    private val nubPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        updateFillColor()
    }

    private fun updateFillColor() {
        // 0% → hue 0° (красный), 100% → hue 120° (зелёный), 50% → hue 60° (жёлтый) — ровно
        // посередине пути, как и просил машинист.
        fillPaint.color = Color.HSVToColor(floatArrayOf(level / 100f * 120f, 1f, 1f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(14f).toInt(), dp(22f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nubH = dp(2f)
        val nubW = width * 0.5f
        canvas.drawRect((width - nubW) / 2f, 0f, (width + nubW) / 2f, nubH, nubPaint)

        val bodyRect = RectF(dp(1f), nubH, width - dp(1f), height - dp(1f))
        canvas.drawRoundRect(bodyRect, dp(2f), dp(2f), outlinePaint)

        val pad = dp(2f)
        val innerTop = bodyRect.top + pad
        val innerBottom = bodyRect.bottom - pad
        val innerH = innerBottom - innerTop
        val fillH = innerH * (level / 100f)
        if (fillH > 0f) {
            canvas.drawRect(
                bodyRect.left + pad,
                innerBottom - fillH,
                bodyRect.right - pad,
                innerBottom,
                fillPaint
            )
        }
    }
}
