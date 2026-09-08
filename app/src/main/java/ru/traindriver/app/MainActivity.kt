package ru.traindriver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import ru.traindriver.app.audio.Announcement
import ru.traindriver.app.audio.VoiceAnnouncer
import ru.traindriver.app.location.GpsLocationProvider
import ru.traindriver.app.route.BrakeTest
import ru.traindriver.app.route.BrakeTestAssetLoader
import ru.traindriver.app.route.ChainageFormatter
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.DirectionSelection
import ru.traindriver.app.route.LocomotiveTable
import ru.traindriver.app.route.PathStatus
import ru.traindriver.app.route.PointAnnouncementScheduler
import ru.traindriver.app.route.RouteAssetLoader
import ru.traindriver.app.route.RouteTrack
import ru.traindriver.app.route.SpeedLimitAssetLoader
import ru.traindriver.app.route.SpeedLimitResolver
import ru.traindriver.app.route.TrainComposition
import ru.traindriver.app.route.TrainParams
import ru.traindriver.app.route.TrainParamsStore
import ru.traindriver.app.ui.TrackProfileView

class MainActivity : AppCompatActivity() {

    private lateinit var coordinateText: TextView
    private lateinit var directionInfoText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusBarText: TextView
    private lateinit var directionButton: Button
    private lateinit var pathButton: Button
    private lateinit var paramsButton: Button
    private lateinit var trackProfileView: TrackProfileView
    private lateinit var gpsLocationProvider: GpsLocationProvider

    // Направление/путь задаёт машинист вручную (ТЗ раздел 3) — по GPS это не определить.
    private var directionSelection = DirectionSelection(Direction.EVEN, 2)

    // По тапу на координату (см. onCreate) переключается обычный км+пк / формат КЛУБ-У.
    private var showKlubU = false
    private var lastChainageM = 0.0

    private fun formatCoordinate(chainageM: Double): String =
        if (showKlubU) ChainageFormatter.formatKlubU(chainageM) else ChainageFormatter.format(chainageM)

    // Калибровка (см. RouteTrack.Companion) приблизительная — см. README.
    private val routeTrack: RouteTrack by lazy {
        RouteTrack(
            rawPoints = RouteAssetLoader.loadLatLonList(this, RouteTrack.HILOK_CHERNYSHEVSK_ASSET),
            startChainageM = RouteTrack.HILOK_START_KM * 1000.0,
            lengthCorrectionFactor = RouteTrack.HILOK_CHERNYSHEVSK_LENGTH_CORRECTION
        )
    }

    private val speedLimits by lazy { SpeedLimitAssetLoader.loadSpeedLimits(this) }
    private val speedLimitResolver by lazy { SpeedLimitResolver(speedLimits) }

    // Голосовые оповещения (ТЗ раздел 6). Из всего списка координаты впереди по маршруту пока
    // есть только у пробы тормозов (brake_tests.json, шаг 5) — остальные фразы (переезд, КТСМ,
    // станция, обрывное место, временное ограничение) уже записаны и лежат в res/raw, но
    // сыграть их пока НЕЧЕМ: ни для одной из них нет координат в присланных данных (см.
    // README) — это отдельная, ещё не решённая задача. brakeTestScheduler пересобирается при
    // каждой смене направления (см. updateDirectionUi) — какие именно точки "впереди" зависит
    // от effectiveDataset.
    private val voiceAnnouncer by lazy { VoiceAnnouncer(this) }
    private val brakeTests by lazy { BrakeTestAssetLoader.loadBrakeTests(this) }
    private var brakeTestScheduler = PointAnnouncementScheduler<BrakeTest>(emptyList(), { it.chainageM }, thresholdM = 2000.0)

    // "Параметры" (ТЗ раздел 5 / раздел 12.2) — пока диалог поверх главного экрана (кнопка
    // paramsButton, см. showTrainParamsDialog), не отдельный подэкран меню, самого меню в
    // реальном приложении ещё нет. Значения сохраняются между запусками (TrainParamsStore) —
    // до первого сохранения используются те же дефолты, что и в HTML-макете.
    private val trainParamsStore by lazy { TrainParamsStore(this) }
    private var trainParams: TrainParams = TrainParamsStore.DEFAULT

    // РЖД всегда работает по московскому времени (ТЗ раздел 7) — местное берём из часового
    // пояса самого телефона, оба видны одновременно.
    private val mskFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }
    private val localFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeUpdater = object : Runnable {
        override fun run() {
            val now = Date()
            timeText.text = "МСК ${mskFormat.format(now)}\nМест ${localFormat.format(now)}"
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                coordinateText.text = getString(R.string.location_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        coordinateText = findViewById(R.id.coordinateText)
        directionInfoText = findViewById(R.id.directionInfoText)
        timeText = findViewById(R.id.timeText)
        statusBarText = findViewById(R.id.statusBarText)
        directionButton = findViewById(R.id.directionButton)
        pathButton = findViewById(R.id.pathButton)
        paramsButton = findViewById(R.id.paramsButton)
        trackProfileView = findViewById(R.id.trackProfileView)
        gpsLocationProvider = GpsLocationProvider(this)

        // "Координата (км+пк, по тапу переключается в формат КЛУБ-У)" — ТЗ раздел 7.
        coordinateText.setOnClickListener {
            showKlubU = !showKlubU
            coordinateText.text = formatCoordinate(lastChainageM)
        }

        trainParams = trainParamsStore.load()
        trackProfileView.setSpeedLimits(speedLimits)
        trackProfileView.setTrainLengthM(trainParams.composition.lengthM)
        timeHandler.post(timeUpdater)

        directionButton.setOnClickListener {
            val newDirection = directionSelection.direction.opposite()
            directionSelection = DirectionSelection(newDirection, directionSelection.physicalPath)
            updateDirectionUi()
        }
        pathButton.setOnClickListener {
            val newPath = if (directionSelection.physicalPath == 1) 2 else 1
            directionSelection = DirectionSelection(directionSelection.direction, newPath)
            updateDirectionUi()
        }
        paramsButton.setOnClickListener { showTrainParamsDialog() }
        updateDirectionUi()

        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onDestroy() {
        gpsLocationProvider.stop()
        timeHandler.removeCallbacks(timeUpdater)
        voiceAnnouncer.release()
        super.onDestroy()
    }

    private fun updateDirectionUi() {
        val s = directionSelection
        directionButton.text = if (s.direction == Direction.EVEN) "Чётное" else "Нечётное"
        pathButton.text = "Путь ${s.physicalPath}"

        val statusText = if (s.pathStatus == PathStatus.CORRECT) "правильный" else "неправильный"
        val datasetText = if (s.effectiveDataset == Direction.EVEN) "чётный" else "нечётный"
        val picketsText = if (s.picketsGrowing) "растут" else "убывают"
        directionInfoText.text =
            "Путь $statusText, датасет: $datasetText, пикеты $picketsText"

        trackProfileView.setDirection(s.effectiveDataset)

        // Смена направления/пути меняет, какой датасет действует (effectiveDataset) — значит
        // и какие именно точки пробы тормозов вообще относятся к пути впереди. Пересобираем
        // планировщик с нуля (а не просто фильтруем на лету) — иначе точки, уже объявленные
        // при старом направлении, ошибочно считались бы объявленными и при новом.
        val relevantBrakeTests = brakeTests.filter { it.direction == s.effectiveDataset }
        brakeTestScheduler = PointAnnouncementScheduler(relevantBrakeTests, { it.chainageM }, thresholdM = 2000.0)
    }

    // "Параметры" (ТЗ раздел 5): серия локомотива -> вес/длина автоматически по таблице
    // (LocomotiveTable), брутто/нетто/кол-во вагонов -> тара на вагон автоматически, условная
    // длина состава (усл. ваг.) -> полная физическая длина автоматически (см. TrainComposition:
    // усл. ваг. × 14 м + длина локомотива). Все "авто" поля пересчитываются на лету при вводе.
    private fun showTrainParamsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_train_params, null)
        val seriesSpinner = view.findViewById<Spinner>(R.id.locoSeriesSpinner)
        val numberInput = view.findViewById<EditText>(R.id.locoNumberInput)
        val weightText = view.findViewById<TextView>(R.id.locoWeightText)
        val lengthText = view.findViewById<TextView>(R.id.locoLengthText)
        val bruttoInput = view.findViewById<EditText>(R.id.wagonBruttoInput)
        val nettoInput = view.findViewById<EditText>(R.id.wagonNettoInput)
        val countInput = view.findViewById<EditText>(R.id.wagonCountInput)
        val tareText = view.findViewById<TextView>(R.id.wagonTareText)
        val condLengthInput = view.findViewById<EditText>(R.id.condLengthInput)
        val composedLengthText = view.findViewById<TextView>(R.id.composedLengthText)

        val seriesNames = LocomotiveTable.SERIES.keys.toList()
        seriesSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, seriesNames)

        val current = trainParams
        seriesSpinner.setSelection(seriesNames.indexOf(current.locomotiveSeries).coerceAtLeast(0))
        numberInput.setText(current.locoNumber)
        bruttoInput.setText(formatNumber(current.bruttoT))
        nettoInput.setText(formatNumber(current.nettoT))
        countInput.setText(current.wagonCount.toString())
        condLengthInput.setText(formatNumber(current.conditionalLengthUslVag))

        fun refreshAuto() {
            val series = seriesNames.getOrNull(seriesSpinner.selectedItemPosition) ?: current.locomotiveSeries
            val spec = LocomotiveTable.SERIES.getValue(series)
            weightText.text = "Вес (авто): ${formatNumber(spec.weightT)} т"
            lengthText.text = "Длина (авто): ${formatNumber(spec.lengthM)} м"

            val brutto = bruttoInput.text.toString().toDoubleOrNull() ?: 0.0
            val netto = nettoInput.text.toString().toDoubleOrNull() ?: 0.0
            val count = countInput.text.toString().toIntOrNull() ?: 0
            tareText.text = if (count > 0) {
                "Тара/вагон (авто): ${"%.1f".format((brutto - netto) / count)} т"
            } else {
                "Тара/вагон (авто): —"
            }

            val condLength = condLengthInput.text.toString().toDoubleOrNull() ?: 0.0
            val totalLength = condLength * TrainComposition.CONDITIONAL_WAGON_LENGTH_M + spec.lengthM
            composedLengthText.text = "Полная длина (авто): ${"%.1f".format(totalLength)} м"
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshAuto()
        }
        bruttoInput.addTextChangedListener(watcher)
        nettoInput.addTextChangedListener(watcher)
        countInput.addTextChangedListener(watcher)
        condLengthInput.addTextChangedListener(watcher)
        seriesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                refreshAuto()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshAuto()

        AlertDialog.Builder(this)
            .setTitle("Параметры")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val newParams = TrainParams(
                    locomotiveSeries = seriesNames.getOrNull(seriesSpinner.selectedItemPosition)
                        ?: current.locomotiveSeries,
                    locoNumber = numberInput.text.toString(),
                    bruttoT = bruttoInput.text.toString().toDoubleOrNull() ?: current.bruttoT,
                    nettoT = nettoInput.text.toString().toDoubleOrNull() ?: current.nettoT,
                    wagonCount = countInput.text.toString().toIntOrNull() ?: current.wagonCount,
                    conditionalLengthUslVag = (condLengthInput.text.toString().toDoubleOrNull()
                        ?: current.conditionalLengthUslVag).coerceAtLeast(0.0)
                )
                trainParams = newParams
                trainParamsStore.save(newParams)
                trackProfileView.setTrainLengthM(newParams.composition.lengthM)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** "4200" вместо "4200.0" для целых значений — как в HTML-макете. */
    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // проверяется явно в hasLocationPermission()
    private fun startLocationUpdates() {
        if (!gpsLocationProvider.isGpsProviderEnabled) {
            coordinateText.text = getString(R.string.gps_provider_disabled)
        }

        if (!hasLocationPermission()) return

        gpsLocationProvider.start { location ->
            val chainageM = routeTrack.chainageMetersFor(location.latitude, location.longitude)
            lastChainageM = chainageM
            coordinateText.text = formatCoordinate(chainageM)
            trackProfileView.setTrainPositionM(chainageM)
            statusBarText.text = buildStatusBarText(location, chainageM)

            val brakeTest = brakeTestScheduler.update(chainageM, directionSelection.picketsGrowing)
            if (brakeTest != null) {
                voiceAnnouncer.play(if (brakeTest.doubleTrain) Announcement.BRAKE_TEST_DOUBLE else Announcement.BRAKE_TEST)
            }
        }
    }

    // Нижняя статусная строка (ТЗ раздел 7): Vф | уклон | V=/S= | голова | хвост.
    // Уклон/голова/хвост пока "—" — нет данных о рельефе и о км+пк сигналов (см. README).
    private fun buildStatusBarText(location: Location, chainageM: Double): String {
        val vf = if (location.hasSpeed()) "Vф=${(location.speed * 3.6).toInt()}" else "Vф=?"

        val next = speedLimitResolver.findNextChange(
            direction = directionSelection.effectiveDataset,
            fromMeters = chainageM,
            forward = directionSelection.picketsGrowing
        )
        val nextText = if (next != null) {
            "V=${next.speedLimit.speedKmh} S=${next.distanceM.toInt()}м"
        } else {
            "V=? S=?"
        }

        return "$vf   Уклон=—   $nextText   Голова=—   Хвост=—"
    }
}
