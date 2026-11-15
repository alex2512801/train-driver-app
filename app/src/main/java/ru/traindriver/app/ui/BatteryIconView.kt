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
 * SIM-карт) — классический контур батарейки с "носиком" справа, заполнение слева по [level]
 * (0..100). Свой рисованный значок, а не emoji — тот же приём, что у StopwatchIconView: emoji
 * "⏱" раньше не отображался на телефоне машиниста вообще, так что для надёжности здесь тоже
 * просто Canvas-рисунок.
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
            invalidate()
        }

    private val outlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(22f).toInt(), dp(14f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nubW = dp(2f)
        val bodyW = width - nubW
        val bodyRect = RectF(dp(1f), dp(1.5f), bodyW - dp(1f), height - dp(1.5f))
        canvas.drawRoundRect(bodyRect, dp(2f), dp(2f), outlinePaint)

        val nubH = height * 0.4f
        canvas.drawRect(bodyW - dp(1f), (height - nubH) / 2f, bodyW + nubW - dp(1f), (height + nubH) / 2f, fillPaint)

        val pad = dp(2.5f)
        val innerW = bodyRect.width() - pad * 2
        val fillW = innerW * (level / 100f)
        if (fillW > 0f) {
            canvas.drawRect(
                bodyRect.left + pad,
                bodyRect.top + pad,
                bodyRect.left + pad + fillW,
                bodyRect.bottom - pad,
                fillPaint
            )
        }
    }
}
