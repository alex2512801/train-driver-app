package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Кнопка "Ограничения" в шапке главного экрана — настоящий чёрно-белый знак "начало опасного
 * места" (ТЗ раздел 8), тот же чертёж, что и у пиктограммы OPASNOE_START на графике (там теперь
 * настоящая присланная картинка, см. TrackProfileView.pic_opasnoe_start, здесь — своя рисованная
 * копия для маленькой кнопки в шапке) — специально чёрно-белый, без цветного/жёлтого фона
 * (машинист поправил).
 */
class OpasnoeIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val whitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val blackPaint = Paint().apply { color = Color.rgb(17, 17, 17); isAntiAlias = true }
    private val ringPaint = Paint().apply {
        color = Color.rgb(17, 17, 17)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 40dp — компактнее, чтобы вся шапка помещалась в одну строку без прокрутки
        // (машинист поправил), но ещё не меньше обычного минимального размера тап-зоны.
        val size = dp(40f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val s = min(width, height) / 2f * 0.82f

        val ringW = s * (100f / 275f)
        val r = s - ringW / 2f
        canvas.save()
        canvas.translate(cx, cy)

        canvas.drawCircle(0f, 0f, s, whitePaint)
        ringPaint.strokeWidth = ringW
        canvas.drawCircle(0f, 0f, r, ringPaint)
        canvas.drawRect(-s, -ringW / 2f, s, ringW / 2f, blackPaint)

        val dotR = s * (51f / 2f / 275f)
        for (i in 0 until 8) {
            val angle = Math.toRadians((-90 + i * 45).toDouble())
            canvas.drawCircle((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat(), dotR, whitePaint)
        }
        for (f in floatArrayOf(-0.5f, 0f, 0.5f)) {
            canvas.drawCircle(f * r, 0f, dotR, whitePaint)
        }
        canvas.restore()
    }
}
