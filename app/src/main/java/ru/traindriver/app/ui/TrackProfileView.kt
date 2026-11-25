package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import ru.traindriver.app.route.AirGap
import ru.traindriver.app.route.BlockPost
import ru.traindriver.app.route.Crossing
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.NeutralInsert
import ru.traindriver.app.route.Restriction
import ru.traindriver.app.route.ReliefProfile
import ru.traindriver.app.route.ShortSegmentSpeedResolver
import ru.traindriver.app.route.Signal
import ru.traindriver.app.route.isVisibleFor
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitResolver
import ru.traindriver.app.R
import ru.traindriver.app.route.Station
import ru.traindriver.app.route.BrakeTest
import ru.traindriver.app.route.Ktsm
import ru.traindriver.app.route.Washout

/**
 * Срез главного экрана (ТЗ раздел 7) — километровая/пикетная линейка, ступенчатый график
 * постоянных ограничений скорости, линия сигналов и профиль рельефа (ТЗ раздел 7/8/9/15),
 * портировано из HTML-макета "Ступени Хилока" (см. чат).
 *
 * Два яруса: верх — от верха блоков скорости до линии сигналов (серая шкала скорости + тёмное
 * "поле" с иконками/табличками сигналов + сама линия сигналов/км), низ — под линией сигналов,
 * где сетка пикетов и рельеф. km-линейка/засечки рисуются ПРЯМО на линии сигналов, отдельной
 * полосы-линейки больше нет.
 *
 * Границу между ярусами (computeStepsH) машинист попросил СЭКОНОМИТЬ: "можем выиграть
 * расстояние... увеличить площадь, где профиль, чтобы там было больше места по высоте" — серая
 * шкала скорости держит свой прежний фиксированный размер (см. grayScaleHeightPx, та же
 * пропорция, что раньше давало старое деление верх:низ=2,5:1 с SCALE_BOTTOM_FRACTION=0,62
 * внутри него), а тёмное поле ниже нёё теперь РОВНО по содержимому (см. darkFieldHeightPx) — не
 * фиксированная доля экрана, как раньше. Освободившаяся высота достаётся полосе рельефа.
 *
 * Сам этот View — только средняя часть экрана; высота самого View относительно шапки и нижней
 * статусной строки задаётся весами LinearLayout в activity_main.xml.
 *
 * Раздел 9 ("короткий участок повышенной скорости") — эффективная скорость считается
 * [ShortSegmentSpeedResolver] (уже было), здесь дорисована зеркальная штриховка "\".
 *
 * Пиктограммы (переезды/воздушные промежутки/нейтральные вставки/опасное место/щиты) сидят
 * прямо на графике рельефа, привязаны к самой КРИВОЙ в своей точке (не к фиксированной высоте
 * полосы): обычно чуть НИЖЕ кривой (минимальный зазор, см. drawReliefAndTrain), а если снизу не
 * остаётся места до низа полосы — чуть ВЫШЕ кривой, с запасом над зелёной полосой поезда, чтобы
 * та не закрывала значок, если поезд окажется в этой точке.
 *
 * Все пиктограммы (опасное место начало/конец, жёлтый/зелёный щит, КТСМ, проба тормозов,
 * нейтральная вставка, воздушный промежуток, переезд охраняемый/неохраняемый) и значок сигнала
 * главного пути (на линии сигналов, см. drawSignalTags) — настоящие присланные PNG
 * (см. drawBitmapCentered), не рисуются вручную. Блок-посты (пока без поля "какой сигнал
 * заменён") — см. README/чат.
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

        // Высота серой шкалы скорости (0..100) — ФИКСИРОВАННАЯ доля высоты всего View, та же
        // величина, что раньше получалась при жёстком делении верх:низ=2,5:1 (см. историю ниже)
        // и SCALE_BOTTOM_FRACTION=0,62 внутри него: height*(2,5/3,5)*0,62 ≈ height*0,443 — сам
        // вид шкалы не менялся, см. grayScaleHeightPx().
        private const val STEPS_TO_RELIEF_RATIO = 2.5f
        private const val SCALE_BOTTOM_FRACTION = 0.62f
        // Высота таблички сигнала/станции (общая для обеих, станция отличается только значком,
        // не подписью) — вынесена в константу, чтобы одна и та же цифра использовалась и при
        // рисовании (drawSignalTags), и при расчёте нужной высоты тёмного поля (см.
        // darkFieldHeightPx) — раньше было отдельное "val boxH = dp(18f)" только в drawSignalTags.
        private const val SIGNAL_TAG_BOX_HEIGHT_DP = 18f

        private const val SHIELD_OFFSET_M = 1000.0

        // Панорамирование: самая низкая точка видимого окна — у пола полосы рельефа, а не
        // выше (машинист). floorY = reliefTop + reliefH * RELIEF_FLOOR_FRACTION.
        private const val RELIEF_FLOOR_FRACTION = 0.92f

        // Фиксированный масштаб рельефа (метров настоящей высоты на всю высоту полосы) — см.
        // doc-комментарий у reliefY. Подобран по статистике реальных данных (track_relief.json):
        // в видимом окне (behindM+aheadM) перепад высоты ОТНОСИТЕЛЬНО высоты самого поезда в
        // большинстве случаев (90-й процентиль что вверх, что вниз) не превышает ~18м в каждую
        // сторону — 36м на обе стороны даёт типичный рельеф без обрезания, и лишь по-настоящему
        // крутые перевалы (Яблоновый и т.п.) уходят за пределы полосы — машинист разрешил именно
        // это ("это не страшно").
        private const val FIXED_RELIEF_RANGE_M = 36.0

        // Все размеры ниже — в dp (условных единицах, не зависящих от плотности экрана
        // конкретного телефона), переводятся в настоящие пиксели через dp() при создании
        // View (см. density/dp ниже). Раньше здесь лежали "голые" числа пикселей, подобранные
        // на глаз по HTML-макету на экране компьютера — на настоящем телефоне с другой
        // плотностью экрана они оказались почти нечитаемо мелкими (машинист: "там вообще
        // ничего не видно"). Значения увеличены и переведены в dp, чтобы исправить это разом.
        private const val MIN_SEGMENT_DP_FOR_LABEL = 50f
        private const val PICTOGRAM_MIN_HATCH_WIDTH_DP = 6f

        // Метка места (см. doc-комментарий у headMarkers): исчезает, когда хвост поезда отъедет
        // от неё на 100 метров — машинист сам продиктовал это расстояние ("проезжает ещё 100
        // метров, и она исчезает"). Допуск тапа по горизонтали — "около головы", не точно в
        // пиксель по тонкой линии, иначе попасть пальцем почти невозможно.
        private const val HEAD_MARKER_CLEAR_M = 100.0
        // 40dp → 90dp — машинист: "попасть в него пальцем очень тяжело". Вертикально допуск
        // и так уже широкий (вся верхняя зона графика, от потолка до линии сигналов, см.
        // maybeAddHeadMarker) — тесно было именно по горизонтали, вокруг тонкой линии головы.
        private const val HEAD_MARKER_TAP_TOLERANCE_DP = 90f

        // Пиктограммы на рельефе — место у кривой, ниже или выше (см. doc-комментарий класса).
        private const val ICON_HALF_DP = 16f
        private const val ICON_GAP_DP = 6f

        // Геометрия значка станции (цветная полоска, см. drawStationStripe — машинист: "убери
        // эти кружки... ставишь вот такую полоску") и высоты столбика сигнала — те же пропорции,
        // что в HTML-макете, но крупнее в dp; станция крупнее сигнала в 7/6 раза по полной
        // высоте, ножка:радиус = 50:45 — оба выводятся из этого отношения, а не масштабируются от
        // сигнала по отдельности (см. HTML-макет). STATION_R_OUTER_DP теперь задаёт только
        // ПОЛОВИНУ ШИРИНЫ полоски (раньше — радиус кольца), её высоту см. у
        // STATION_STRIPE_HEIGHT_RATIO. Ширина самого значка сигнала (было rx=7 у рисованной
        // капсулы) больше не задаётся отдельной константой — у присланной картинки сигнала своя
        // собственная пропорция, см. drawBitmapCentered.
        private const val SIGNAL_RY_DP = 10f
        private const val SIGNAL_POLE_DP = 12f
        private const val SIGNAL_TOTAL_H_DP = SIGNAL_RY_DP * 2f + SIGNAL_POLE_DP
        private const val STATION_TOTAL_H_DP = SIGNAL_TOTAL_H_DP * 7f / 6f
        private const val STATION_R_OUTER_DP = STATION_TOTAL_H_DP / (2f + 50f / 45f)
        private const val STATION_POLE_DP = STATION_R_OUTER_DP * (50f / 45f)
        // Полоска станции — плоский прямоугольник, а не круг: высота — доля от её ширины
        // (2×STATION_R_OUTER_DP), подобрана просто "на глаз" под присланный образец (длинная
        // тонкая полоска), а не точная пропорция самой картинки-образца (та была 261×27px —
        // в масштабе значка на графике это было бы почти незаметной линией).
        private const val STATION_STRIPE_HEIGHT_RATIO = 0.4f

        // Пропорции пояса поезда (см. trainStripeHeightPx/drawTrainSignalStripe) — машинист
        // продиктовал по эталонному поезду: электровоз "Ермак" 3ЭС5К + состав 71 условная
        // единица (условная единица пути — стандартная ж/д величина, 14м/единица) ≈ 994м. При
        // таком поезде, на БАЗОВОМ (не текущем) масштабе экрана (behindM+aheadM по умолчанию,
        // см. REFERENCE_SPAN_M), высота пояса должна относиться к его длине как 4:25 (машинист
        // замерил на глаз: "если 25мм, то высота 4"). Чтобы высота не "гуляла" вместе с текущим
        // зумом/длиной состава ("если он меньше будет, высота должна быть такая же"), она
        // пересчитывается ОДИН раз от эталонного поезда на БАЗОВОМ масштабе, а не от фактической
        // ширины полоски на экране.
        private const val REFERENCE_TRAIN_LENGTH_M = 994.0 // 71 × 14м
        private const val REFERENCE_SPAN_M = 2500.0 + 5500.0 // поумолчанию behindM+aheadM
        private const val TRAIN_STRIPE_HEIGHT_RATIO = 4.0 / 25.0

        // Светофор должен быть ровно в 2 раза выше пояса поезда (машинист по скриншоту:
        // "светофор должен быть ровно в 2 раза выше этого поезда"). См. использование
        // trainStripeHeight в drawSignalTags — внутренние пропорции самого значка сигнала
        // (rx:ry:столбик и станция:сигнал) при этом остаются теми же, что заданы
        // SIGNAL_*_DP/STATION_*_DP выше, просто масштабируются целиком под новую полную высоту.
        private const val SIGNAL_TO_TRAIN_HEIGHT_RATIO = 2f
        // Километровый столбик НИЖЕ светофора — машинист дал точную пропорцию: "если светофор
        // 4,3мм, то км столбик 3,7мм" (см. kmTickH в drawSignalLineTicksAndNumbers).
        private const val KM_TICK_TO_SIGNAL_HEIGHT_RATIO = 3.7f / 4.3f
    }

    private data class Segment(val startM: Double, val endM: Double, val speedKmh: Int)

    /** Нижняя граница "занятой" зоны ступени — низ обычной заливки, либо низ штриховки
     *  раздела 9, если она здесь есть (см. [drawSpeedSteps]). Перпендикуляры стартуют отсюда. */
    private data class StepRegionBottom(val startM: Double, val endM: Double, val bottomY: Float)

    /** Один сигнал на линии сигналов — настоящий (из track_signals.json/блок-постов) либо
     *  станция (крупнее, другой значок). */
    private data class SignalTag(val chainageM: Double, val label: String, val isStation: Boolean)

    private data class Pictogram(val chainageM: Double, val kind: PictogramKind)

    private enum class PictogramKind {
        OPASNOE_START, OPASNOE_END, YELLOW_SHIELD, GREEN_SHIELD, AIR_GAP, NEUTRAL,
        CROSSING_GUARDED, CROSSING_UNGUARDED, BRAKE_TEST, KTSM
    }

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
    // Табличка сигнала на НЕПРАВИЛЬНОМ пути — машинист: "по неправильному пути... сигналы
    // обозначаются: например в чётном направлении по непр пути 13 (I) - 200" — римскими
    // цифрами ФИЗИЧЕСКИЙ путь (не "правильный" путь направления, а именно physicalPath, тот,
    // на котором машинист реально сейчас едет), в скобках сразу после номера/литеры сигнала,
    // перед тире и расстоянием. См. setPathStatus/romanPathSuffix.
    private var isWrongPath: Boolean = false
    private var physicalPath: Int = 1
    private var trainPositionM: Double = 0.0
    // Факт. скорость (Vф, с GPS/одометра) — крупно в левом верхнем углу графика, на месте
    // бывшей шкалы-линейки (машинист: "эта линейка нам зачем, если скорости на графике и так
    // подписаны у каждой ступени" — убрали линейку, см. drawCurrentSpeedLabel, и освободившееся
    // место отдали самому важному показателю, который раньше был только маленьким текстом в
    // нижней строке статуса).
    private var actualSpeedKmh: Double? = null

    private var restrictions: List<Restriction> = emptyList()
    private var trainLengthM: Double = 0.0

    private var signals: List<Signal> = emptyList()
    private var stations: List<Station> = emptyList()
    private var blockPosts: List<BlockPost> = emptyList()
    private var relief: ReliefProfile = ReliefProfile(emptyList())
    private var crossings: List<Crossing> = emptyList()
    private var airGaps: List<AirGap> = emptyList()
    private var neutralInserts: List<NeutralInsert> = emptyList()
    private var washouts: List<Washout> = emptyList()
    private var brakeTests: List<BrakeTest> = emptyList()
    private var ktsmPoints: List<Ktsm> = emptyList()

    // Метки места (машинист, не из ТЗ/макета): тап около головы поезда в верхней зоне (где
    // сигналы) ставит метку ровно на ТЕКУЩЕМ месте головы — например, прямо на стрелке, когда
    // поезд её проезжает. Метка остаётся на этом месте пути (не едет с поездом), а исчезает
    // сама, когда хвост поезда отъедет от неё на [HEAD_MARKER_CLEAR_M] метров — машинист: "вижу,
    // что хвост выехал, можно увеличить скорость". См. maybeAddHeadMarker/pruneHeadMarkers/
    // drawHeadMarkers.
    private data class HeadMarker(val positionM: Double)
    private val headMarkers = mutableListOf<HeadMarker>()

    // Позади головы поезда — 2,5 км (было 3), машинист попросил сдвинуть голову левее по
    // экрану, чтобы было больше видно впереди. ВАЖНО: сэкономленные 500 м добавлены именно
    // ВПЕРЁД (5000→5500), а не просто убраны сзади — иначе общий охват графика (behindM+aheadM)
    // уменьшился бы, и впереди по-прежнему было бы видно те же самые 5 км, только крупнее
    // (сильнее "увеличенными"), а не больше километров пути, как просил машинист.
    var behindM: Double = 2500.0
    var aheadM: Double = 5500.0

    /** Реальный видимый диапазон пикетажа [fromM, toM] (fromM < toM ВСЕГДА, настоящие км/пк —
     *  как и везде в этом файле при проверках "chainageM in fromM..toM") плюс признак,
     *  перевёрнут ли экран. Машинист: "когда я включаю нечётный путь... километры всё равно
     *  идут на возрастание... как я сейчас поеду?" — раньше график ВСЕГДА считал "вперёд"
     *  (вправо на экране, где по умолчанию больше места — aheadM > behindM) направлением
     *  РОСТА реального км, независимо от того, куда машинист реально едет. Для нечётного
     *  направления движения (rawDirection == ODD) поезд едет в сторону УМЕНЬШЕНИЯ реального
     *  км (например, из Чернышевска в Хилок) — значит "вперёд" должно соответствовать МЕНЬШЕМУ
     *  км, а километровые цифры — убывать слева направо, а не расти. panOffsetM (протягивание
     *  пальцем, см. onTouchEvent) прибавляется к обеим границам ОДИНАКОВО в обоих случаях —
     *  знак его собственного смещения при протягивании подбирается отдельно в onTouchEvent. */
    private fun visibleWindow(): Triple<Double, Double, Boolean> {
        val isReversed = rawDirection == Direction.ODD
        return if (isReversed) {
            Triple(trainPositionM - aheadM + panOffsetM, trainPositionM + behindM + panOffsetM, true)
        } else {
            Triple(trainPositionM - behindM + panOffsetM, trainPositionM + aheadM + panOffsetM, false)
        }
    }

    /** +1 для чётного направления движения, -1 для нечётного — та же логика, что уже
     *  используется в MainActivity.estimatedChainageM (DirectionSelection.picketsGrowing: "растут
     *  пикеты или убывают... зависит только от направления"). Хвост поезда всегда "позади"
     *  головы В СМЫСЛЕ ДВИЖЕНИЯ: для чётного это МЕНЬШИЙ реальный км (как считалось раньше), для
     *  нечётного — БОЛЬШИЙ (хвост ещё не доехал до того места, откуда уже уехала голова, а
     *  едут они в сторону УМЕНЬШЕНИЯ км). */
    private val directionSignM: Double get() = if (rawDirection == Direction.ODD) -1.0 else 1.0

    /** Реальный км хвоста поезда — см. doc-комментарий у [directionSignM]. Используется везде
     *  в этом файле вместо прежнего "trainPositionM - trainLengthM" (которое подразумевало
     *  только чётное направление). */
    private val trainTailM: Double get() = trainPositionM - directionSignM * trainLengthM

    // Протягивание графика пальцем вперёд/назад (машинист, не из ТЗ/макета — "как в
    // навигаторах", посмотреть, что там впереди). panOffsetM сдвигает окно обзора целиком
    // (верхнюю часть со ступенями/сигналами и нижний рельеф — ОДНИМ и тем же числом, поэтому
    // они всегда протягиваются синхронно, см. fromM/toM в onDraw). Если 10 секунд не трогать —
    // само возвращается на голову поезда (см. panRevertRunnable); кнопка "моё местоположение"
    // (recenter()) делает то же самое сразу.
    private var panOffsetM: Double = 0.0
    private var lastTouchX: Float = 0f
    // Нужны, чтобы отличить настоящий тап (для метки места, см. headMarkers) от протягивания
    // пальцем — ACTION_UP срабатывает в обоих случаях одинаково, разница только в том, двигался
    // ли палец между ACTION_DOWN и ACTION_UP больше чем на HEAD_MARKER_TAP_TOLERANCE_DP.
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var touchMoved: Boolean = false
    private val panRevertHandler = Handler(Looper.getMainLooper())
    private val panRevertRunnable = Runnable { recenter() }
    private val panRevertDelayMs = 10_000L

    // Плотность экрана конкретного телефона — все размеры в companion object записаны в dp
    // (условных единицах) и переводятся в настоящие пиксели здесь, один раз, при создании
    // View. Должно идти РАНЬШЕ всех Paint-полей ниже, иначе dp() в их инициализаторах
    // отработает по ещё не заполненному density (см. doc-комментарий класса/README).
    private val density: Float = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val tapTolerancePx = dp(10f)
    private val headMarkerTapTolerancePx = dp(HEAD_MARKER_TAP_TOLERANCE_DP)
    private val minSegmentPxForLabel = dp(MIN_SEGMENT_DP_FOR_LABEL)
    private val pictogramMinHatchWidthPx = dp(PICTOGRAM_MIN_HATCH_WIDTH_DP)
    private val iconHalfPx = dp(ICON_HALF_DP)
    private val iconGapPx = dp(ICON_GAP_DP)
    // Минимальный зазор по X, при котором два значка на рельефе ещё не легли бы вплотную друг
    // на друга — используется и чтобы развести по двум вертикальным местам соседние значки из
    // РАЗНЫХ источников (см. drawReliefAndTrain — машинист нашёл баг: постоянный значок пробы
    // тормозов пропадал, пока рядом было активно временное ограничение, т.к. его значок
    // рисовался поверх в тех же пикселях и полностью его закрывал), и чтобы решить, стоит ли
    // вообще показывать "конец опасного места" у короткого ограничения (см. buildPictograms).
    private val minPictogramGapPx = iconHalfPx * 2f + iconGapPx

    // Настоящие картинки сигнала/пиктограмм (машинист прислал PNG вместо того, что раньше
    // рисовалось вручную через Canvas — капсула-"стадион" для сигнала, нарисованные от руки
    // щиты/КТСМ/НТ и т.п.). Грузятся один раз при создании View, а не на каждый drawXxxGlyph —
    // decodeResource на каждый кадр заметно нагрузил бы отрисовку. День/ночь эти картинки не
    // инвертирует (так же, как раньше не инвертировались их рисованные предшественники —
    // opasnoe*/shield*/airGap*/neutralGlyph* в applyPalette, см. комментарий там).
    private val signalBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_signal)
    private val opasnoeStartBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_opasnoe_start)
    private val opasnoeEndBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_opasnoe_end)
    private val yellowShieldBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_yellow_shield)
    private val greenShieldBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_green_shield)
    private val ktsmBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_ktsm)
    private val brakeTestBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_brake_test)
    private val neutralBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_neutral)
    private val airGapBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_air_gap)
    // Переезд (ГОСТ Р 52290, знаки 1.1/1.2) — машинист прислал оба позже, отдельным сообщением;
    // раньше рисовались вручную (drawCrossingGlyph/drawFenceGlyph/drawLocomotiveGlyph).
    private val crossingGuardedBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_crossing_guarded)
    private val crossingUnguardedBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_crossing_unguarded)
    // Обрывное место — шарик+стрелка, присланы одной картинкой, уже "смотрящей" вниз (шарик
    // сверху, остриё снизу). См. drawWashoutGlyph про то, как её переворачивают, когда стрелка
    // должна стоять СНИЗУ и указывать вверх.
    private val washoutBitmap = BitmapFactory.decodeResource(resources, R.drawable.pic_washout)
    private val bitmapPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

    /** Рисует [bitmap] с центром в (cx,cy), высотой 2×[targetHalfHeight], ширина — по
     *  собственной пропорции картинки (не растягиваем в чужой прямоугольник, иначе фигура
     *  искажается — у присланных PNG соотношение сторон у каждого своё). */
    private fun drawBitmapCentered(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, targetHalfHeight: Float) {
        val targetH = targetHalfHeight * 2f
        val targetW = targetH * (bitmap.width.toFloat() / bitmap.height.toFloat())
        val dst = RectF(cx - targetW / 2f, cy - targetH / 2f, cx + targetW / 2f, cy + targetH / 2f)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

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
    // Метка места (см. doc-комментарий у headMarkers) — красная линия вверх от головы в
    // момент тапа + значок-"капля" (как в Google Картах) над её верхним концом. Цвет красный
    // ЗАФИКСИРОВАН (не зависит от applyPalette/день-ночь) — это предупреждающая отметка, она
    // должна выглядеть одинаково в любом режиме, а не менять цвет вместе с темой.
    private val headMarkerLinePaint = Paint().apply {
        color = Color.RED
        strokeWidth = dp(3f)
    }
    private val headMarkerPinPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val headMarkerPinHolePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
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
        color = Color.rgb(31, 220, 11)
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
    // Намного меньше, чем было (54dp→26dp) — машинист попросил, число скорости на ступени
    // занимало слишком много места, но должно оставаться читабельным (в отличие от
    // km/сигнальных подписей, это не упало до размера часов).
    private val stepNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(26f)
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
    // Крупнее и жирным — Vф (факт. скорость) машинист попросил сделать самым заметным
    // показателем на экране ("самый важный... крупными буквами, чтобы я его хорошо видел").
    private val actualSpeedTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(48f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }
    // Окошко вокруг Vф — непрозрачный чёрный фон с тонкой белой каёмкой (машинист: "не просто
    // цифру, а окно... чтобы она не перекрывалась ничем, не было линий"). Рисуется ПОСЛЕДНИМ в
    // onDraw (см. drawCurrentSpeedLabel), поэтому фон гарантированно перекрывает всё, что
    // нарисовано под ним (сетку, ступени, сигналы), а не только зрительно совпадает по месту.
    private val speedWindowBgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val speedWindowBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        isAntiAlias = true
    }

    // Штриховка временных ограничений (ТЗ раздел 11, "/") И короткого участка раздела 9 ("\",
    // reverse) — тот же угол 45°, в разные стороны, см. [drawHatch]. Машинист: поле ограничения
    // должно быть серым (на тон светлее самой ступени скорости, чтобы отличалось, но было
    // видно, что это то же семейство), а штриховка внутри — тоже серой, темнее этого поля, а
    // не белыми линиями (раньше штриховка шла прямо по тёмному фону без заливки вообще).
    private val restrictionFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val hatchLinePaint = Paint().apply {
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
    // Значок станции — цветная полоска (машинист прислал цвет отдельно, вместо прежних колец),
    // см. drawStationStripe. Фиксированный цвет — не входит в applyPalette (как и нейтральная
    // вставка/пояс поезда, см. комментарий у isDayMode про "чертёжные цвета").
    private val stationStripePaint = Paint().apply { color = Color.rgb(15, 103, 255) }
    private val stationStripeBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
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
    private val neutralBarPaint = Paint().apply { color = Color.rgb(0, 0, 255) }
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
        stepNumberPaint.color = c(Color.WHITE)
        speedTextPaint.color = c(Color.WHITE)
        actualSpeedTextPaint.color = c(Color.WHITE)
        speedWindowBgPaint.color = c(Color.BLACK)
        speedWindowBorderPaint.color = c(Color.WHITE)
        restrictionFillPaint.color = c(Color.rgb(195, 194, 199))
        hatchLinePaint.color = c(Color.rgb(108, 107, 112))
        hatchBorderPaint.color = c(Color.WHITE)
        restrictionNumberPaint.color = c(Color.WHITE)
        signalLinePaint.color = c(Color.WHITE)
        signalPolePaint.color = c(Color.WHITE)
        thinPerpendicularPaint.color = if (isDayMode) Color.argb(128, 55, 55, 55) else Color.argb(128, 200, 200, 200)
        thickPerpendicularPaint.color = c(Color.rgb(160, 159, 164))
        trainMarkerPaint.color = c(Color.rgb(160, 159, 164))
        // trainSignalStripePaint/trainReliefGreenPaint/trainReliefOutlinePaint,
        // neutralBarPaint/neutralBarBorderPaint, stationStripePaint/stationStripeBorderPaint,
        // пиктограммы (теперь присланные PNG — их палитра дня/ночи вообще не касается, см.
        // drawBitmapCentered) и signalTagBg/Border/Text — сознательно НЕ входят сюда, см.
        // комментарий у isDayMode выше. trainMarkerPaint раньше тоже не
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

    /** DirectionSelection.pathStatus/physicalPath — см. doc-комментарий у [isWrongPath]. */
    fun setPathStatus(wrongPath: Boolean, path: Int) {
        isWrongPath = wrongPath
        physicalPath = path
        invalidate()
    }

    /** " (I)"/" (II)" на неправильном пути, иначе пусто — см. doc-комментарий у [isWrongPath]. */
    private fun romanPathSuffix(): String =
        if (isWrongPath) " (${if (physicalPath == 1) "I" else "II"})" else ""

    fun setTrainPositionM(meters: Double) {
        trainPositionM = meters
        pruneExpiredHeadMarkers()
        invalidate()
    }

    /** Убирает метки, место которых хвост поезда уже проехал на HEAD_MARKER_CLEAR_M и больше
     *  (см. doc-комментарий у headMarkers и у [trainTailM]/[directionSignM]). Сравнение тоже
     *  направленное: для чётного хвост "проехал" метку, когда его км БОЛЬШЕ метки+запас, для
     *  нечётного — когда МЕНЬШЕ метки-запас (поезд едет в сторону уменьшения км). Записываем
     *  это как одно выражение через directionSignM, а не отдельным if — чтобы не дублировать
     *  формулу условия для обоих направлений. */
    private fun pruneExpiredHeadMarkers() {
        if (headMarkers.isEmpty()) return
        val tailM = trainTailM
        headMarkers.removeAll { directionSignM * (tailM - it.positionM) >= HEAD_MARKER_CLEAR_M }
    }

    /** Vф — крупная цифра факт. скорости в углу графика (см. doc-комментарий у [actualSpeedKmh]). */
    fun setActualSpeedKmh(speedKmh: Double?) {
        actualSpeedKmh = speedKmh
        invalidate()
    }

    /** Кнопка "моё местоположение" (см. doc-комментарий у panOffsetM) — то же самое, что
     *  происходит само через 10 секунд без касаний. */
    fun recenter() {
        panOffsetM = 0.0
        panRevertHandler.removeCallbacks(panRevertRunnable)
        invalidate()
    }

    /** Протягивание графика пальцем вперёд/назад — см. doc-комментарий у panOffsetM/
     *  visibleWindow. Палец двигается вправо → открываем то, что было СЗАДИ, поэтому окно
     *  обзора сдвигается НАЗАД — тот же принцип, что в любой карте/навигаторе ("тащишь
     *  карту"). "Сзади" — это МЕНЬШИЙ реальный км при чётном направлении движения, но БОЛЬШИЙ
     *  при нечётном (см. visibleWindow) — поэтому знак сдвига panOffsetM переключается по
     *  rawDirection, а не всегда "меньше км", как было раньше (из-за этого при нечётном
     *  протягивание пальцем двигало бы график в обратную, неожиданную сторону). */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                panRevertHandler.removeCallbacks(panRevertRunnable)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dxPx = event.x - lastTouchX
                lastTouchX = event.x
                if (abs(event.x - touchDownX) > tapTolerancePx || abs(event.y - touchDownY) > tapTolerancePx) {
                    touchMoved = true
                }
                // graphWidth — вся ширина канваса, должно совпадать с тем же graphWidth в
                // onDraw (см. его doc-комментарий) — иначе протягивание пальцем сдвигало бы
                // график не на то расстояние, которое прошёл палец.
                val graphWidth = width.toFloat()
                if (graphWidth > 0f) {
                    val spanM = behindM + aheadM
                    val sign = if (rawDirection == Direction.ODD) 1.0 else -1.0
                    panOffsetM += sign * dxPx * (spanM / graphWidth)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (!touchMoved) maybeAddHeadMarker(touchDownX, touchDownY)
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

    /** Тап около головы поезда, в верхней зоне (где сигналы) — ставит метку места на текущем
     *  месте головы (см. doc-комментарий у headMarkers). Допуск по X — "около", не точно в
     *  тонкую линию головы; по Y — только верхний ярус (ступени/сигналы), не рельеф снизу. */
    private fun maybeAddHeadMarker(x: Float, y: Float) {
        val graphWidth = width.toFloat()
        if (graphWidth <= 0f) return
        val stepsH = computeStepsH(trainStripeHeightPx())
        if (y > stepsH) return
        val (fromM, toM, isReversed) = visibleWindow()
        val spanM = toM - fromM
        val t = (trainPositionM - fromM) / spanM
        val headX = ((if (isReversed) 1.0 - t else t) * graphWidth).toFloat()
        if (abs(x - headX) > headMarkerTapTolerancePx) return
        headMarkers.add(HeadMarker(trainPositionM))
        invalidate()
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

    fun setWashouts(list: List<Washout>) {
        washouts = list
        invalidate()
    }

    fun setBrakeTests(list: List<BrakeTest>) {
        brakeTests = list
        invalidate()
    }

    fun setKtsm(list: List<Ktsm>) {
        ktsmPoints = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val (fromM, toM, isReversed) = visibleWindow()
        val spanM = toM - fromM
        // graphLeft=0, graphWidth=вся ширина канваса — машинист поправил: 2,5 км позади головы
        // должны считаться от САМОГО левого края экрана, а не от правого края отдельной полосы
        // (раньше под шкалу скорости была зарезервирована отдельная полоса слева, график начинался
        // правее неё). Шкалу-линейку позже убрали целиком (машинист: скорость уже подписана у
        // каждой ступени, линейка не нужна) — её место занял крупный Vф, см. drawCurrentSpeedLabel.
        val graphLeft = 0f
        val graphWidth = width.toFloat()

        val trainStripeHeight = trainStripeHeightPx()
        val stepsH = computeStepsH(trainStripeHeight)
        val lineY = stepsH
        val reliefTop = stepsH
        val reliefH = height - stepsH
        val scaleBottomY = grayScaleHeightPx()

        // isReversed (нечётное направление движения, см. visibleWindow) — "вперёд" (вправо на
        // экране) соответствует МЕНЬШЕМУ реальному км, поэтому и xFor, и обратная к ней mFor
        // зеркалятся: доля t от fromM к toM откладывается от ПРАВОГО края, а не от левого.
        fun xFor(meters: Double): Float {
            val t = (meters - fromM) / spanM
            return graphLeft + ((if (isReversed) 1.0 - t else t) * graphWidth).toFloat()
        }
        // Обратное к xFor (x→m) — ОБЯЗАТЕЛЬНО со сдвигом на graphLeft, иначе рельеф/сетка/
        // зелёная полоса съезжают относительно ступеней и линии сигналов (та же ошибка уже
        // была в HTML-макете и там же исправлена, см. drawGridAndRelief/README).
        fun mFor(x: Float): Double {
            // .toDouble() обязателен: без него t — Float, а "1.0 - t" — Double, и ветки if
            // получаются разных числовых типов (не собиралось на CI: "Unresolved reference"
            // на операторе * чуть ниже, компилятор не смог вывести общий тип веток if).
            val t = ((x - graphLeft) / graphWidth).toDouble()
            return fromM + (if (isReversed) 1.0 - t else t) * spanM
        }
        fun yForSpeed(speedKmh: Int): Float =
            scaleBottomY * (1f - speedKmh.coerceIn(0, 100) / MAX_SPEED_KMH)

        // Тёмное "поле" под всей зоной ступеней — ступени и штриховки лягут поверх (ТЗ раздел 7).
        canvas.drawRect(0f, 0f, width.toFloat(), lineY, signalFieldPaint)

        val segments = computeSegments(fromM, toM)
        val stepRegionBottoms = drawSpeedSteps(canvas, segments, ::xFor, ::yForSpeed)
        drawRestrictionHatching(canvas, fromM, toM, ::xFor, ::yForSpeed)
        drawSignalLineTicksAndNumbers(canvas, fromM, toM, ::xFor, lineY, trainStripeHeight)
        drawNeutralInsertBar(canvas, fromM, toM, ::xFor, lineY)
        drawSignalTags(canvas, fromM, toM, ::xFor, lineY, trainStripeHeight)
        // Пояс поезда рисуется ПОСЛЕДНИМ (поверх столбиков сигналов), не раньше — машинист: "я
        // его вижу, светофоры за ним" (раньше было наоборот: сигналы рисовались поверх пояса).
        drawTrainSignalStripe(canvas, ::xFor, lineY, trainStripeHeight)

        val pictograms = buildPictograms(fromM, toM, ::xFor)
        val (headY, tailY) = drawReliefAndTrain(canvas, fromM, toM, ::xFor, ::mFor, reliefTop, reliefH, pictograms)

        drawPerpendiculars(canvas, fromM, toM, ::xFor, stepRegionBottoms, pictograms, headY, tailY)
        drawTrainMarker(canvas, ::xFor)
        drawHeadMarkers(canvas, ::xFor, lineY)
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
            // startM < endM всегда (реальный пикетаж), но при нечётном направлении xFor
            // зеркалит экран — xFor(startM) оказывается БОЛЬШЕ xFor(endM), не меньше. Без
            // min/max получался "перевёрнутый" прямоугольник (left>right) — Android такое не
            // рисует вообще (или рисует вырожденно), из-за чего и сами ступени пропадали, и
            // проверка ширины "короткого участка" (minSegmentPxForLabel и т.п. ниже) всегда
            // ложно срабатывала на отрицательной "ширине", втискивая цифры друг на друга
            // (машинист прислал скриншот именно такой путаницы в нечётном направлении).
            val x0 = minOf(xFor(segment.startM), xFor(segment.endM))
            val x1 = maxOf(xFor(segment.startM), xFor(segment.endM))
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
            // min/max — см. тот же комментарий в drawSpeedSteps: при нечётном направлении
            // xFor зеркалит экран, и xFor(startM) оказывается больше xFor(endM).
            var x0 = minOf(xFor(r.startM.coerceAtLeast(fromM)), xFor(r.endM.coerceAtMost(toM)))
            var x1 = maxOf(xFor(r.startM.coerceAtLeast(fromM)), xFor(r.endM.coerceAtMost(toM)))
            if (x1 - x0 < pictogramMinHatchWidthPx) {
                val cx = (x0 + x1) / 2f
                x0 = cx - pictogramMinHatchWidthPx / 2f
                x1 = cx + pictogramMinHatchWidthPx / 2f
            }
            val yBottom = yForSpeed(r.speedKmh)

            canvas.drawRect(x0, scaleTop, x1, yBottom, restrictionFillPaint)
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

    /** Засечки пикетов/км и номера км — ПРЯМО на линии сигналов (не отдельной полосой-линейкой). */
    private fun drawSignalLineTicksAndNumbers(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        lineY: Float,
        trainStripeHeight: Float
    ) {
        // Километровый столбик НИЖЕ светофора — машинист дал точную пропорцию: "если светофор
        // 4,3мм, то км столбик 3,7мм", т.е. KM_TICK_TO_SIGNAL_HEIGHT_RATIO = 3.7/4.3.
        val signalTotalH = trainStripeHeight * SIGNAL_TO_TRAIN_HEIGHT_RATIO
        val kmTickH = signalTotalH * KM_TICK_TO_SIGNAL_HEIGHT_RATIO
        // Засечка середины километра (5-й пикет) — машинист: "сделай риски на 1/3 меньше, где
        // 5-й пикет тоже... просто без подписи, тоже риски, чтобы было видно". Без цифры, выше
        // обычной пикетной засечки, но на треть ниже километрового столбика.
        val midKmTickH = kmTickH * (2f / 3f)
        val picketsPerKm = (KM_LINE_LENGTH_M / PICKET_LENGTH_M).toLong()
        val midKmPicket = picketsPerKm / 2
        val firstPicketIndex = ceil(fromM / PICKET_LENGTH_M).toLong()
        val lastPicketIndex = floor(toM / PICKET_LENGTH_M).toLong()
        for (i in firstPicketIndex..lastPicketIndex) {
            val picketM = i * PICKET_LENGTH_M
            val isKmLine = i % picketsPerKm == 0L
            val isMidKmLine = !isKmLine && i % picketsPerKm == midKmPicket
            val x = xFor(picketM)
            val tickH = if (isKmLine) kmTickH else if (isMidKmLine) midKmTickH else dp(7f)
            canvas.drawLine(x, lineY - tickH, x, lineY, if (isKmLine) kmLinePaint else picketLinePaint)
        }

        canvas.drawLine(0f, lineY, width.toFloat(), lineY, signalLinePaint)

        // Номер км — ПРЯМО над своим километровым столбиком (засечка выше остальных, см. цикл
        // пикетов выше), а не между столбиками. Km-столбик стоит на отметке
        // kmIndex * KM_LINE_LENGTH_M — туда же, в пространстве, пишем и номер, но САМА ЦИФРА —
        // kmIndex-1, а не kmIndex. Машинист поправил: "когда едем по 6438 километру, впереди
        // должен быть столбик 6438 — а не 6439, как сейчас". Пока едем по пикетам километра N
        // (реальный пикетаж в [N·1000, (N+1)·1000) — то же самое N, что показывает крупная
        // координата вверху), столбик ВПЕРЕДИ физически стоит на отметке (N+1)·1000, и раньше
        // получал номер kmIndex=N+1 (следующий реальный км) — машинисту нужно, чтобы на этом же
        // физическом столбике был подписан N (номер км, по которому едем СЕЙЧАС), а не N+1.
        // Сам столбик (засечка) и его физическое место не трогаем — только номер, который на
        // нём подписан, сдвинут на единицу вниз.
        val firstKmIndex = floor(fromM / KM_LINE_LENGTH_M).toLong()
        val lastKmIndex = floor(toM / KM_LINE_LENGTH_M).toLong()
        for (kmIndex in firstKmIndex..lastKmIndex) {
            val kmM = kmIndex * KM_LINE_LENGTH_M
            if (kmM in fromM..toM) {
                // Номер км — ПРЯМО над своим столбиком (минимальный зазор, см. doc-комментарий
                // у kmNumberBaselineY) — машинист: "цифры километра... на минимальном, еле
                // заметном расстоянии от самого километрового столбика". Раньше отталкивался от
                // высоты ЗНАЧКА сигнала (signalTotalH, "на всякий случай", чтобы не пересечься с
                // соседним сигналом) — машинист явно отменил этот запас в пользу минимального
                // зазора; см. doc-комментарий у kmNumberBaselineY про редкий край случай.
                canvas.drawText((kmIndex - 1).toString(), xFor(kmM), kmNumberBaselineY(lineY, kmTickH), kmTextPaint)
            }
        }
    }

    /** Положение (baseline) номера км — минимальный зазор [labelLayerGapPx] над САМИМ столбиком
     *  (kmTickH), а не над значком сигнала, как раньше (машинист явно попросил именно так, см.
     *  doc-комментарий у вызова выше). Та же точка отсчёта используется и в drawSignalTags для
     *  подписи сигнала — цепочка снизу вверх "столбик → номер км → подпись светофора → подпись
     *  станции", каждая пара с тем же минимальным зазором (машинист: "на таком же минимальном
     *  расстоянии").
     *
     *  Редкий край случай: значок сигнала (signalTotalH) немного выше столбика (kmTickH,
     *  KM_TICK_TO_SIGNAL_HEIGHT_RATIO=3.7/4.3 ≈86%), поэтому теоретически при очень широком
     *  экране номер км/табличка сигнала могли бы наложиться на сам значок — на практике высота
     *  текста+зазоров (десятки dp) намного превышает эту разницу (всего ~14% от signalTotalH,
     *  которая сама по себе невелика), так что на реальных телефонах наложения не будет. */
    private val labelLayerGapPx = dp(3f)

    private fun kmNumberBaselineY(lineY: Float, kmTickH: Float): Float {
        val descent = kmTextPaint.fontMetrics.descent
        return lineY - kmTickH - descent - labelLayerGapPx
    }

    /** Высота пояса поезда на линии сигналов, px — см. doc-комментарий у REFERENCE_TRAIN_LENGTH_M.
     *  ФИКСИРОВАННАЯ: считается один раз от эталонного поезда (Ермак 3ЭС5К + 71 условная
     *  единица) на БАЗОВОМ масштабе экрана (REFERENCE_SPAN_M), а не от фактической ширины
     *  полоски — поэтому не меняется ни от текущего зума, ни от реальной длины состава
     *  (машинист: "если он меньше будет, высота должна быть такая же"). */
    private fun trainStripeHeightPx(): Float {
        val referenceWidthPx = width.toFloat() * (REFERENCE_TRAIN_LENGTH_M / REFERENCE_SPAN_M).toFloat()
        // ×2/3 — машинист: "высоту поезда уменьши на 1/3". Светофор и км-столбик при этом сами
        // пересчитаются пропорционально (они берутся от trainStripeHeight), так что "светофор
        // ровно в 2 раза выше поезда" остаётся верным автоматически.
        return referenceWidthPx * TRAIN_STRIPE_HEIGHT_RATIO.toFloat() * (2f / 3f)
    }

    /** Высота серой шкалы скорости (0..100), px — см. doc-комментарий класса/companion object:
     *  тот же фиксированный размер, что раньше давало старое жёсткое деление верх:низ=2,5:1 с
     *  SCALE_BOTTOM_FRACTION внутри него, но теперь считается напрямую от ВСЕЙ высоты View, а не
     *  от стопроцентно зарезервированного верхнего яруса. */
    private fun grayScaleHeightPx(): Float =
        height * (STEPS_TO_RELIEF_RATIO / (STEPS_TO_RELIEF_RATIO + 1f)) * SCALE_BOTTOM_FRACTION

    /** Нужная высота тёмного "поля" сигналов (между низом серой шкалы и линией сигналов) — РОВНО
     *  по содержимому (значок сигнала/станции + номер км + табличка сигнала + табличка станции,
     *  всё с минимальными зазорами [labelLayerGapPx], см. drawSignalTags/drawSignalLineTicks-
     *  AndNumbers), плюс минимальный зазор [labelLayerGapPx] сверху — а не фиксированная
     *  доля экрана, как раньше. Машинист: "можем выиграть расстояние... увеличить площадь, где
     *  профиль, чтобы там было больше места по высоте" — освободившаяся так высота отдаётся
     *  полосе рельефа (см. onDraw: stepsH = grayScaleHeightPx() + darkFieldHeightPx(...),
     *  reliefH = height - stepsH). Формула ПОВТОРЯЕТ (не переиспользует код, но те же константы
     *  и те же шаги) расчёт в drawSignalTags, чтобы высота, которую мы здесь РЕЗЕРВИРУЕМ, точно
     *  совпадала с тем, что там реально РИСУЕТСЯ. */
    private fun darkFieldHeightPx(trainStripeHeight: Float): Float {
        val signalTotalH = trainStripeHeight * SIGNAL_TO_TRAIN_HEIGHT_RATIO
        val kmTickH = signalTotalH * KM_TICK_TO_SIGNAL_HEIGHT_RATIO
        val kmMetrics = kmTextPaint.fontMetrics
        val kmNumberTextH = kmMetrics.descent - kmMetrics.ascent
        val boxH = dp(SIGNAL_TAG_BOX_HEIGHT_DP)
        // Столбик → [зазор] → номер км → [зазор] → табличка сигнала → [зазор] → табличка
        // станции → [зазор] → низ серой шкалы — та же цепочка минимальных зазоров, что и в
        // drawSignalLineTicksAndNumbers/drawSignalTags (см. их doc-комментарии), повторена
        // здесь, чтобы высота, которую мы РЕЗЕРВИРУЕМ, точно совпадала с тем, что реально
        // РИСУЕТСЯ — включая последний зазор перед серой шкалой (машинист: "линия [ограничения]
        // на таком же минимальном расстоянии от надписи станции").
        return kmTickH + labelLayerGapPx + kmNumberTextH + labelLayerGapPx + boxH + labelLayerGapPx + boxH + labelLayerGapPx
    }

    private fun computeStepsH(trainStripeHeight: Float): Float =
        grayScaleHeightPx() + darkFieldHeightPx(trainStripeHeight)

    /** Серая полоска физической длины поезда НА линии сигналов (ТЗ раздел 7) + белая окантовка.
     *  С головной стороны (x1, куда едет поезд) — не прямой угол, а скос под 45° от середины
     *  боковой грани вверх до верхнего края, как кабина локомотива (машинист: "с правой
     *  стороны, где голова поезда... с середины боковой стороны вверх идёт скос под углом 45
     *  градусов, как будто бы там кабина"). */
    private fun drawTrainSignalStripe(canvas: Canvas, xFor: (Double) -> Float, lineY: Float, h: Float) {
        if (trainLengthM <= 0.0) return
        val x0 = xFor(trainTailM)
        val x1 = xFor(trainPositionM)
        val w = max(1f, x1 - x0)
        val top = lineY - h
        val cabCut = h / 2f
        val path = Path().apply {
            moveTo(x0, lineY)
            lineTo(x0, top)
            lineTo(x0 + w - cabCut, top)
            lineTo(x0 + w, top + cabCut)
            lineTo(x0 + w, lineY)
            close()
        }
        canvas.drawPath(path, trainSignalStripePaint)
        canvas.drawPath(path, trainSignalStripeBorderPaint)
    }

    /** Нейтральная вставка — синяя полоса НА линии сигналов, от km+пк начала на реальную
     *  протяжённость [NeutralInsert.lengthM]; направление — [rawDirection] (см. класс). */
    private fun drawNeutralInsertBar(canvas: Canvas, fromM: Double, toM: Double, xFor: (Double) -> Float, lineY: Float) {
        for (n in neutralInserts) {
            if (n.direction != rawDirection) continue
            if (n.chainageM + n.lengthM < fromM || n.chainageM > toM) continue
            // min/max — см. тот же комментарий в drawSpeedSteps: при нечётном направлении
            // xFor зеркалит экран.
            val x0 = minOf(xFor(n.chainageM), xFor(n.chainageM + n.lengthM))
            val x1 = maxOf(xFor(n.chainageM), xFor(n.chainageM + n.lengthM))
            val w = max(1f, x1 - x0)
            canvas.drawRect(x0, lineY - dp(5f), x0 + w, lineY, neutralBarPaint)
            canvas.drawRect(x0, lineY - dp(5f), x0 + w, lineY, neutralBarBorderPaint)
        }
    }

    /** Значки сигналов (картинка) и станций (цветная полоска, см. drawStationStripe) + таблички
     *  с расстоянием — настоящие
     *  данные ([signals] главного пути + [stations] + [blockPosts] как доп. сигналы).
     *  [Signal.isVisibleFor] ([direction] — effectiveDataset, [rawDirection] — реальное
     *  направление движения), а не "сырой" [Signal.direction] — см. doc-комментарий у
     *  effectiveDirection: сырой тег означает только "из какого PDF-документа", а не "к какому
     *  направлению реально относится этот светофор" (машинист прислал скриншот с сигналами
     *  обоих направлений одновременно на чётном пути — именно из-за использования сырого тега).
     *  ЧД/НД — особый случай внутри isVisibleFor: показываются только на неправильном пути
     *  (машинист поправил отдельно — "ЧД и НД должны показываться только по неправильному
     *  пути", у него на правильном пути всё равно показывался ЧД). */
    private fun drawSignalTags(
        canvas: Canvas,
        fromM: Double,
        toM: Double,
        xFor: (Double) -> Float,
        lineY: Float,
        trainStripeHeight: Float
    ) {
        // Полная высота значка сигнала (от линии сигналов до верха капсулы, столбик+2×ry) —
        // РОВНО в 2 раза выше пояса поезда (машинист по скриншоту: "светофор должен быть ровно
        // в 2 раза выше этого поезда"). Внутренние пропорции самого значка (rx:ry:столбик,
        // станция:сигнал) берутся из констант SIGNAL_*_DP/STATION_*_DP как ШАБЛОН формы и
        // масштабируются ЦЕЛИКОМ одним коэффициентом под эту новую полную высоту — так станция
        // остаётся в 7/6 раза крупнее сигнала, а не только сигнал подрос.
        val signalTotalH = trainStripeHeight * SIGNAL_TO_TRAIN_HEIGHT_RATIO
        val signalScale = signalTotalH / dp(SIGNAL_TOTAL_H_DP)
        // signalRxPx (ширина капсулы) больше не нужен — у присланной картинки сигнала своя
        // собственная пропорция, см. drawBitmapCentered.
        val signalRyPx = dp(SIGNAL_RY_DP) * signalScale
        val signalPolePx = dp(SIGNAL_POLE_DP) * signalScale
        val stationROuterPx = dp(STATION_R_OUTER_DP) * signalScale
        val stationPolePx = dp(STATION_POLE_DP) * signalScale

        val tags = buildList {
            for (s in signals) if (s.isVisibleFor(direction, rawDirection)) {
                // Условно разрешающий сигнал — буква "Т" сразу после номера, перед тире и
                // расстоянием (машинист: "пишем 12 и букву Т, тире и расстояние"). Римский
                // номер пути — сразу за этим, см. doc-комментарий у [isWrongPath].
                val label = (if (s.conditionallyPermissive) s.name + "Т" else s.name) + romanPathSuffix()
                add(SignalTag(s.chainageM, label, false))
            }
            for (b in blockPosts) if (b.direction == direction) add(SignalTag(b.chainageM, b.label + romanPathSuffix(), false))
            for (st in stations) if (st.direction == direction) add(SignalTag(st.chainageM, st.name, true))
        }
        // Порядок — ПО ЭКРАНУ слева направо, не всегда по возрастанию реального км: ниже по
        // коду цикл коллизий табличек (tierRightEdge) идёт одним проходом слева направо и
        // предполагает, что x у каждого следующего тега не меньше, чем у предыдущего — при
        // нечётном направлении (см. directionSignM/xFor) экран зеркалится, и порядок по
        // экрану — это УБЫВАНИЕ реального км, а не возрастание.
        .sortedBy { directionSignM * it.chainageM }

        // "Впереди"/"проехали" — направленные понятия (см. doc-комментарий у [directionSignM]):
        // для чётного впереди значит БОЛЬШИЙ реальный км, для нечётного — МЕНЬШИЙ. Раньше тут
        // было жёстко "chainageM > trainPositionM", что для нечётного направления было прямо
        // противоположно реальности (машинист: "включаю нечётный путь... как я сейчас поеду?").
        // Сортировка — по расстоянию ОТ поезда В СТОРОНУ движения, по возрастанию (ближайший
        // впереди первый), а не просто по km — для нечётного это убывающий км.
        val aheadSignalsOnly = tags
            .filter { !it.isStation && directionSignM * (it.chainageM - trainPositionM) > 0.0 }
            .sortedBy { directionSignM * (it.chainageM - trainPositionM) }

        // Раньше табличка, которая не помещалась на базовой высоте рядом с предыдущей,
        // "подрастала" на уровень выше (столбик удлинялся) — машинист прислал скриншот, где
        // из-за этого ОДИН сигнал (и вслед за ним, через maxSignalTier+1, вообще все станции в
        // кадре) оказался заметно выше остальных: "один светофор почему-то выше остальных... от
        // него ещё идёт станция тоже ещё выше... выровняй по одной линии". Пока убрано совсем —
        // все сигналы на одном уровне, все станции на одном фиксированном уровне выше, без
        // динамического роста; "позже скажу, как перерисовать светофоры" — отдельная будущая
        // задача на саму схему разводки табличек.
        data class PlacedTag(val tag: SignalTag, val x: Float, val text: String, val boxW: Float)

        val placed = mutableListOf<PlacedTag>()
        for (tag in tags) {
            if (tag.chainageM < fromM || tag.chainageM > toM) continue
            val x = xFor(tag.chainageM)

            val passed = directionSignM * (tag.chainageM - trainPositionM) <= 0.0
            var distanceLabel = ""
            if (!passed) {
                val distance = if (tag.isStation) {
                    directionSignM * (tag.chainageM - trainPositionM)
                } else {
                    val idx = aheadSignalsOnly.indexOf(tag)
                    if (idx <= 0) {
                        directionSignM * (tag.chainageM - trainPositionM)
                    } else {
                        directionSignM * (tag.chainageM - aheadSignalsOnly[idx - 1].chainageM)
                    }
                }
                distanceLabel = "-" + Math.round(distance)
            }
            val text = tag.label + distanceLabel

            val textW = signalTagTextPaint.measureText(text)
            // Коробка вокруг надписи пропорционально уменьшена вместе с самим текстом
            // (signalTagTextPaint 26dp→16dp) — иначе вокруг мелкого текста осталась бы
            // непропорционально широкая белая рамка.
            val boxW = textW + dp(8f)

            placed.add(PlacedTag(tag, x, text, boxW))
        }

        val boxH = dp(SIGNAL_TAG_BOX_HEIGHT_DP)
        // Машинист: "цифры километра... на минимальном... расстоянии от самого километрового
        // столбика. [Табличка] сигналов... на таком же минимальном расстоянии выше, чем цифры
        // километра. Название станции... на минимальном таком же расстоянии от надписи сигналов"
        // — цепочка СНИЗУ ВВЕРХ: столбик км (kmTickH) → [labelLayerGapPx] → номер км →
        // [labelLayerGapPx] → табличка сигнала → [labelLayerGapPx] → табличка станции. Раньше
        // табличка сигнала отталкивалась от ВЫСОТЫ ЗНАЧКА (signalTotalH) с отдельным запасом
        // "на номер км" — читалось как лишний зазор именно между столбиком и номером км. Теперь
        // — от ФАКТИЧЕСКОГО верха номера км (kmNumberBaselineY+ascent), той же точки, где номер
        // реально рисуется в drawSignalLineTicksAndNumbers (см. doc-комментарий там же).
        val kmTickH = signalTotalH * KM_TICK_TO_SIGNAL_HEIGHT_RATIO
        val kmMetrics = kmTextPaint.fontMetrics
        val kmNumberTopY = kmNumberBaselineY(lineY, kmTickH) + kmMetrics.ascent
        val signalTagBottom = kmNumberTopY - labelLayerGapPx
        val signalTagTop = signalTagBottom - boxH
        val stationTagBottom = signalTagTop - labelLayerGapPx
        for ((tag, x, text, boxW) in placed) {

            val poleLen = if (tag.isStation) stationPolePx else signalPolePx
            val rOuter = if (tag.isStation) stationROuterPx else signalRyPx
            val poleTop = lineY - poleLen
            val iconCy = lineY - poleLen - rOuter

            canvas.drawLine(x, lineY, x, poleTop, signalPolePaint)

            if (tag.isStation) {
                drawStationStripe(canvas, x, iconCy, stationROuterPx)
            } else {
                // Машинист прислал настоящую картинку сигнала (три огня + "лепесток" снизу) —
                // заменяет прежнюю рисованную капсулу-"стадион" целиком (машинист подтвердил:
                // "заменить все сигналы на графике этим PNG"). Высота та же, что была у капсулы
                // (signalRyPx*2 — ровно в 2 раза выше пояса поезда, см. SIGNAL_TO_TRAIN_HEIGHT_RATIO),
                // ширина — по собственной пропорции картинки, не по прежней signalRxPx.
                drawBitmapCentered(canvas, signalBitmap, x, iconCy, signalRyPx)
            }

            val tagBottom = if (tag.isStation) stationTagBottom else signalTagBottom
            val tagTop = tagBottom - boxH

            canvas.drawLine(x, tagBottom, x, iconCy - rOuter, signalPolePaint)
            canvas.drawRect(x + dp(0.7f), tagTop + dp(0.7f), x + boxW - dp(0.7f), tagTop + boxH - dp(0.7f), signalTagBgPaint)
            canvas.drawRect(x, tagTop, x + boxW, tagTop + boxH, signalTagBorderPaint)
            canvas.drawText(text, x + dp(4f), tagTop + boxH - dp(5f), signalTagTextPaint)
        }
    }


    /** Станция — цветная полоска вместо прежних колец (машинист: "убери эти кружки... ставишь
     *  вот такую полоску такого цвета"), по центру столбика, той же шириной (2×[rOuter]), что
     *  раньше занимало кольцо — указательная линия к табличке расстояния по-прежнему идёт от
     *  её середины вверх (см. вызов drawLine в drawSignalTags, он не изменился). Фиксированный
     *  цвет — не зависит от дня/ночи (см. stationStripePaint). */
    private fun drawStationStripe(canvas: Canvas, cx: Float, cy: Float, rOuter: Float) {
        val h = 2f * rOuter * STATION_STRIPE_HEIGHT_RATIO
        val bounds = RectF(cx - rOuter, cy - h / 2f, cx + rOuter, cy + h / 2f)
        canvas.drawRect(bounds, stationStripePaint)
        canvas.drawRect(bounds, stationStripeBorderPaint)
    }

    /** Все пиктограммы в видимом окне, отсортированные по м — настоящие (переезды/возд.
     *  промежутки/нейтральные вставки) + динамические (ограничения, ТЗ раздел 11). Переезды —
     *  оба знака ГОСТ Р 52290, по [Crossing.guarded]: 1.1 со шлагбаумом (решётка) для охраняемых,
     *  1.2 с паровозом (без шлагбаума) для неохраняемых — машинист прислал образцы обоих. */
    private fun buildPictograms(fromM: Double, toM: Double, xFor: (Double) -> Float): List<Pictogram> {
        val result = mutableListOf<Pictogram>()
        for (c in crossings) if (c.direction == direction && c.chainageM in fromM..toM) {
            val kind = if (c.guarded) PictogramKind.CROSSING_GUARDED else PictogramKind.CROSSING_UNGUARDED
            result.add(Pictogram(c.chainageM, kind))
        }
        for (a in airGaps) if (a.direction == direction && a.chainageM in fromM..toM) {
            result.add(Pictogram(a.chainageM, PictogramKind.AIR_GAP))
        }
        for (k in ktsmPoints) if (k.direction == direction && k.chainageM in fromM..toM) {
            result.add(Pictogram(k.chainageM, PictogramKind.KTSM))
        }
        for (b in brakeTests) if (b.direction == direction && b.chainageM in fromM..toM) {
            result.add(Pictogram(b.chainageM, PictogramKind.BRAKE_TEST))
        }
        for (n in neutralInserts) if (n.direction == rawDirection && n.chainageM in fromM..toM) {
            // Знак "Нейтральная вставка" должен стоять в НАЧАЛЕ вставки — там, где её встречают
            // первой, едучи вперёд (машинист: "знак стоит в начале... в нечётном направлении у
            // тебя сначала полоска, потом знак — поменяй"). Синяя полоска (drawNeutralInsertBar)
            // всегда рисуется по реальному пикетажу [n.chainageM, n.chainageM+n.lengthM] — для
            // чётного (едем в сторону роста км) первым встречается МЕНЬШИЙ край (n.chainageM —
            // уже было верно), а для нечётного (едем в сторону убывания км, см.
            // directionSignM/visibleWindow) первым встречается БОЛЬШИЙ край.
            val signChainageM = if (n.direction == Direction.ODD) n.chainageM + n.lengthM else n.chainageM
            result.add(Pictogram(signChainageM, PictogramKind.NEUTRAL))
        }
        for (r in restrictions) {
            // Знак "конец опасного места" (машинист уточнил правило): показываем, только если
            // участок достаточно длинный, чтобы значки начала и конца не легли вплотную друг на
            // друга на экране — иначе, как и раньше, его просто нет (решаем по месту на экране,
            // в пикселях, а не фиксированным числом метров, чтобы правило было верным при любом
            // масштабе графика). Жёлтый и зелёный щиты остаются на тех же местах — за км до
            // начала и через км после конца, их это решение не касается.
            val yellowM = r.startM - SHIELD_OFFSET_M
            val greenM = r.endM + SHIELD_OFFSET_M
            if (r.startM in fromM..toM) result.add(Pictogram(r.startM, PictogramKind.OPASNOE_START))
            if (r.endM in fromM..toM) {
                val pxGap = kotlin.math.abs(xFor(r.endM) - xFor(r.startM))
                if (pxGap >= minPictogramGapPx) result.add(Pictogram(r.endM, PictogramKind.OPASNOE_END))
            }
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
        val ceilY = top + dp(6f)
        // Масштаб — ФИКСИРОВАННЫЙ (см. FIXED_RELIEF_RANGE_M), одинаковый ВЕЗДЕ на маршруте, а не
        // зависящий от какого-либо окна. Раньше перепробованы: весь маршрут целиком (единственный
        // по-настоящему огромный перепад, Яблоновый перевал, занимал всю шкалу — обычные подъёмы
        // сжимались почти до прямой линии); фиксированный 15-км блок (кривая рисовалась ломаной,
        // скачками на границе блока — "он идёт ступенями... нужно, чтобы не был ступенями");
        // скользящее окно ±7,5км вокруг каждой точки (масштаб плавный, но ЛЮБОЙ локальный
        // диапазон — хоть в единицы метров на ровном месте — растягивался на всю высоту полосы,
        // "где практически нет подъёма, там рисует гору"). Машинист в итоге отказался от любого
        // масштаба, зависящего от окна: "профиль нужно нарисовать максимально точно... весь
        // профиль одинаково поднимался и опускался, не изменяя масштаб по высоте" — высота любой
        // точки теперь ВСЕГДА через один и тот же масштаб. "Плавает" (поднимается/опускается как
        // единое целое) только сам отступ кривой — привязан к высоте поезда в точке [anchorM]
        // (см. ниже), которая всегда рисуется на одной и той же (центральной) высоте полосы.
        // Машинист явно разрешил: то, что далеко впереди (на 2-3-4 км), может уйти выше потолка
        // полосы, если там настоящий большой подъём — "это не страшно", главное видеть точную
        // форму и где сам поезд.
        // Машинист: "когда пролистываю вправо/влево... допустим, до перегона Тургутуй-Яблоновая,
        // у меня этот бугор уходит в самый верх... когда поезда нету [в видимом окне], его можно
        // было стараться уменьшить [опустить], чтобы видно было профиль... но только поднимать
        // или опускать, не меняя масштаб по высоте". Отступ кривой привязан к высоте поезда в его
        // РЕАЛЬНОЙ точке только пока эта точка ещё видна в текущем окне (fromM..toM) — как только
        // пролистали достаточно далеко, что поезда в окне больше нет, берём высоту в БЛИЖАЙШЕЙ
        // к поезду точке ВНУТРИ окна (т.е. у ближнего к поезду края) — плавно, без скачка в
        // момент выхода из видимости (clamp, а не резкое переключение на середину окна), и
        // масштаб [FIXED_RELIEF_RANGE_M] при этом остаётся тем же самым, как и просил машинист.
        val anchorM = trainPositionM.coerceIn(fromM, toM)
        fun reliefY(m: Double): Float {
            val h = relief.heightAt(direction, m)
                // Вне покрытия (км5930-6604, README шаг 15) — ровная линия у пола полосы, а не
                // выдуманная синусоида (в отличие от HTML-макета — там это было нужно для
                // наглядности пропорций, здесь настоящему приложению это не нужно).
                ?: return floorY
            val anchorH = relief.heightAt(direction, anchorM) ?: return floorY
            val bandPx = floorY - ceilY
            val centerY = ceilY + bandPx / 2f
            val scale = bandPx / FIXED_RELIEF_RANGE_M
            return (centerY - (h - anchorH) * scale).toFloat()
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
        // Было "ceil(mFor(0f) / PICKET_LENGTH_M)" — верно, пока mFor(0f) совпадало с fromM
        // (всегда, до появления зеркалки для нечётного направления). При нечётном xFor/mFor
        // зеркалятся, и mFor(0f) — это уже toM (экран начинается с БОЛЬШЕГО км, не меньшего),
        // из-за чего firstPicketIndex получался почти равным lastPicketIndex (оба от toM) —
        // сетка пикетов на рельефе пропадала почти целиком (машинист: "а где пикетная
        // сетка?"). Берём fromM/toM напрямую — они всегда реальные min/max, направление тут
        // ни при чём (см. doc-комментарий у visibleWindow).
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
        // tailM может быть и МЕНЬШЕ, и БОЛЬШЕ trainPositionM в зависимости от направления (см.
        // trainTailM) — границы диапазона строим через min/max, иначе при нечётном направлении
        // получился бы пустой (невалидный, start>end) диапазон и полоса вообще не рисовалась бы.
        val tailM = trainTailM
        val trainRangeLowM = minOf(trainPositionM, tailM)
        val trainRangeHighM = maxOf(trainPositionM, tailM)
        fun traceTrainPath(strokeW: Float): Path {
            val path = Path()
            var started = false
            var px = 0f
            while (px <= w) {
                val m = mFor(px)
                if (m in trainRangeLowM..trainRangeHighM) {
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

        // пиктограммы — машинист: "если позволяет место снизу профиля, но не на нижней линии, а
        // на минимальном расстоянии от самой линии профиля [curveY, не от низа полосы]. а если
        // снизу не помещаются, то выше линии профиля, но на расстоянии чуть выше, чем линия
        // поезда, чтоб поезд не закрывал пиктограмму" — т.е. место привязано к КРИВОЙ рельефа в
        // своей точке, а не к фиксированной высоте полосы, как было раньше. aboveTrainClearancePx —
        // запас над зелёной полосой поезда (которая сама рисуется вплотную к кривой сверху, см.
        // traceTrainPath/trainReliefOutlinePaint), чтобы полоса, если поезд окажется в этой
        // точке, не закрашивала значок сверху.
        //
        // Если два значка из РАЗНЫХ источников (например, постоянная проба тормозов и временное
        // ограничение) легли бы ближе minPictogramGapPx друг к другу на экране, более поздний
        // при рисовании просто закрашивал бы более ранний своей непрозрачной заливкой — у
        // Canvas нет "расступись сам" (машинист нашёл это на реальном тесте: "была проба
        // тормозов, поставил ограничение рядом — значок пробы тормозов исчез, как только убрал
        // ограничение — опять появился"). Разводим жадным проходом СЛЕВА НАПРАВО ПО ЭКРАНУ (не
        // по пикетажу — при нечётном направлении экран зеркалится, см. xFor/isReversed): если
        // предпочтительное место (низ/верх по рельефу) занято слишком близко соседним значком,
        // пробуем противоположное; если и там тесно — остаёмся на предпочтительном (частичное
        // наложение лучше полной потери значка, и это редкий край случая — три и более значка
        // ровно в одной точке).
        val aboveTrainClearancePx = trainReliefOutlinePaint.strokeWidth + iconGapPx
        val pictogramsByScreenX = pictograms
            .map { Triple(it.chainageM, xFor(it.chainageM), it.kind) }
            .sortedBy { it.second }
        var lastBottomPx = Float.NEGATIVE_INFINITY
        var lastTopPx = Float.NEGATIVE_INFINITY
        for ((chainageM, px, kind) in pictogramsByScreenX) {
            val curveY = reliefY(chainageM)
            var useBottom = curveY + iconGapPx + 2f * iconHalfPx <= top + bandH
            val collidesPreferred =
                if (useBottom) px - lastBottomPx < minPictogramGapPx else px - lastTopPx < minPictogramGapPx
            if (collidesPreferred) {
                val collidesOther =
                    if (useBottom) px - lastTopPx < minPictogramGapPx else px - lastBottomPx < minPictogramGapPx
                if (!collidesOther) useBottom = !useBottom
            }
            if (useBottom) lastBottomPx = px else lastTopPx = px
            val cy = if (useBottom) curveY + iconGapPx + iconHalfPx else curveY - aboveTrainClearancePx - iconHalfPx
            drawPictogramGlyph(canvas, px, cy, iconHalfPx, kind)
        }

        // Обрывное место — в отличие от обычных пиктограмм выше (фиксированное место внизу
        // ИЛИ вверху полосы) стрелка касается рельефа РОВНО в своей точке, а не в одном из
        // двух условных мест. Машинист продиктовал: если рельеф там низкий (в нижней половине
        // полосы) — стрелка висит сверху и указывает вниз, на него; если высокий (в верхней
        // половине) — стрелка стоит снизу и указывает вверх.
        val midY = top + bandH / 2f
        for (washout in washouts) {
            if (washout.direction != direction || washout.chainageM !in fromM..toM) continue
            val px = xFor(washout.chainageM)
            val curveY = reliefY(washout.chainageM)
            drawWashoutGlyph(canvas, px, curveY, pointingDown = curveY > midY)
        }

        return reliefY(trainPositionM) to reliefY(tailM)
    }

    /** Присланная картинка (шарик+стрелка) — остриё стрелки касается кривой рельефа РОВНО в
     *  своей точке (curveY), а не в одном из двух фиксированных мест, как обычные пиктограммы
     *  (см. doc-комментарий у вызова выше). [pointingDown]=true — рисуем как прислано (шарик
     *  сверху, остриё снизу касается curveY); false — переворачиваем по вертикали вокруг
     *  точки касания (canvas.scale(1,-1)), чтобы шарик оказался снизу, а остриё указывало
     *  вверх, по-прежнему касаясь curveY. */
    private fun drawWashoutGlyph(canvas: Canvas, cx: Float, curveY: Float, pointingDown: Boolean) {
        val targetH = iconHalfPx * 2f
        val targetW = targetH * (washoutBitmap.width.toFloat() / washoutBitmap.height.toFloat())
        val dst = RectF(cx - targetW / 2f, curveY - targetH, cx + targetW / 2f, curveY)
        canvas.save()
        if (!pointingDown) canvas.scale(1f, -1f, cx, curveY)
        canvas.drawBitmap(washoutBitmap, null, dst, bitmapPaint)
        canvas.restore()
    }

    private fun drawPictogramGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, kind: PictogramKind) {
        when (kind) {
            PictogramKind.OPASNOE_START -> drawBitmapCentered(canvas, opasnoeStartBitmap, cx, cy, s)
            PictogramKind.OPASNOE_END -> drawBitmapCentered(canvas, opasnoeEndBitmap, cx, cy, s)
            PictogramKind.YELLOW_SHIELD -> drawBitmapCentered(canvas, yellowShieldBitmap, cx, cy, s)
            PictogramKind.GREEN_SHIELD -> drawBitmapCentered(canvas, greenShieldBitmap, cx, cy, s)
            PictogramKind.AIR_GAP -> drawBitmapCentered(canvas, airGapBitmap, cx, cy, s)
            PictogramKind.NEUTRAL -> drawBitmapCentered(canvas, neutralBitmap, cx, cy, s)
            PictogramKind.CROSSING_GUARDED -> drawBitmapCentered(canvas, crossingGuardedBitmap, cx, cy, s)
            PictogramKind.CROSSING_UNGUARDED -> drawBitmapCentered(canvas, crossingUnguardedBitmap, cx, cy, s)
            PictogramKind.BRAKE_TEST -> drawBitmapCentered(canvas, brakeTestBitmap, cx, cy, s)
            PictogramKind.KTSM -> drawBitmapCentered(canvas, ktsmBitmap, cx, cy, s)
        }
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

        val tailM = trainTailM
        val headX = xFor(trainPositionM)
        val tailX = xFor(tailM)
        canvas.drawLine(headX, regionBottomAt(trainPositionM), headX, headY, thickPerpendicularPaint)
        canvas.drawLine(tailX, regionBottomAt(tailM), tailX, tailY, thickPerpendicularPaint)
    }

    private fun drawTrainMarker(canvas: Canvas, xFor: (Double) -> Float) {
        val headX = xFor(trainPositionM)
        canvas.drawLine(headX, 0f, headX, height.toFloat(), trainMarkerPaint)
    }

    // Метка(и) места (см. doc-комментарий у headMarkers) — рисуются на СВОЕЙ позиции в метрах
    // (не на текущей голове поезда), поэтому с движением поезда уезжают влево по экрану вместе
    // со всем остальным графиком через тот же xFor, что и всё прочее.
    private fun drawHeadMarkers(canvas: Canvas, xFor: (Double) -> Float, lineY: Float) {
        if (headMarkers.isEmpty()) return
        val lineTopY = dp(36f)
        for (marker in headMarkers) {
            val x = xFor(marker.positionM)
            canvas.drawLine(x, lineY, x, lineTopY, headMarkerLinePaint)
            drawHeadMarkerPin(canvas, x, lineTopY)
        }
    }

    /** Значок-"капля", как в Google Картах — красная капля с белым кружком внутри, остриём
     *  вниз, на верхний конец красной линии (см. drawHeadMarkers). */
    private fun drawHeadMarkerPin(canvas: Canvas, cx: Float, tipY: Float) {
        val r = dp(9f)
        val ballCy = tipY - r * 2.1f
        val tailHalfWidth = r * 0.55f
        val path = Path()
        path.moveTo(cx - tailHalfWidth, ballCy + r * 0.8f)
        path.quadTo(cx, tipY, cx + tailHalfWidth, ballCy + r * 0.8f)
        path.close()
        canvas.drawPath(path, headMarkerPinPaint)
        canvas.drawCircle(cx, ballCy, r, headMarkerPinPaint)
        canvas.drawCircle(cx, ballCy, r * 0.42f, headMarkerPinHolePaint)
    }

    // Vф (факт. скорость) — в отдельном окошке (чёрный фон + белая каёмка), в левом верхнем
    // углу графика — там, где раньше была шкала-линейка (машинист: "эта линейка нам зачем, если
    // скорости на графике и так подписаны у каждой ступени" — убрали её). Окошко рисуется
    // ПОСЛЕДНИМ в onDraw, поэтому его непрозрачный фон гарантированно перекрывает всё
    // нарисованное под ним (машинист: "чтобы не перекрывалась ничем, не было линий"). V=
    // (разрешённая скорость на текущем месте) — отдельной строкой под окошком, как и раньше.
    // Окошко Vф — у правого края графика (было у левого), машинист: "перенести в правую
    // сторону, к правому краю экрана". Сдвинуто вниз (boxTop = dp(104f), не dp(8f)) специально
    // ПОД секундомер (MainActivity.toggleStopwatch), который открывается поверх графика в
    // верхнем правом углу той же стороны (activity_main.xml, stopwatchWindow, gravity=top|end,
    // высота ≈ отступы 16dp + время 22sp + отступ 6dp + кнопка "Сброс" 40dp ≈ 90dp) — иначе
    // открытый секундомер накрывал бы собой окошко Vф.
    private fun speedAtPoint(m: Double): Int? {
        val activeRestriction = restrictions.firstOrNull { m in it.startM..it.endM }
        return activeRestriction?.speedKmh ?: resolver.speedAt(direction, m)?.speedKmh
    }

    /** "V=" у Vф должно показывать не скорость в точке головы, а МИНИМАЛЬНУЮ из действующих
     *  на всей длине состава (от хвоста до головы) — машинист: "пока не выедем на 45, у нас
     *  должно показываться 45, как только выехали на 70 — уже 70" (состав реально растянут по
     *  нескольким ступеням скорости одновременно, и должна побеждать самая строгая). */
    private fun minSpeedOverTrainLength(): Int? {
        val lowM = minOf(trainPositionM, trainTailM)
        val highM = maxOf(trainPositionM, trainTailM)
        var min: Int? = null
        var m = lowM
        while (m < highM) {
            speedAtPoint(m)?.let { min = if (min == null) it else minOf(min!!, it) }
            m += SAMPLE_STEP_M
        }
        speedAtPoint(highM)?.let { min = if (min == null) it else minOf(min!!, it) }
        return min
    }

    private fun drawCurrentSpeedLabel(canvas: Canvas) {
        val speedKmh = minSpeedOverTrainLength()
        val limitText = if (speedKmh != null) "V=$speedKmh км/ч" else "V=?"
        val actualText = actualSpeedKmh?.let { "Vф=${it.toInt()}" } ?: "Vф=?"

        val paddingH = dp(14f)
        val paddingV = dp(10f)
        val metrics = actualSpeedTextPaint.fontMetrics
        val boxRight = width - dp(8f)
        val boxTop = dp(104f)
        val boxLeft = boxRight - actualSpeedTextPaint.measureText(actualText) - paddingH * 2
        val boxBottom = boxTop + (metrics.descent - metrics.ascent) + paddingV * 2
        val cornerRadius = dp(10f)
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, cornerRadius, cornerRadius, speedWindowBgPaint)
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, cornerRadius, cornerRadius, speedWindowBorderPaint)
        canvas.drawText(actualText, boxLeft + paddingH, boxTop + paddingV - metrics.ascent, actualSpeedTextPaint)

        val limitTextW = speedTextPaint.measureText(limitText)
        canvas.drawText(limitText, boxRight - limitTextW, boxBottom + dp(34f), speedTextPaint)
    }
}
