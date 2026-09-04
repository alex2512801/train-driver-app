package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitResolver

/**
 * Срез главного экрана (ТЗ раздел 7) — километровая/пикетная линейка и ступенчатый график
 * постоянных ограничений скорости, со шкалой скорости и числами на самих ступенях (сверено
 * с макетом из раздела 14 ТЗ — "Главный экран (ночной режим)").
 *
 * Пока НЕ реализовано (сознательно, отдельными следующими шагами): линия сигналов и их
 * таблички, значки сигналов/станций, пиктограммы, профиль рельефа, штриховка временных
 * ограничений (нет данных о временных ограничениях — только постоянные), штриховка
 * "короткого участка повышенной скорости" (ТЗ раздел 9), день/ночь. Цвета — из ночной
 * палитры ТЗ (раздел 7).
 *
 * Внешний вид ПРОВЕРЕН пока только на HTML-макете (см. чат), не на реальном устройстве —
 * в этой среде разработки нет Android SDK, см. README.
 */
class TrackProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_SPEED_KMH = 100f
        private const val SAMPLE_STEP_M = 20.0
        private const val RULER_HEIGHT_PX = 70f
        private const val AXIS_WIDTH_PX = 60f
        private const val KM_LINE_LENGTH_M = 1000.0
        private const val PICKET_LENGTH_M = 100.0
        private const val MIN_SEGMENT_PX_FOR_LABEL = 36f
        private val AXIS_SPEEDS = intArrayOf(100, 80, 60, 40, 20)
    }

    private data class Segment(val startM: Double, val endM: Double, val speedKmh: Int)

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
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val axisTextPaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        textSize = 22f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }
    private val stepNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
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
        val graphLeft = AXIS_WIDTH_PX
        val graphWidth = width - AXIS_WIDTH_PX
        val stepAreaHeight = height - RULER_HEIGHT_PX

        fun xFor(meters: Double): Float = graphLeft + ((meters - fromM) / spanM * graphWidth).toFloat()
        fun yForSpeed(speedKmh: Int): Float =
            stepAreaHeight * (1f - (speedKmh.coerceIn(0, 100) / MAX_SPEED_KMH))

        val segments = computeSegments(fromM, toM)

        drawSpeedSteps(canvas, segments, ::xFor, ::yForSpeed, stepAreaHeight)
        drawSpeedAxis(canvas, ::yForSpeed)
        drawKmRuler(canvas, fromM, toM, ::xFor, stepAreaHeight)
        drawTrainMarker(canvas, ::xFor)
        drawCurrentSpeedLabel(canvas)
    }

    /** Склеивает соседние сэмплы с одинаковой скоростью в один непрерывный участок —
     * иначе не на чем аккуратно центрировать подпись числа на ступени. */
    private fun computeSegments(fromM: Double, toM: Double): List<Segment> {
        val segments = mutableListOf<Segment>()
        var segStart = fromM
        var segSpeed = resolver.speedAt(direction, fromM + SAMPLE_STEP_M / 2)?.speedKmh
        var m = fromM
        while (m < toM) {
            val next = (m + SAMPLE_STEP_M).coerceAtMost(toM)
            val mid = (m + next) / 2.0
            val speed = resolver.speedAt(direction, mid)?.speedKmh
            if (speed != segSpeed) {
                if (segSpeed != null) segments.add(Segment(segStart, m, segSpeed))
                segStart = m
                segSpeed = speed
            }
            m = next
        }
        if (segSpeed != null) segments.add(Segment(segStart, toM, segSpeed))
        return segments
    }

    private fun drawSpeedSteps(
        canvas: Canvas,
        segments: List<Segment>,
        xFor: (Double) -> Float,
        yForSpeed: (Int) -> Float,
        stepAreaHeight: Float
    ) {
        for (segment in segments) {
            val x0 = xFor(segment.startM)
            val x1 = xFor(segment.endM)
            val top = yForSpeed(segment.speedKmh)
            canvas.drawRect(x0, top, x1, stepAreaHeight, stepPaint)

            if (x1 - x0 >= MIN_SEGMENT_PX_FOR_LABEL) {
                val metrics = stepNumberPaint.fontMetrics
                val textY = (top + stepAreaHeight) / 2f - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(segment.speedKmh.toString(), (x0 + x1) / 2f, textY, stepNumberPaint)
            }
        }
    }

    private fun drawSpeedAxis(canvas: Canvas, yForSpeed: (Int) -> Float) {
        for (speed in AXIS_SPEEDS) {
            val y = yForSpeed(speed)
            canvas.drawText(speed.toString(), 4f, y + 8f, axisTextPaint)
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
                canvas.drawText((kmIndex + 1).toString(), xFor(centerM), rulerBottom - 18f, kmTextPaint)
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
        canvas.drawText(text, AXIS_WIDTH_PX, 30f, speedTextPaint)
    }
}
