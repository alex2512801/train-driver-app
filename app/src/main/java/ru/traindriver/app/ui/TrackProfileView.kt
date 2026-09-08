package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.Restriction
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitResolver

/**
 * Срез главного экрана (ТЗ раздел 7) — километровая/пикетная линейка и ступенчатый график
 * постоянных ограничений скорости, со шкалой скорости и числами на самих ступенях (сверено
 * с макетом из раздела 14 ТЗ — "Главный экран (ночной режим)"), плюс штриховка и пиктограммы
 * временных ограничений (ТЗ раздел 11, см. [setRestrictions]).
 *
 * Пока НЕ реализовано (сознательно, отдельными следующими шагами): линия сигналов и их
 * таблички, значки сигналов/станций, профиль рельефа, штриховка
 * "короткого участка повышенной скорости" (эффективная скорость уже считается —
 * ShortSegmentSpeedResolver, ТЗ раздел 9 — но зеркальная штриховка "\" на этом графике
 * пока не рисуется), день/ночь. Цвета — из ночной палитры ТЗ (раздел 7).
 *
 * Ряд пиктограмм ограничений (начало/конец опасного места, жёлтый/зелёный щит) по ТЗ должен
 * сидеть на нижнем крае профиля рельефа — раз самого профиля в реальном экране ещё нет (см.
 * выше), пиктограммы временно рисуются в зоне km-линейки, сразу под ступенями. Как только
 * профиль появится — переставить туда (см. README).
 *
 * Полоска физической длины состава ([setTrainLengthM]) рисуется от головы (GPS-координата)
 * назад на реальную длину (TrainComposition.lengthM — условная длина × 14 м + локомотив,
 * ТЗ раздел 5), а не на произвольную иллюстративную константу.
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

        // Пиктограммы ограничений (ТЗ раздел 8/11).
        private const val PICTOGRAM_SIZE_PX = 11f
        private const val PICTOGRAM_ROW_OFFSET_PX = 16f
        private const val PICTOGRAM_MIN_HATCH_WIDTH_PX = 4f

        // "Жёлтый щит стоит на расстоянии ровно 1 км... перед знаком «начало опасного места»;
        // зелёный щит — ровно 1 км после знака «конец опасного места»" (ТЗ раздел 8).
        private const val SHIELD_OFFSET_M = 1000.0
    }

    private data class Segment(val startM: Double, val endM: Double, val speedKmh: Int)

    private var speedLimits: List<SpeedLimit> = emptyList()
    private var resolver = SpeedLimitResolver(emptyList())
    private var direction: Direction = Direction.EVEN
    private var trainPositionM: Double = 0.0

    // Временные ограничения (ТЗ раздел 11) — уже отфильтрованы вызывающим кодом по текущему
    // пути (см. MainActivity.refreshTrackProfileRestrictions), здесь просто рисуются все.
    private var restrictions: List<Restriction> = emptyList()

    // Физическая длина состава (TrainComposition.lengthM — условная длина в усл. вагонах ×
    // 14 м + длина локомотива, ТЗ раздел 5), а не произвольная константа для масштаба —
    // задаётся вызывающим кодом через setTrainLengthM. Полоска поезда рисуется от головы
    // (GPS-координата, trainPositionM) назад ровно на эту длину.
    private var trainLengthM: Double = 0.0

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
    // Полоска физической длины поезда — та же зелень, что и позиционная линия, но толще и
    // короче (только вдоль границы ступеней/линейки, не на всю высоту, см. ТЗ раздел 7).
    private val trainStripePaint = Paint().apply {
        color = Color.rgb(44, 255, 39)
        strokeWidth = 8f
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

    // Штриховка временного ограничения (ТЗ раздел 7: "диагональной штриховкой поверх той же
    // непрерывной ступени... У штриховки — тонкая видимая окантовка").
    private val hatchLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val hatchBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val restrictionNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // Пиктограмма "опасное место" (ТЗ раздел 8): белый круг + чёрное кольцо-обод вплотную к
    // краю + чёрная перекладина той же толщины, что и кольцо, через центр; 8 белых точек на
    // кольце + 3 на перекладине. "Конец опасного места" — тот же знак, повёрнутый на 90°.
    private val opasnoeWhitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val opasnoeBlackPaint = Paint().apply { color = Color.rgb(17, 17, 17); isAntiAlias = true }
    private val opasnoeRingPaint = Paint().apply {
        color = Color.rgb(17, 17, 17)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Жёлтый/зелёный щит (ТЗ раздел 8): белая рамка → более толстая чёрная рамка → цветная
    // заливка внутри — три концентричных квадрата.
    private val shieldWhitePaint = Paint().apply { color = Color.WHITE }
    private val shieldBlackPaint = Paint().apply { color = Color.BLACK }
    private val shieldColorPaint = Paint()
    private val yellowShieldColor = Color.rgb(255, 230, 0)
    private val greenShieldColor = Color.rgb(58, 194, 90)

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

    /** [meters] — TrainComposition.lengthM (условная длина × 14 м + локомотив), не число вагонов. */
    fun setTrainLengthM(meters: Double) {
        trainLengthM = meters
        invalidate()
    }

    /** Список уже отфильтрован вызывающим кодом по текущему пути (ТЗ раздел 11). */
    fun setRestrictions(list: List<Restriction>) {
        restrictions = list
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
        drawRestrictionHatching(canvas, fromM, toM, ::xFor, ::yForSpeed)
        drawSpeedAxis(canvas, ::yForSpeed)
        drawKmRuler(canvas, fromM, toM, ::xFor, stepAreaHeight)
        drawRestrictionPictograms(canvas, fromM, toM, ::xFor, stepAreaHeight)
        drawTrainMarker(canvas, ::xFor, stepAreaHeight)
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

    /**
     * ТЗ раздел 7: "Участки временных ограничений показаны диагональной штриховкой поверх той
     * же непрерывной ступени — сама ступень при этом не разрывается... Высота штриховки всегда
     * считается от значения самого ограничения" — то есть от верха шкалы (speed=100) вниз до
     * y самого ограничения, независимо от высоты ступени под ней, с окантовкой и отдельным
     * (пониженным) числом внутри.
     */
    private fun drawRestrictionHatching(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        yForSpeed: (Int) -> Float
    ) {
        if (restrictions.isEmpty()) return
        val scaleTop = yForSpeed(100)
        for (r in restrictions) {
            if (r.endM < fromM || r.startM > toM) continue
            var x0 = xFor(r.startM.coerceAtLeast(fromM))
            var x1 = xFor(r.endM.coerceAtMost(toM))
            // Точечное ограничение получает минимальную видимую ширину — иначе штриховка
            // нулевой ширины была бы не видна вовсе (та же логика, что и в HTML-макете).
            if (x1 - x0 < PICTOGRAM_MIN_HATCH_WIDTH_PX) {
                val cx = (x0 + x1) / 2f
                x0 = cx - PICTOGRAM_MIN_HATCH_WIDTH_PX / 2f
                x1 = cx + PICTOGRAM_MIN_HATCH_WIDTH_PX / 2f
            }
            val yBottom = yForSpeed(r.speedKmh)

            canvas.save()
            canvas.clipRect(x0, scaleTop, x1, yBottom)
            val w = x1 - x0
            val h = yBottom - scaleTop
            var d = -h
            while (d < w) {
                canvas.drawLine(x0 + d, scaleTop + h, x0 + d + h, scaleTop, hatchLinePaint)
                d += 9f
            }
            canvas.restore()

            canvas.drawRect(x0, scaleTop, x1, yBottom, hatchBorderPaint)

            val metrics = restrictionNumberPaint.fontMetrics
            val textY = (scaleTop + yBottom) / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(r.speedKmh.toString(), (x0 + x1) / 2f, textY, restrictionNumberPaint)
        }
    }

    /**
     * Пиктограммы начала/конца опасного места + жёлтый/зелёный щит (ТЗ раздел 8/11) — см.
     * doc-комментарий класса про временное место в зоне km-линейки (профиля рельефа ещё нет).
     */
    private fun drawRestrictionPictograms(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        stepAreaHeight: Float
    ) {
        if (restrictions.isEmpty()) return
        val rowY = stepAreaHeight + PICTOGRAM_ROW_OFFSET_PX
        for (r in restrictions) {
            if (r.startM in fromM..toM) {
                drawOpasnoeGlyph(canvas, xFor(r.startM), rowY, PICTOGRAM_SIZE_PX, rotated = false)
            }
            if (r.endM in fromM..toM) {
                drawOpasnoeGlyph(canvas, xFor(r.endM), rowY, PICTOGRAM_SIZE_PX, rotated = true)
            }
            val yellowM = r.startM - SHIELD_OFFSET_M
            if (yellowM in fromM..toM) {
                drawShieldGlyph(canvas, xFor(yellowM), rowY, PICTOGRAM_SIZE_PX, yellowShieldColor)
            }
            val greenM = r.endM + SHIELD_OFFSET_M
            if (greenM in fromM..toM) {
                drawShieldGlyph(canvas, xFor(greenM), rowY, PICTOGRAM_SIZE_PX, greenShieldColor)
            }
        }
    }

    private fun drawOpasnoeGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, rotated: Boolean) {
        // Точные размеры с чертежа (ТЗ раздел 8): Ø550мм общий, кольцо/перекладина 100мм
        // толщиной, световозвращатели (точки) Ø51мм — внешний край кольца вплотную к краю круга.
        val ringW = s * (100f / 275f)
        val r = s - ringW / 2f
        canvas.save()
        canvas.translate(cx, cy)
        if (rotated) canvas.rotate(90f)

        canvas.drawCircle(0f, 0f, s, opasnoeWhitePaint)
        opasnoeRingPaint.strokeWidth = ringW
        canvas.drawCircle(0f, 0f, r, opasnoeRingPaint)
        canvas.drawRect(-s, -ringW / 2f, s, ringW / 2f, opasnoeBlackPaint)

        // 8 белых отверстий по кольцу (циферблат, от 12 часов по часовой стрелке) + 3 в
        // перекладине — вместе с 2 крайними на кольце (3 и 9 часов) дают 5 точек на центральной
        // линии, равномерно от -r до +r.
        val dotR = s * (51f / 2f / 275f)
        for (i in 0 until 8) {
            val angle = Math.toRadians((-90 + i * 45).toDouble())
            canvas.drawCircle((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat(), dotR, opasnoeWhitePaint)
        }
        for (f in floatArrayOf(-0.5f, 0f, 0.5f)) {
            canvas.drawCircle(f * r, 0f, dotR, opasnoeWhitePaint)
        }
        canvas.restore()
    }

    private fun drawShieldGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, fillColor: Int) {
        // Точные размеры с чертежа щита (ТЗ раздел 8): сторона 470мм, белая полоса 25мм,
        // чёрная полоса 25мм (обе от внешнего края) — цветная заливка занимает всё остальное.
        val halfMm = 235f
        val borderMm = 25f
        val inset1 = s * (borderMm / halfMm)
        val inset2 = s * ((borderMm * 2f) / halfMm)
        canvas.drawRect(cx - s, cy - s, cx + s, cy + s, shieldWhitePaint)
        canvas.drawRect(cx - s + inset1, cy - s + inset1, cx + s - inset1, cy + s - inset1, shieldBlackPaint)
        shieldColorPaint.color = fillColor
        canvas.drawRect(cx - s + inset2, cy - s + inset2, cx + s - inset2, cy + s - inset2, shieldColorPaint)
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

        // Номер км по центру каждого километрового столбца, как на профилях пути. Обычный
        // км+пк (ChainageFormatter.format) — без +1, это НЕ формат КЛУБ-У.
        val firstKmIndex = floor(fromM / KM_LINE_LENGTH_M).toLong()
        val lastKmIndex = floor(toM / KM_LINE_LENGTH_M).toLong()
        for (kmIndex in firstKmIndex..lastKmIndex) {
            val kmBoundary = kmIndex * KM_LINE_LENGTH_M
            val centerM = kmBoundary + KM_LINE_LENGTH_M / 2
            if (centerM in fromM..toM) {
                canvas.drawText(kmIndex.toString(), xFor(centerM), rulerBottom - 18f, kmTextPaint)
            }
        }
    }

    private fun drawTrainMarker(canvas: Canvas, xFor: (Double) -> Float, stepAreaHeight: Float) {
        val headX = xFor(trainPositionM)
        canvas.drawLine(headX, 0f, headX, height.toFloat(), trainMarkerPaint)

        // Физическая длина состава — от головы (GPS) назад, вдоль границы ступеней/линейки.
        // trainLengthM == 0.0 (длина ещё не задана вызывающим кодом) — полоску не рисуем,
        // остаётся только позиционная линия выше.
        if (trainLengthM > 0.0) {
            val tailX = xFor(trainPositionM - trainLengthM)
            canvas.drawLine(tailX, stepAreaHeight, headX, stepAreaHeight, trainStripePaint)
        }
    }

    private fun drawCurrentSpeedLabel(canvas: Canvas) {
        val limit = resolver.speedAt(direction, trainPositionM)
        val text = if (limit != null) "V=${limit.speedKmh} км/ч" else "V=?"
        canvas.drawText(text, AXIS_WIDTH_PX, 30f, speedTextPaint)
    }
}
