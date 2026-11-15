package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import ru.traindriver.app.route.AirGap
import ru.traindriver.app.route.BlockPost
import ru.traindriver.app.route.Crossing
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.NeutralInsert
import ru.traindriver.app.route.Restriction
import ru.traindriver.app.route.ReliefProfile
import ru.traindriver.app.route.ShortSegmentSpeedResolver
import ru.traindriver.app.route.Signal
import ru.traindriver.app.route.effectiveDirection
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitResolver
import ru.traindriver.app.route.Station

/**
 * Срез главного экрана (ТЗ раздел 7) — километровая/пикетная линейка, ступенчатый график
 * постоянных ограничений скорости, линия сигналов и профиль рельефа (ТЗ раздел 7/8/9/15),
 * портировано из HTML-макета "Ступени Хилока" (см. чат).
 *
 * Два яруса, пропорция верх:низ = [STEPS_TO_RELIEF_RATIO] = 2,5:1 (машинист продиктовал по
 * измерению на экране, см. companion object): верх — от верха блоков скорости до линии
 * сигналов (ступени + тёмное "поле" + сама линия сигналов/км), низ — под линией сигналов, где
 * сетка пикетов и рельеф. km-линейка/засечки рисуются ПРЯМО на линии сигналов, отдельной
 * полосы-линейки больше нет.
 *
 * Сам этот View — только средняя часть экрана (эта пропорция 7,5:3 — только между ступенями и
 * рельефом внутри него); высота самого View относительно шапки и нижней статусной строки
 * задаётся весами LinearLayout в activity_main.xml (та же продиктованная пропорция целиком).
 *
 * Раздел 9 ("короткий участок повышенной скорости") — эффективная скорость считается
 * [ShortSegmentSpeedResolver] (уже было), здесь дорисована зеркальная штриховка "\".
 *
 * Пиктограммы (переезды/воздушные промежутки/нейтральные вставки/опасное место/щиты) сидят
 * прямо на графике рельефа — два ФИКСИРОВАННЫХ места: низ полосы рельефа (обычно, когда
 * рельеф высокий и заливка доходит до низа), или верх полосы (когда рельеф опускается
 * настолько, что иконка на нижнем месте повисла бы в пустом поле).
 *
 * Переезды (пиктограмма-чертёж, реальный PNG знак в HTML-макете) и блок-посты (пока без поля
 * "какой сигнал заменён") здесь сознательно НЕ дорисованы/упрощены — см. README/чат.
 *
 * Внешний вид ПРОВЕРЕН пока только на HTML-макете (см. чат), не на реальном устройстве — в
 * этой среде разработки нет Android SDK (не получилось даже собрать проект, чтобы прогнать
 * существующие unit-тесты — Gradle не может скачать Android-плагin без доступа к
 * dl.google.com), см. README.
 */
class TrackProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_SPEED_KMH = 100f
        private const val SAMPLE_STEP_M = 20.0
        private const val KM_LINE_LENGTH_M = 1000.0
        private const val PICKET_LENGTH_M = 100.0
        private val AXIS_SPEEDS = intArrayOf(100, 80, 60, 40, 20)

        // Верх:низ = 7,5:3 = 2,5:1 (машинист продиктовал по измерению на экране: вся высота
        // экрана 15,5 условных единиц — шапка 1,3, график 10,5 (из них скорости+сигналы 7,5 и
        // рельеф 3), нижняя строка 3,7 — см. doc-комментарий класса и activity_main.xml).
        private const val STEPS_TO_RELIEF_RATIO = 2.5f
        // Доля верхнего яруса, занятая самой шкалой скорости (0..100) — ниже неё, до линии
        // сигналов, тёмное "поле" без делений, где сидят иконки/таблички сигналов (см.
        // HTML-макет, drawStepsAndSignalLine: scaleBottom = top + bandH*0.62).
        private const val SCALE_BOTTOM_FRACTION = 0.62f

        private const val SHIELD_OFFSET_M = 1000.0

        // Панорамирование: самая низкая точка видимого окна — у пола полосы рельефа, а не
        // выше (машинист). floorY = reliefTop + reliefH * RELIEF_FLOOR_FRACTION.
        private const val RELIEF_FLOOR_FRACTION = 0.92f

        // Все размеры ниже — в dp (условных единицах, не зависящих от плотности экрана
        // конкретного телефона), переводятся в настоящие пиксели через dp() при создании
        // View (см. density/dp ниже). Раньше здесь лежали "голые" числа пикселей, подобранные
        // на глаз по HTML-макету на экране компьютера — на настоящем телефоне с другой
        // плотностью экрана они оказались почти нечитаемо мелкими (машинист: "там вообще
        // ничего не видно"). Значения увеличены и переведены в dp, чтобы исправить это разом.
        private const val AXIS_WIDTH_DP = 70f
        private const val MIN_SEGMENT_DP_FOR_LABEL = 50f
        private const val PICTOGRAM_MIN_HATCH_WIDTH_DP = 6f

        // Пиктограммы на рельефе — два фиксированных места (см. doc-комментарий класса).
        private const val ICON_HALF_DP = 16f
        private const val ICON_GAP_DP = 6f

        // dp на 1pt склеенной шкалы рельефа (REAL_RELIEF_PX_PER_PT в HTML-макете) — подобрано
        // визуально, это не калибровка в метры (её нет, см. README шаг 15).
        private const val RELIEF_DP_PER_PT = 1.5f

        // Геометрия значка сигнала/станции (капсула/кольцо) — те же пропорции, что в
        // HTML-макете (rx:ry:столбик = 5:7:8), но крупнее в dp; станция крупнее сигнала в
        // 7/6 раза по полной высоте, ножка:радиус = 50:45 — оба выводятся из этого отношения,
        // а не масштабируются от сигнала по отдельности (см. HTML-макет).
        private const val SIGNAL_RY_DP = 10f
        private const val SIGNAL_RX_DP = 7f
        private const val SIGNAL_POLE_DP = 12f
        private const val SIGNAL_TOTAL_H_DP = SIGNAL_RY_DP * 2f + SIGNAL_POLE_DP
        private const val STATION_TOTAL_H_DP = SIGNAL_TOTAL_H_DP * 7f / 6f
        private const val STATION_R_OUTER_DP = STATION_TOTAL_H_DP / (2f + 50f / 45f)
        private const val STATION_R_INNER_DP = STATION_R_OUTER_DP * 0.65f
        private const val STATION_POLE_DP = STATION_R_OUTER_DP * (50f / 45f)
    }

    private data class Segment(val startM: Double, val endM: Double, val speedKmh: Int)

    /** Нижняя граница "занятой" зоны ступени — низ обычной заливки, либо низ штриховки
     *  раздела 9, если она здесь есть (см. [drawSpeedSteps]). Перпендикуляры стартуют отсюда. */
    private data class StepRegionBottom(val startM: Double, val endM: Double, val bottomY: Float)

    /** Один сигнал на линии сигналов — настоящий (из track_signals.json/блок-постов) либо
     *  станция (крупнее, другой значок). */
    private data class SignalTag(val chainageM: Double, val label: String, val isStation: Boolean)

    private data class Pictogram(val chainageM: Double, val kind: PictogramKind)

    private enum class PictogramKind { OPASNOE_START, OPASNOE_END, YELLOW_SHIELD, GREEN_SHIELD, AIR_GAP, NEUTRAL }

    private var speedLimits: List<SpeedLimit> = emptyList()
    private var resolver = SpeedLimitResolver(emptyList())
    private var shortSegmentResolver = ShortSegmentSpeedResolver(emptyList())

    // direction — эффективный датасет (DirectionSelection.effectiveDataset): им размечены
    // speed_limits/track_signals/track_stations/crossings/air_gaps/block_posts, т.к. это
    // физические объекты на конкретном пути. rawDirection — направление движения как есть
    // (DirectionSelection.direction, БЕЗ поправки на путь) — нужно только нейтральной вставке,
    // у которой км+пк зависит от направления счёта пикетажа, а не от физического пути
    // (см. NeutralInsert).
    private var direction: Direction = Direction.EVEN
    private var rawDirection: Direction = Direction.EVEN
    private var trainPositionM: Double = 0.0

    private var restrictions: List<Restriction> = emptyList()
    private var trainLengthM: Double = 0.0

    private var signals: List<Signal> = emptyList()
    private var stations: List<Station> = emptyList()
    private var blockPosts: List<BlockPost> = emptyList()
    private var relief: ReliefProfile = ReliefProfile(emptyList())
    private var crossings: List<Crossing> = emptyList()
    private var airGaps: List<AirGap> = emptyList()
    private var neutralInserts: List<NeutralInsert> = emptyList()

    /** Сколько метров показывать позади/впереди головы поезда. */
    var behindM: Double = 3000.0
    var aheadM: Double = 5000.0

    // Протягивание графика пальцем вперёд/назад (машинист, не из ТЗ/макета — "как в
    // навигаторах", посмотреть, что там впереди). panOffsetM сдвигает окно обзора целиком
    // (верхнюю часть со ступенями/сигналами и нижний рельеф — ОДНИМ и тем же числом, поэтому
    // они всегда протягиваются синхронно, см. fromM/toM в onDraw). Если 10 секунд не трогать —
    // само возвращается на голову поезда (см. panRevertRunnable); кнопка "моё местоположение"
    // (recenter()) делает то же самое сразу.
    private var panOffsetM: Double = 0.0
    private var lastTouchX: Float = 0f
    private val panRevertHandler = Handler(Looper.getMainLooper())
    private val panRevertRunnable = Runnable { recenter() }
    private val panRevertDelayMs = 10_000L

    // Плотность экрана конкретного телефона — все размеры в companion object записаны в dp
    // (условных единицах) и переводятся в настоящие пиксели здесь, один раз, при создании
    // View. Должно идти РАНЬШЕ всех Paint-полей ниже, иначе dp() в их инициализаторах
    // отработает по ещё не заполненному density (см. doc-комментарий класса/README).
    private val density: Float = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val axisWidthPx = dp(AXIS_WIDTH_DP)
    private val minSegmentPxForLabel = dp(MIN_SEGMENT_DP_FOR_LABEL)
    private val pictogramMinHatchWidthPx = dp(PICTOGRAM_MIN_HATCH_WIDTH_DP)
    private val iconHalfPx = dp(ICON_HALF_DP)
    private val iconGapPx = dp(ICON_GAP_DP)
    private val reliefPxPerPt = dp(RELIEF_DP_PER_PT)
    private val signalRyPx = dp(SIGNAL_RY_DP)
    private val signalRxPx = dp(SIGNAL_RX_DP)
    private val signalPolePx = dp(SIGNAL_POLE_DP)
    private val stationROuterPx = dp(STATION_R_OUTER_DP)
    private val stationRInnerPx = dp(STATION_R_INNER_DP)
    private val stationPolePx = dp(STATION_POLE_DP)

    private val stepPaint = Paint().apply { color = Color.rgb(160, 159, 164) }
    // Тёмное "поле" от низа шкалы скорости до линии сигналов — визуально одна сплошная зона
    // со ступенями, без чёрного разрыва (ТЗ раздел 7).
    private val signalFieldPaint = Paint().apply { color = Color.rgb(36, 36, 36) }
    private val kmLinePaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        strokeWidth = dp(4f)
    }
    private val picketLinePaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        strokeWidth = dp(1.5f)
    }
    private val reliefFillPaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        style = Paint.Style.FILL
    }
    // Вертикальная позиционная линия (голова поезда) — на всю высоту графика. Раньше была
    // зелёной и толстой — машинист поправил: голова и хвост поезда (см. thickPerpendicularPaint
    // ниже) должны выглядеть ОДИНАКОВО (обе серые, тонкие), а зелёный цвет — только у полосы
    // поезда НА РЕЛЬЕФЕ (trainReliefGreenPaint), чтобы не путать два разных элемента.
    private val trainMarkerPaint = Paint().apply {
        color = Color.rgb(160, 159, 164)
        strokeWidth = dp(2f)
    }
    // Серая полоска поезда НА ЛИНИИ СИГНАЛОВ (та же заливка, что у ступеней) + её белая
    // окантовка — ТЗ раздел 7. Не путать с зелёной полосой НА РЕЛЬЕФЕ ниже.
    private val trainSignalStripePaint = Paint().apply {
        color = Color.rgb(160, 159, 164)
        style = Paint.Style.FILL
    }
    private val trainSignalStripeBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    // Зелёная полоса поезда НА РЕЛЬЕФЕ — повторяет его изгибы, не прямая линия; не заходит
    // ниже кривой (нижний край полосы = кривая, см. [drawTrainReliefStripe]), плюс тонкая
    // белая окантовка по контуру со всех сторон.
    private val trainReliefGreenPaint = Paint().apply {
        color = Color.rgb(44, 255, 39)
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
    }
    private val trainReliefOutlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }
    // 16dp — машинист попросил сделать километровые числа такими же маленькими, как цифры
    // времени в шапке (timeText, 16sp) — раньше (34dp) они "захламляли экран".
    private val kmTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val axisTextPaint = Paint().apply {
        color = Color.rgb(178, 178, 178)
        textSize = dp(28f)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }
    private val stepNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(54f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val speedTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(34f)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    // Штриховка временных ограничений (ТЗ раздел 11, "/") И короткого участка раздела 9 ("\",
    // reverse) — тот же угол 45°, в разные стороны, см. [drawHatch].
    private val hatchLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val hatchBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }
    private val restrictionNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(30f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    // Чёрная обводка чисел на штриховке (и раздела 9, и временных ограничений) — иначе
    // сливаются с чёрно-белыми полосами штриховки (машинист).
    private val hatchNumberOutlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // Линия сигналов, значки, таблички (ТЗ раздел 7) — белая/негатив как и остальной график,
    // КРОМЕ самой таблички (белый фон/чёрный текст — фиксированно, см. applyPalette).
    private val signalLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dp(3f)
    }
    private val signalPolePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val signalIconPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val stationRingOuterPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    // Значок станции — вторая (тёмная) половина круга + внутренние кольца противоположных
    // цветов на каждой половине, см. drawStationIcon.
    private val stationHalfDarkPaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }
    private val stationInnerRingDarkPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    private val stationInnerRingLightPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    // Табличка сигнала/станции — БЕЛАЯ с ЧЁРНЫМ текстом фиксированно, несмотря на день/ночь
    // (сверено с реальным скриншотом ТЗ раздел 14) — не входит в applyPalette.
    private val signalTagBgPaint = Paint().apply { color = Color.WHITE }
    private val signalTagBorderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    // 16dp — та же просьба машиниста, что и у kmTextPaint: название сигнала и расстояние до
    // него (одна и та же надпись, см. drawSignalTags) — такого же маленького размера, как
    // цифры времени в шапке (раньше 26dp).
    private val signalTagTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = dp(16f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    // Нейтральная вставка — синяя полоса НА линии сигналов, от начала на реальную протяжённость
    // (ТЗ раздел 8), чуть ниже серой полосы поезда по высоте; белая окантовка по контуру.
    private val neutralBarPaint = Paint().apply { color = Color.rgb(40, 80, 200) }
    private val neutralBarBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    // Тонкие (от границ ступеней/пиктограмм) и толстые (проекция головы/хвоста поезда)
    // перпендикуляры — ТЗ раздел 7/9: идут СНИЗУ от линии сигналов и доходят РОВНО до скорости
    // (обычной заливки или штриховки раздела 9), сквозь неё не проходят.
    private val thinPerpendicularPaint = Paint().apply {
        color = Color.argb(128, 200, 200, 200)
        strokeWidth = dp(1.5f)
    }
    private val thickPerpendicularPaint = Paint().apply {
        color = Color.rgb(160, 159, 164)
        strokeWidth = dp(2f)
    }

    // Пиктограмма "опасное место" (ТЗ раздел 8) — см. drawOpasnoeGlyph.
    private val opasnoeWhitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val opasnoeBlackPaint = Paint().apply { color = Color.rgb(17, 17, 17); isAntiAlias = true }
    private val opasnoeRingPaint = Paint().apply {
        color = Color.rgb(17, 17, 17)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Жёлтый/зелёный щит (ТЗ раздел 8).
    private val shieldWhitePaint = Paint().apply { color = Color.WHITE }
    private val shieldBlackPaint = Paint().apply { color = Color.BLACK }
    private val shieldColorPaint = Paint()
    private val yellowShieldColor = Color.rgb(255, 230, 0)
    private val greenShieldColor = Color.rgb(58, 194, 90)

    // Воздушный промежуток (ТЗ раздел 8) — сетка 3×7, чёрные строки 1/3/5/7 + сплошная чёрная
    // средняя колонка целиком сверху донизу (см. drawAirGapGlyph).
    private val airGapWhitePaint = Paint().apply { color = Color.WHITE }
    private val airGapBlackPaint = Paint().apply { color = Color.rgb(17, 17, 17) }
    private val airGapBorderPaint = Paint().apply {
        color = Color.rgb(136, 136, 136)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    // Нейтральная вставка — пиктограмма "включить ток" (ГОСТ 8442-65): синий ромб, чёрная
    // окантовка + серый контур снаружи, внутри — 2 белых квадрата сверху + 1 пошире снизу.
    private val neutralGlyphOuterPaint = Paint().apply {
        color = Color.rgb(136, 136, 136)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val neutralGlyphWhitePaint = Paint().apply { color = Color.WHITE }
    private val neutralGlyphBlackPaint = Paint().apply { color = Color.rgb(0, 0, 0) }
    private val neutralGlyphBluePaint = Paint().apply { color = Color.rgb(40, 80, 200) }

    private val backgroundPaint = Paint().apply { color = Color.BLACK }

    // День/ночь (ТЗ раздел 14) — см. doc-комментарий у isDayMode в предыдущей версии: полный
    // цветовой негатив, КРОМЕ зелёных полос поезда и пиктограмм с фиксированными чертёжными
    // цветами (опасное место/щиты/воздушный промежуток/нейтральная вставка/табличка сигнала).
    private var isDayMode = false

    init {
        applyPalette()
    }

    private fun negate(color: Int): Int =
        Color.rgb(255 - Color.red(color), 255 - Color.green(color), 255 - Color.blue(color))

    private fun applyPalette() {
        fun c(nightColor: Int): Int = if (isDayMode) negate(nightColor) else nightColor

        backgroundPaint.color = c(Color.BLACK)
        stepPaint.color = c(Color.rgb(160, 159, 164))
        signalFieldPaint.color = c(Color.rgb(36, 36, 36))
        kmLinePaint.color = c(Color.rgb(178, 178, 178))
        picketLinePaint.color = c(Color.rgb(178, 178, 178))
        reliefFillPaint.color = c(Color.rgb(178, 178, 178))
        kmTextPaint.color = c(Color.WHITE)
        axisTextPaint.color = c(Color.rgb(178, 178, 178))
        stepNumberPaint.color = c(Color.WHITE)
        speedTextPaint.color = c(Color.WHITE)
        hatchLinePaint.color = c(Color.WHITE)
        hatchBorderPaint.color = c(Color.WHITE)
        restrictionNumberPaint.color = c(Color.WHITE)
        signalLinePaint.color = c(Color.WHITE)
        signalPolePaint.color = c(Color.WHITE)
        signalIconPaint.color = c(Color.WHITE)
        stationRingOuterPaint.color = c(Color.WHITE)
        stationHalfDarkPaint.color = c(Color.BLACK)
        stationInnerRingDarkPaint.color = c(Color.BLACK)
        stationInnerRingLightPaint.color = c(Color.WHITE)
        thinPerpendicularPaint.color = if (isDayMode) Color.argb(128, 55, 55, 55) else Color.argb(128, 200, 200, 200)
        thickPerpendicularPaint.color = c(Color.rgb(160, 159, 164))
        trainMarkerPaint.color = c(Color.rgb(160, 159, 164))
        // trainSignalStripePaint/trainReliefGreenPaint/trainReliefOutlinePaint, все
        // opasnoe*/shield*/airGap*/neutralGlyph*, и signalTagBg/Border/Text — сознательно НЕ
        // входят сюда, см. комментарий у isDayMode выше. trainMarkerPaint раньше тоже не
        // входил (был зелёным, фиксированным цветом) — после того как машинист попросил
        // сделать его обычным серым (как thickPerpendicularPaint), стал обычным цветом
        // интерфейса и тоже инвертируется днём/ночью.
    }

    fun setDayMode(day: Boolean) {
        isDayMode = day
        applyPalette()
        invalidate()
    }

    fun setSpeedLimits(limits: List<SpeedLimit>) {
        speedLimits = limits
        resolver = SpeedLimitResolver(limits)
        shortSegmentResolver = ShortSegmentSpeedResolver(limits)
        invalidate()
    }

    /** Эффективный датасет (DirectionSelection.effectiveDataset) — см. doc-комментарий у [direction]. */
    fun setDirection(newDirection: Direction) {
        direction = newDirection
        invalidate()
    }

    /** Направление движения как есть (DirectionSelection.direction) — только для нейтральной
     *  вставки, см. doc-комментарий у [rawDirection]. */
    fun setRawDirection(newDirection: Direction) {
        rawDirection = newDirection
        invalidate()
    }

    fun setTrainPositionM(meters: Double) {
        trainPositionM = meters
        invalidate()
    }

    /** Кнопка "моё местоположение" (см. doc-комментарий у panOffsetM) — то же самое, что
     *  происходит само через 10 секунд без касаний. */
    fun recenter() {
        panOffsetM = 0.0
        panRevertHandler.removeCallbacks(panRevertRunnable)
        invalidate()
    }

    /** Протягивание графика пальцем вперёд/назад — см. doc-комментарий у panOffsetM. Палец
     *  двигается вправо → открываем то, что было СЗАДИ (меньше км), поэтому окно обзора
     *  сдвигается НАЗАД — тот же принцип, что в любой карте/навигаторе ("тащишь карту"). */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                panRevertHandler.removeCallbacks(panRevertRunnable)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dxPx = event.x - lastTouchX
                lastTouchX = event.x
                val graphWidth = width - axisWidthPx
                if (graphWidth > 0f) {
                    val spanM = behindM + aheadM
                    panOffsetM -= dxPx * (spanM / graphWidth)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                panRevertHandler.postDelayed(panRevertRunnable, panRevertDelayMs)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                panRevertHandler.postDelayed(panRevertRunnable, panRevertDelayMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        panRevertHandler.removeCallbacks(panRevertRunnable)
        super.onDetachedFromWindow()
    }

    fun setTrainLengthM(meters: Double) {
        trainLengthM = meters
        invalidate()
    }

    fun setRestrictions(list: List<Restriction>) {
        restrictions = list
        invalidate()
    }

    fun setSignals(list: List<Signal>) {
        signals = list
        invalidate()
    }

    fun setStations(list: List<Station>) {
        stations = list
        invalidate()
    }

    fun setBlockPosts(list: List<BlockPost>) {
        blockPosts = list
        invalidate()
    }

    fun setRelief(profile: ReliefProfile) {
        relief = profile
        invalidate()
    }

    fun setCrossings(list: List<Crossing>) {
        crossings = list
        invalidate()
    }

    fun setAirGaps(list: List<AirGap>) {
        airGaps = list
        invalidate()
    }

    fun setNeutralInserts(list: List<NeutralInsert>) {
        neutralInserts = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val fromM = trainPositionM - behindM + panOffsetM
        val toM = trainPositionM + aheadM + panOffsetM
        val spanM = toM - fromM
        val graphLeft = axisWidthPx
        val graphWidth = width - axisWidthPx

        val stepsH = height * (STEPS_TO_RELIEF_RATIO / (STEPS_TO_RELIEF_RATIO + 1f))
        val lineY = stepsH
        val reliefTop = stepsH
        val reliefH = height - stepsH
        val scaleBottomY = stepsH * SCALE_BOTTOM_FRACTION

        fun xFor(meters: Double): Float = graphLeft + ((meters - fromM) / spanM * graphWidth).toFloat()
        // Обратное к xFor (x→m) — ОБЯЗАТЕЛЬНО со сдвигом на graphLeft, иначе рельеф/сетка/
        // зелёная полоса съезжают относительно ступеней и линии сигналов (та же ошибка уже
        // была в HTML-макете и там же исправлена, см. drawGridAndRelief/README).
        fun mFor(x: Float): Double = fromM + ((x - graphLeft) / graphWidth) * spanM
        fun yForSpeed(speedKmh: Int): Float =
            scaleBottomY * (1f - speedKmh.coerceIn(0, 100) / MAX_SPEED_KMH)

        // Тёмное "поле" под всей зоной ступеней — ступени и штриховки лягут поверх (ТЗ раздел 7).
        canvas.drawRect(0f, 0f, width.toFloat(), lineY, signalFieldPaint)

        val segments = computeSegments(fromM, toM)
        val stepRegionBottoms = drawSpeedSteps(canvas, segments, ::xFor, ::yForSpeed)
        drawRestrictionHatching(canvas, fromM, toM, ::xFor, ::yForSpeed)
        drawSpeedAxis(canvas, ::yForSpeed)
        drawSignalLineTicksAndNumbers(canvas, fromM, toM, ::xFor, lineY)
        drawTrainSignalStripe(canvas, ::xFor, lineY)
        drawNeutralInsertBar(canvas, fromM, toM, ::xFor, lineY)
        drawSignalTags(canvas, fromM, toM, ::xFor, lineY)

        val pictograms = buildPictograms(fromM, toM)
        val (headY, tailY) = drawReliefAndTrain(canvas, fromM, toM, ::xFor, ::mFor, reliefTop, reliefH, pictograms)

        drawPerpendiculars(canvas, fromM, toM, ::xFor, stepRegionBottoms, pictograms, headY, tailY)
        drawTrainMarker(canvas, ::xFor)
        drawCurrentSpeedLabel(canvas)
    }

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

    /**
     * Ступени + штриховка раздела 9 ("\", короткий участок повышенной скорости зажат между
     * двумя более медленными — состав не успевает разогнаться). "Короткий" и эффективная
     * (заниженная) скорость — не пересчитываются здесь заново, а берутся у уже готового и
     * протестированного [ShortSegmentSpeedResolver] (тот же, что уже использовался в V=/S=
     * статусной строки, см. README шаг 17) — чтобы не завести вторую, слегка другую версию
     * этой логики. Возвращает нижнюю границу занятой зоны каждого сегмента — для
     * перпендикуляров (см. doc-комментарий StepRegionBottom).
     *
     * Заливка ступени идёт СВЕРХУ ВНИЗ: от уровня скорости 100 (scaleTop) до значения самой
     * ступени (y) — а не наоборот, от значения до низа шкалы. Визуально это читается как
     * "серая зона — то, что ВЫШЕ разрешённой скорости", нижний край серого — это и есть сама
     * ступень (сверено с HTML-макетом, drawStepsAndSignalLine).
     */
    private fun drawSpeedSteps(
        canvas: Canvas,
        segments: List<Segment>,
        xFor: (Double) -> Float,
        yForSpeed: (Int) -> Float
    ): List<StepRegionBottom> {
        val result = mutableListOf<StepRegionBottom>()
        val scaleTop = yForSpeed(100)
        for (segment in segments) {
            val x0 = xFor(segment.startM)
            val x1 = xFor(segment.endM)
            val y = yForSpeed(segment.speedKmh)
            canvas.drawRect(x0, scaleTop, x1, y, stepPaint)

            val midM = (segment.startM + segment.endM) / 2.0
            val effective = shortSegmentResolver.effectiveSpeedAt(direction, midM, trainLengthM)

            var bottomY = y
            if (effective != null && effective.isShortSegment) {
                val capY = yForSpeed(effective.effectiveSpeedKmh)
                bottomY = capY
                drawHatch(canvas, x0, y, x1 - x0, capY - y, reverse = true)
                canvas.drawRect(x0, y, x1, capY, hatchBorderPaint)
                drawNumberWithOutline(canvas, effective.effectiveSpeedKmh.toString(), (x0 + x1) / 2f, (y + capY) / 2f)
            }
            result.add(StepRegionBottom(segment.startM, segment.endM, bottomY))

            if (x1 - x0 >= minSegmentPxForLabel) {
                val metrics = stepNumberPaint.fontMetrics
                val textY = (scaleTop + y) / 2f - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(segment.speedKmh.toString(), (x0 + x1) / 2f, textY, stepNumberPaint)
            }
        }
        return result
    }


    private fun drawNumberWithOutline(canvas: Canvas, text: String, cx: Float, cy: Float) {
        hatchNumberOutlinePaint.textSize = restrictionNumberPaint.textSize
        hatchNumberOutlinePaint.typeface = restrictionNumberPaint.typeface
        val metrics = restrictionNumberPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, cx, textY, hatchNumberOutlinePaint)
        canvas.drawText(text, cx, textY, restrictionNumberPaint)
    }

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
            if (x1 - x0 < pictogramMinHatchWidthPx) {
                val cx = (x0 + x1) / 2f
                x0 = cx - pictogramMinHatchWidthPx / 2f
                x1 = cx + pictogramMinHatchWidthPx / 2f
            }
            val yBottom = yForSpeed(r.speedKmh)

            drawHatch(canvas, x0, scaleTop, x1 - x0, yBottom - scaleTop, reverse = false)
            canvas.drawRect(x0, scaleTop, x1, yBottom, hatchBorderPaint)
            drawNumberWithOutline(canvas, r.speedKmh.toString(), (x0 + x1) / 2f, (scaleTop + yBottom) / 2f)
        }
    }

    /** reverse=false — "/" (временные ограничения), reverse=true — "\" (раздел 9). */
    private fun drawHatch(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, reverse: Boolean) {
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipRect(x, y, x + w, y + h)
        var d = -h
        while (d < w) {
            if (reverse) {
                canvas.drawLine(x + d, y, x + d + h, y + h, hatchLinePaint)
            } else {
                canvas.drawLine(x + d, y + h, x + d + h, y, hatchLinePaint)
            }
            d += dp(12f)
        }
        canvas.restore()
    }

    private fun drawSpeedAxis(canvas: Canvas, yForSpeed: (Int) -> Float) {
        for (speed in AXIS_SPEEDS) {
            val y = yForSpeed(speed)
            canvas.drawText(speed.toString(), dp(4f), y + dp(10f), axisTextPaint)
        }
    }

    /** Засечки пикетов/км и номера км — ПРЯМО на линии сигналов (не отдельной полосой-линейкой). */
    private fun drawSignalLineTicksAndNumbers(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        lineY: Float
    ) {
        val picketsPerKm = (KM_LINE_LENGTH_M / PICKET_LENGTH_M).toLong()
        val firstPicketIndex = ceil(fromM / PICKET_LENGTH_M).toLong()
        val lastPicketIndex = floor(toM / PICKET_LENGTH_M).toLong()
        for (i in firstPicketIndex..lastPicketIndex) {
            val picketM = i * PICKET_LENGTH_M
            val isKmLine = i % picketsPerKm == 0L
            val x = xFor(picketM)
            val tickH = if (isKmLine) dp(14f) else dp(7f)
            canvas.drawLine(x, lineY - tickH, x, lineY, if (isKmLine) kmLinePaint else picketLinePaint)
        }

        canvas.drawLine(0f, lineY, width.toFloat(), lineY, signalLinePaint)

        val firstKmIndex = floor(fromM / KM_LINE_LENGTH_M).toLong()
        val lastKmIndex = floor(toM / KM_LINE_LENGTH_M).toLong()
        for (kmIndex in firstKmIndex..lastKmIndex) {
            val centerM = kmIndex * KM_LINE_LENGTH_M + KM_LINE_LENGTH_M / 2
            if (centerM in fromM..toM) {
                canvas.drawText(kmIndex.toString(), xFor(centerM), lineY - dp(8f), kmTextPaint)
            }
        }
    }

    /** Серая полоска физической длины поезда НА линии сигналов (ТЗ раздел 7) + белая окантовка. */
    private fun drawTrainSignalStripe(canvas: Canvas, xFor: (Double) -> Float, lineY: Float) {
        if (trainLengthM <= 0.0) return
        val x0 = xFor(trainPositionM - trainLengthM)
        val x1 = xFor(trainPositionM)
        val w = max(1f, x1 - x0)
        canvas.drawRect(x0, lineY - dp(6f), x0 + w, lineY, trainSignalStripePaint)
        canvas.drawRect(x0, lineY - dp(6f), x0 + w, lineY, trainSignalStripeBorderPaint)
    }

    /** Нейтральная вставка — синяя полоса НА линии сигналов, от km+пк начала на реальную
     *  протяжённость [NeutralInsert.lengthM]; направление — [rawDirection] (см. класс). */
    private fun drawNeutralInsertBar(canvas: Canvas, fromM: Double, toM: Double, xFor: (Double) -> Float, lineY: Float) {
        for (n in neutralInserts) {
            if (n.direction != rawDirection) continue
            if (n.chainageM + n.lengthM < fromM || n.chainageM > toM) continue
            val x0 = xFor(n.chainageM)
            val x1 = xFor(n.chainageM + n.lengthM)
            val w = max(1f, x1 - x0)
            canvas.drawRect(x0, lineY - dp(5f), x0 + w, lineY, neutralBarPaint)
            canvas.drawRect(x0, lineY - dp(5f), x0 + w, lineY, neutralBarBorderPaint)
        }
    }

    /** Значки сигналов (капсула) и станций (кольцо) + таблички с расстоянием — настоящие
     *  данные ([signals] главного пути + [stations] + [blockPosts] как доп. сигналы).
     *  [Signal.effectiveDirection], а не "сырой" [Signal.direction] — см. doc-комментарий у
     *  effectiveDirection: сырой тег означает только "из какого PDF-документа", а не "к какому
     *  направлению реально относится этот светофор" (машинист прислал скриншот с сигналами
     *  обоих направлений одновременно на чётном пути — именно из-за использования сырого тега). */
    private fun drawSignalTags(canvas: Canvas, fromM: Double, toM: Double, xFor: (Double) -> Float, lineY: Float) {
        val tags = buildList {
            for (s in signals) if (s.effectiveDirection == direction) add(SignalTag(s.chainageM, s.name, false))
            for (b in blockPosts) if (b.direction == direction) add(SignalTag(b.chainageM, b.label, false))
            for (st in stations) if (st.direction == direction) add(SignalTag(st.chainageM, st.name, true))
        }.sortedBy { it.chainageM }

        val aheadSignalsOnly = tags.filter { !it.isStation && it.chainageM > trainPositionM }.sortedBy { it.chainageM }

        // Когда несколько сигналов стоят близко друг к другу, их таблички (рисуются вправо от
        // своего столбика) налезают друг на друга и становятся нечитаемыми — машинист прислал
        // скриншот как раз с таким наложением у Читы. Решение как у подписей на картах:
        // табличка, которая не помещается на базовой высоте рядом с предыдущей, "подрастает"
        // на уровень выше (столбик удлиняется), пока не перестанет с той пересекаться по
        // горизонтали. tierRightEdge[i] — правый край последней размещённой на уровне i
        // таблички; тэги уже отсортированы по км, поэтому одного прохода слева направо хватает.
        val tierRightEdge = mutableListOf<Float>()
        val tierGapPx = dp(6f)

        for (tag in tags) {
            if (tag.chainageM < fromM || tag.chainageM > toM) continue
            val x = xFor(tag.chainageM)

            val passed = tag.chainageM <= trainPositionM
            var distanceLabel = ""
            if (!passed) {
                val distance = if (tag.isStation) {
                    tag.chainageM - trainPositionM
                } else {
                    val idx = aheadSignalsOnly.indexOf(tag)
                    if (idx <= 0) tag.chainageM - trainPositionM else tag.chainageM - aheadSignalsOnly[idx - 1].chainageM
                }
                distanceLabel = " " + Math.round(distance)
            }
            val text = tag.label + distanceLabel

            val textW = signalTagTextPaint.measureText(text)
            // Коробка вокруг надписи пропорционально уменьшена вместе с самим текстом
            // (signalTagTextPaint 26dp→16dp) — иначе вокруг мелкого текста осталась бы
            // непропорционально широкая белая рамка.
            val boxW = textW + dp(8f)
            val boxH = dp(18f)

            var tier = 0
            while (tier < tierRightEdge.size && x < tierRightEdge[tier] + tierGapPx) tier++
            if (tier == tierRightEdge.size) tierRightEdge.add(x + boxW) else tierRightEdge[tier] = x + boxW

            val basePoleLen = if (tag.isStation) stationPolePx else signalPolePx
            val poleLen = basePoleLen + tier * (boxH + tierGapPx)
            val rOuter = if (tag.isStation) stationROuterPx else signalRyPx
            val poleTop = lineY - poleLen
            val iconCy = lineY - poleLen - rOuter

            canvas.drawLine(x, lineY, x, poleTop, signalPolePaint)

            if (tag.isStation) {
                drawStationIcon(canvas, x, iconCy, stationROuterPx, stationRInnerPx)
            } else {
                drawCapsule(canvas, x, iconCy, signalRxPx, signalRyPx)
            }

            val extraGap = if (tag.isStation) dp(7f) else 0f
            val tagBottom = iconCy - rOuter - dp(1.5f) - extraGap
            val tagTop = tagBottom - boxH

            canvas.drawLine(x, tagBottom, x, iconCy - rOuter, signalPolePaint)
            canvas.drawRect(x + dp(0.7f), tagTop + dp(0.7f), x + boxW - dp(0.7f), tagTop + boxH - dp(0.7f), signalTagBgPaint)
            canvas.drawRect(x, tagTop, x + boxW, tagTop + boxH, signalTagBorderPaint)
            canvas.drawText(text, x + dp(4f), tagTop + boxH - dp(5f), signalTagTextPaint)
        }
    }

    /** Капсула-стадион (rx < ry — вытянута вертикально, скруглена и сверху, и снизу): round-rect
     *  с радиусом скругления = rx (половина ширины) даёт ровно такую форму. */
    private fun drawCapsule(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        canvas.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, rx, rx, signalIconPaint)
    }

    /** Два концентрических кольца, разделённых пополам (ТЗ раздел 7): одна половина светлая
     *  с тёмным внутренним кольцом, другая — тёмная со светлым внутренним кольцом, плюс
     *  светлый внешний контур. "Светлый"/"тёмный" — через applyPalette (день/ночь), как и у
     *  остальной линии сигналов. */
    private fun drawStationIcon(canvas: Canvas, cx: Float, cy: Float, rOuter: Float, rInner: Float) {
        val bounds = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)

        canvas.save()
        val rightHalf = Path().apply { arcTo(bounds, -90f, 180f); close() }
        canvas.clipPath(rightHalf)
        canvas.drawRect(bounds, signalIconPaint)
        canvas.drawCircle(cx, cy, rInner, stationInnerRingDarkPaint)
        canvas.restore()

        canvas.save()
        val leftHalf = Path().apply { arcTo(bounds, 90f, 180f); close() }
        canvas.clipPath(leftHalf)
        canvas.drawRect(bounds, stationHalfDarkPaint)
        canvas.drawCircle(cx, cy, rInner, stationInnerRingLightPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, rOuter, stationRingOuterPaint)
    }

    /** Все пиктограммы в видимом окне, отсортированные по м — настоящие (переезды/возд.
     *  промежутки/нейтральные вставки) + динамические (ограничения, ТЗ раздел 11). Переезды
     *  визуально пока не дорисованы (нужен настоящий чертёж-PNG знака 1.1 ГОСТ Р 52290, см.
     *  README/чат) — данные загружаются, но в этот список намеренно не попадают. */
    private fun buildPictograms(fromM: Double, toM: Double): List<Pictogram> {
        val result = mutableListOf<Pictogram>()
        for (a in airGaps) if (a.direction == direction && a.chainageM in fromM..toM) {
            result.add(Pictogram(a.chainageM, PictogramKind.AIR_GAP))
        }
        for (n in neutralInserts) if (n.direction == rawDirection && n.chainageM in fromM..toM) {
            result.add(Pictogram(n.chainageM, PictogramKind.NEUTRAL))
        }
        for (r in restrictions) {
            val yellowM = r.startM - SHIELD_OFFSET_M
            val greenM = r.endM + SHIELD_OFFSET_M
            if (r.startM in fromM..toM) result.add(Pictogram(r.startM, PictogramKind.OPASNOE_START))
            if (r.endM in fromM..toM) result.add(Pictogram(r.endM, PictogramKind.OPASNOE_END))
            if (yellowM in fromM..toM) result.add(Pictogram(yellowM, PictogramKind.YELLOW_SHIELD))
            if (greenM in fromM..toM) result.add(Pictogram(greenM, PictogramKind.GREEN_SHIELD))
        }
        return result.sortedBy { it.chainageM }
    }

    /**
     * Рельеф (сетка пикетов + серая заливка) с панорамированием, зелёная полоса поезда вдоль
     * кривой, и пиктограммы на нём (два фиксированных места — низ/верх полосы, см. класс).
     * Возвращает (headY, tailY) — высоту рельефа у головы/хвоста поезда, для толстых
     * перпендикуляров.
     */
    private fun drawReliefAndTrain(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        mFor: (Float) -> Double,
        top: Float,
        bandH: Float,
        pictograms: List<Pictogram>
    ): Pair<Float, Float> {
        val hasCoverage = relief.hasCoverage(direction)
        val floorY = top + bandH * RELIEF_FLOOR_FRACTION
        // Привязка к МАКСИМУМУ видимого окна, не к минимуму (машинист про реальный экран:
        // "пересчитывается на каждую отрисовку от максимума в видимом окне" — см. HTML-макет).
        // Раньше здесь была привязка к минимуму без верхней границы — из-за этого высокий
        // участок рельефа мог нарисоваться ВЫШЕ линии сигналов, в чужой зоне графика (машинист
        // прислал скриншот именно такого наложения). Привязка к максимуму гарантирует обратное:
        // самая высокая точка всегда ровно у верхней границы полосы, ниже неё — никогда.
        val maxInView = if (hasCoverage) relief.maxHeightInRange(direction, fromM, toM) else null
        val ceilY = top + dp(6f)

        fun reliefY(m: Double): Float {
            val h = relief.heightAt(direction, m)
            return if (h != null && maxInView != null) {
                ceilY + ((maxInView - h) * reliefPxPerPt).toFloat()
            } else {
                // Вне покрытия (км5930-6604, README шаг 15) — ровная линия у пола полосы, а
                // не выдуманная синусоида (в отличие от HTML-макета — там это было нужно для
                // наглядности пропорций, здесь настоящему приложению это не нужно).
                floorY
            }
        }

        // заливка рельефа
        val fillPath = Path()
        fillPath.moveTo(0f, top + bandH)
        fillPath.lineTo(0f, reliefY(fromM))
        var x = 0f
        val stepPx = 16f
        val w = width.toFloat()
        while (x <= w) {
            fillPath.lineTo(x, reliefY(mFor(x)))
            x += stepPx
        }
        fillPath.lineTo(w, top + bandH)
        fillPath.close()
        canvas.drawPath(fillPath, reliefFillPaint)

        // сетка пикетов, клип по поверхности рельефа
        canvas.save()
        canvas.clipRect(0f, top, w, top + bandH)
        val firstPicketIndex = ceil(fromM / PICKET_LENGTH_M).toLong()
        val lastPicketIndex = floor(toM / PICKET_LENGTH_M).toLong()
        for (i in firstPicketIndex..lastPicketIndex) {
            val picketM = i * PICKET_LENGTH_M
            val px = xFor(picketM)
            canvas.drawLine(px, top, px, top + bandH, picketLinePaint)
        }
        val hLines = 4
        for (j in 1 until hLines) {
            val y = top + (bandH * 0.9f * j) / hLines
            canvas.drawLine(0f, y, w, y, picketLinePaint)
        }
        canvas.restore()

        // зелёная полоса поезда вдоль рельефа — от хвоста до головы, нижний край полосы = кривая.
        val headX0M = trainPositionM - trainLengthM
        fun traceTrainPath(strokeW: Float): Path {
            val path = Path()
            var started = false
            var px = 0f
            while (px <= w) {
                val m = mFor(px)
                if (m in headX0M..trainPositionM) {
                    val y = reliefY(m) - strokeW / 2f
                    if (!started) {
                        path.moveTo(px, y)
                        started = true
                    } else {
                        path.lineTo(px, y)
                    }
                }
                px += 4f
            }
            return path
        }
        if (trainLengthM > 0.0) {
            trainReliefOutlinePaint.strokeCap = Paint.Cap.SQUARE
            trainReliefGreenPaint.strokeCap = Paint.Cap.SQUARE
            canvas.drawPath(traceTrainPath(trainReliefOutlinePaint.strokeWidth), trainReliefOutlinePaint)
            canvas.drawPath(traceTrainPath(trainReliefGreenPaint.strokeWidth), trainReliefGreenPaint)
        }

        // пиктограммы — низ полосы (обычно) либо верх (если рельеф там слишком низкий)
        val bottomY = top + bandH - iconGapPx - iconHalfPx
        val topY = top + iconGapPx + iconHalfPx
        for (p in pictograms) {
            val px = xFor(p.chainageM)
            val fitsAtBottom = reliefY(p.chainageM) <= bottomY - iconHalfPx
            val py = if (fitsAtBottom) bottomY else topY
            drawPictogramGlyph(canvas, px, py, iconHalfPx, p.kind)
        }

        return reliefY(trainPositionM) to reliefY(headX0M)
    }

    private fun drawPictogramGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, kind: PictogramKind) {
        when (kind) {
            PictogramKind.OPASNOE_START -> drawOpasnoeGlyph(canvas, cx, cy, s, rotated = false)
            PictogramKind.OPASNOE_END -> drawOpasnoeGlyph(canvas, cx, cy, s, rotated = true)
            PictogramKind.YELLOW_SHIELD -> drawShieldGlyph(canvas, cx, cy, s, yellowShieldColor)
            PictogramKind.GREEN_SHIELD -> drawShieldGlyph(canvas, cx, cy, s, greenShieldColor)
            PictogramKind.AIR_GAP -> drawAirGapGlyph(canvas, cx, cy, s)
            PictogramKind.NEUTRAL -> drawNeutralGlyph(canvas, cx, cy, s)
        }
    }

    private fun drawOpasnoeGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, rotated: Boolean) {
        val ringW = s * (100f / 275f)
        val r = s - ringW / 2f
        canvas.save()
        canvas.translate(cx, cy)
        if (rotated) canvas.rotate(90f)

        canvas.drawCircle(0f, 0f, s, opasnoeWhitePaint)
        opasnoeRingPaint.strokeWidth = ringW
        canvas.drawCircle(0f, 0f, r, opasnoeRingPaint)
        canvas.drawRect(-s, -ringW / 2f, s, ringW / 2f, opasnoeBlackPaint)

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
        val halfMm = 235f
        val borderMm = 25f
        val inset1 = s * (borderMm / halfMm)
        val inset2 = s * ((borderMm * 2f) / halfMm)
        canvas.drawRect(cx - s, cy - s, cx + s, cy + s, shieldWhitePaint)
        canvas.drawRect(cx - s + inset1, cy - s + inset1, cx + s - inset1, cy + s - inset1, shieldBlackPaint)
        shieldColorPaint.color = fillColor
        canvas.drawRect(cx - s + inset2, cy - s + inset2, cx + s - inset2, cy + s - inset2, shieldColorPaint)
    }

    /** 3×7 клеток, строки 1/3/5/7 (индексы 0,2,4,6) чёрные во всю ширину + сплошная чёрная
     *  средняя колонка сверху донизу (машинист, со слов — не по чертежу с размерами). */
    private fun drawAirGapGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val rows = 7
        val h = s * 2f
        val cellH = h / rows
        val cellW = cellH
        val w = cellW * 3
        val left = cx - w / 2f
        val top = cy - h / 2f

        canvas.drawRect(left, top, left + w, top + h, airGapWhitePaint)
        for (row in 0 until rows) {
            if (row % 2 != 0) continue
            canvas.drawRect(left, top + row * cellH, left + w, top + (row + 1) * cellH, airGapBlackPaint)
        }
        canvas.drawRect(left + cellW, top, left + 2 * cellW, top + h, airGapBlackPaint)
        canvas.drawRect(left, top, left + w, top + h, airGapBorderPaint)
    }

    /** Ромб "включить ток" (ГОСТ 8442-65) — синяя заливка, чёрная окантовка, серый контур
     *  снаружи; внутри 2 белых скруглённых квадрата сверху + 1 пошире снизу, все пустые. */
    private fun drawNeutralGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx, cy - s - 1.5f)
        path.lineTo(cx + s + 1.5f, cy)
        path.lineTo(cx, cy + s + 1.5f)
        path.lineTo(cx - s - 1.5f, cy)
        path.close()
        canvas.drawPath(path, neutralGlyphOuterPaint)

        fun diamond(r: Float, paint: Paint) {
            val p = Path()
            p.moveTo(cx, cy - r)
            p.lineTo(cx + r, cy)
            p.lineTo(cx, cy + r)
            p.lineTo(cx - r, cy)
            p.close()
            canvas.drawPath(p, paint)
        }
        val inradius = s / 1.41421356f
        fun insetR(mm: Float): Float {
            val sideLen = s * 1.41421356f
            val dUnits = mm * (sideLen / 450f)
            return s * (1f - dUnits / inradius)
        }
        diamond(s, neutralGlyphWhitePaint)
        diamond(insetR(20f), neutralGlyphBlackPaint)
        diamond(insetR(40f), neutralGlyphBluePaint)

        val sideLen = s * 1.41421356f
        val sq = sideLen * (90f / 450f)
        val gap = sideLen * (80f / 450f)
        val contentW = sideLen * (260f / 450f)
        val cornerR = sideLen * (40f / 450f)
        val barH = sq * 0.6f
        val totalH = sq + gap + barH
        val sqTop = cy - totalH / 2f
        val barTop = sqTop + sq + gap
        canvas.drawRoundRect(cx - gap / 2f - sq, sqTop, cx - gap / 2f, sqTop + sq, cornerR, cornerR, neutralGlyphWhitePaint)
        canvas.drawRoundRect(cx + gap / 2f, sqTop, cx + gap / 2f + sq, sqTop + sq, cornerR, cornerR, neutralGlyphWhitePaint)
        canvas.drawRoundRect(cx - contentW / 2f, barTop, cx + contentW / 2f, barTop + barH, cornerR, cornerR, neutralGlyphWhitePaint)
    }

    /**
     * Тонкие перпендикуляры — от каждой пиктограммы и каждой границы ступеней, СНИЗУ (от
     * низа графика) РОВНО до занятой зоны ступени ([StepRegionBottom.bottomY]) — сквозь
     * заливку/штриховку не проходят (машинист). Толстые — проекция головы/хвоста поезда,
     * от занятой зоны своей ступени до высоты рельефа ([headY]/[tailY]).
     */
    private fun drawPerpendiculars(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        stepRegionBottoms: List<StepRegionBottom>,
        pictograms: List<Pictogram>,
        headY: Float,
        tailY: Float
    ) {
        fun regionBottomAt(m: Double): Float =
            stepRegionBottoms.firstOrNull { m >= it.startM && m <= it.endM }?.bottomY ?: 0f

        val h = height.toFloat()
        for (p in pictograms) {
            val x = xFor(p.chainageM)
            canvas.drawLine(x, regionBottomAt(p.chainageM), x, h, thinPerpendicularPaint)
        }
        for (i in 1 until stepRegionBottoms.size) {
            val boundaryM = stepRegionBottoms[i].startM
            if (boundaryM < fromM || boundaryM > toM) continue
            val topY = max(stepRegionBottoms[i - 1].bottomY, stepRegionBottoms[i].bottomY)
            canvas.drawLine(xFor(boundaryM), topY, xFor(boundaryM), h, thinPerpendicularPaint)
        }

        val tailM = trainPositionM - trainLengthM
        val headX = xFor(trainPositionM)
        val tailX = xFor(tailM)
        canvas.drawLine(headX, regionBottomAt(trainPositionM), headX, headY, thickPerpendicularPaint)
        canvas.drawLine(tailX, regionBottomAt(tailM), tailX, tailY, thickPerpendicularPaint)
    }

    private fun drawTrainMarker(canvas: Canvas, xFor: (Double) -> Float) {
        val headX = xFor(trainPositionM)
        canvas.drawLine(headX, 0f, headX, height.toFloat(), trainMarkerPaint)
    }

    private fun drawCurrentSpeedLabel(canvas: Canvas) {
        val activeRestriction = restrictions.firstOrNull { trainPositionM in it.startM..it.endM }
        val speedKmh = activeRestriction?.speedKmh ?: resolver.speedAt(direction, trainPositionM)?.speedKmh
        val text = if (speedKmh != null) "V=$speedKmh км/ч" else "V=?"
        canvas.drawText(text, axisWidthPx, dp(40f), speedTextPaint)
    }
}
