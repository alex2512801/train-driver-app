package ru.traindriver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import ru.traindriver.app.location.GpsLocationProvider
import ru.traindriver.app.route.ChainageFormatter
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.DirectionSelection
import ru.traindriver.app.route.PathStatus
import ru.traindriver.app.route.RouteAssetLoader
import ru.traindriver.app.route.RouteTrack
import ru.traindriver.app.route.SpeedLimitAssetLoader
import ru.traindriver.app.route.SpeedLimitResolver
import ru.traindriver.app.ui.TrackProfileView

class MainActivity : AppCompatActivity() {

    private lateinit var coordinateText: TextView
    private lateinit var directionInfoText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusBarText: TextView
    private lateinit var directionButton: Button
    private lateinit var pathButton: Button
    private lateinit var trackProfileView: TrackProfileView
    private lateinit var gpsLocationProvider: GpsLocationProvider

    // Направление/путь задаёт машинист вручную (ТЗ раздел 3) — по GPS это не определить.
    private var directionSelection = DirectionSelection(Direction.EVEN, 2)

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
        trackProfileView = findViewById(R.id.trackProfileView)
        gpsLocationProvider = GpsLocationProvider(this)

        trackProfileView.setSpeedLimits(speedLimits)
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
    }

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
            coordinateText.text = ChainageFormatter.format(chainageM)
            trackProfileView.setTrainPositionM(chainageM)
            statusBarText.text = buildStatusBarText(location, chainageM)
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
