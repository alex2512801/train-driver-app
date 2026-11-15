package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Кнопка "моё местоположение" внизу у "Параметры" (машинист, не из ТЗ/макета — "как в картах,
 * чтобы вернуться на точку позиции поезда") — значок-мишень, как в большинстве
 * карт/навигаторов. Возвращает график к голове поезда (см. TrackProfileView.recenter), после
 * того как машинист протянул его пальцем вперёд/назад, чтобы посмотреть, что там впереди.
 */
class RecenterIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val ringPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(40f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 0.26f
        val tick = width * 0.14f

        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, dp(2.5f), dotPaint)

        canvas.drawLine(cx, cy - r - tick, cx, cy - r + dp(1f), ringPaint)
        canvas.drawLine(cx, cy + r - dp(1f), cx, cy + r + tick, ringPaint)
        canvas.drawLine(cx - r - tick, cy, cx - r + dp(1f), cy, ringPaint)
        canvas.drawLine(cx + r - dp(1f), cy, cx + r + tick, cy, ringPaint)
    }
}
