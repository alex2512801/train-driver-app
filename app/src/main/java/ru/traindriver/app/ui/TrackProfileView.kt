package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitResolver

/**
 * Первый, самый простой срез главного экрана (ТЗ раздел 7) — то, на чём держится всё
 * остальное: километровая/пикетная линейка и ступенчатый график постоянных ограничений
 * скорости. Пока НЕ реализовано (сознательно, отдельными следующими шагами): шапка,
 * линия сигналов и их таблички, пиктограммы, профиль рельефа, штриховка временных
 * ограничений, день/ночь. Цвета — из ночной палитры ТЗ (раздел 7), день/ночь пока нет.
 *
 * Внешний вид ЕЩЁ НЕ ПРОВЕРЕН на реальном экране (в этой среде разработки нет Android SDK,
 * см. README) — пропорции и читаемость нужно будет поправить по факту.
 */
class TrackProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_SPEED_KMH = 100f
        private const val SAMPLE_STEP_M = 20.0
        private const val RULER_HEIGHT_PX = 90f
        private const val KM_LINE_LENGTH_M = 1000.0
        private const val PICKET_LENGTH_M = 100.0
    }

    private var speedLimits: List<SpeedLimit> = emptyList()
    private var resolver = SpeedLimitResolver(emptyList())
    private var direction: Direction = Direction.EVEN
    private var trainPositionM: Double = 0.0

    // 3000 м позади / 5000 м впереди — как в официальном описании экрана ИСАВП-РТ
    // ("основное_окно_сокр.pdf", область 25).
    /** Сколько метров показывать позади/впереди головы поезда. */
    var behindM: Double = 3000.0
    var aheadM: Double = 5000.0

    private val stepPaint = Paint().apply { color = Color.rgb(160, 159, 164) }
    private val kmLinePaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        strokeWidth = 3f
    }
    private val picketLinePaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        strokeWidth = 1f
    }
    private val trainMarkerPaint = Paint().apply {
        color = Color.rgb(44, 255, 39)
        strokeWidth = 5f
    }
    private val kmTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val speedTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    fun setSpeedLimits(limits: List<SpeedLimit>) {
        speedLimits = limits
        resolver = SpeedLimitResolver(limits)
        invalidate()
    }

    fun setDirection(newDirection: Direction) {
        direction = newDirection
        invalidate()
    }

    fun setTrainPositionM(meters: Double) {
        trainPositionM = meters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val fromM = trainPositionM - behindM
        val toM = trainPositionM + aheadM
        val spanM = toM - fromM
        val stepAreaHeight = height - RULER_HEIGHT_PX

        fun xFor(meters: Double): Float = ((meters - fromM) / spanM * width).toFloat()
        fun yForSpeed(speedKmh: Int): Float =
            stepAreaHeight * (1f - (speedKmh.coerceIn(0, 100) / MAX_SPEED_KMH))

        drawSpeedSteps(canvas, fromM, toM, ::xFor, ::yForSpeed, stepAreaHeight)
        drawKmRuler(canvas, fromM, toM, ::xFor, stepAreaHeight)
        drawTrainMarker(canvas, ::xFor)
        drawCurrentSpeedLabel(canvas)
    }

    private fun drawSpeedSteps(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        yForSpeed: (Int) -> Float,
        stepAreaHeight: Float
    ) {
        var m = fromM
        while (m < toM) {
            val next = (m + SAMPLE_STEP_M).coerceAtMost(toM)
            val mid = (m + next) / 2.0
            val limit = resolver.speedAt(direction, mid)
            if (limit != null) {
                canvas.drawRect(xFor(m), yForSpeed(limit.speedKmh), xFor(next), stepAreaHeight, stepPaint)
            }
            m = next
        }
    }

    private fun drawKmRuler(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        stepAreaHeight: Float
    ) {
        val rulerBottom = height.toFloat()
        val picketsPerKm = (KM_LINE_LENGTH_M / PICKET_LENGTH_M).toLong() // 10

        // Целочисленные индексы пикетов/км, а не повторное сложение float — иначе после
        // многих итераций накопленная погрешность может сдвинуть отметку километра.
        val firstPicketIndex = ceil(fromM / PICKET_LENGTH_M).toLong()
        val lastPicketIndex = floor(toM / PICKET_LENGTH_M).toLong()
        for (i in firstPicketIndex..lastPicketIndex) {
            val picketM = i * PICKET_LENGTH_M
            val isKmLine = i % picketsPerKm == 0L
            val x = xFor(picketM)
            if (isKmLine) {
                canvas.drawLine(x, 0f, x, rulerBottom, kmLinePaint)
            } else {
                canvas.drawLine(x, stepAreaHeight, x, rulerBottom, picketLinePaint)
            }
        }

        // Номер км по центру каждого километрового столбца, как на профилях пути.
        val firstKmIndex = floor(fromM / KM_LINE_LENGTH_M).toLong()
        val lastKmIndex = floor(toM / KM_LINE_LENGTH_M).toLong()
        for (kmIndex in firstKmIndex..lastKmIndex) {
            val kmBoundary = kmIndex * KM_LINE_LENGTH_M
            val centerM = kmBoundary + KM_LINE_LENGTH_M / 2
            if (centerM in fromM..toM) {
                canvas.drawText((kmIndex + 1).toString(), xFor(centerM), rulerBottom - 20f, kmTextPaint)
            }
        }
    }

    private fun drawTrainMarker(canvas: Canvas, xFor: (Double) -> Float) {
        val x = xFor(trainPositionM)
        canvas.drawLine(x, 0f, x, height.toFloat(), trainMarkerPaint)
    }

    private fun drawCurrentSpeedLabel(canvas: Canvas) {
        val limit = resolver.speedAt(direction, trainPositionM)
        val text = if (limit != null) "V=${limit.speedKmh} км/ч" else "V=?"
        canvas.drawText(text, 16f, 36f, speedTextPaint)
    }
}
