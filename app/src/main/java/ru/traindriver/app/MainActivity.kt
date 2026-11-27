// PhoneStateListener (ниже) — официально устаревший способ чтения силы сигнала, оставлен
// намеренно как запасной вариант только для Android ниже 12 (см. doc-комментарий у
// bindSimSlot) — на самих местах использования предупреждения уже были отключены
// (@Suppress("DEPRECATION") у функций), но импорт класса и объявление поля со списком этого
// типа — отдельные места, до которых та защита не дотягивалась (три жёлтых предупреждения
// при сборке — компилятор прав, но это ожидаемо и безопасно, а не забытая ошибка).
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package ru.traindriver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import ru.traindriver.app.audio.Announcement
import ru.traindriver.app.audio.VoiceAnnouncer
import ru.traindriver.app.location.BatteryOptimizationHelper
import ru.traindriver.app.location.GpsForegroundService
import ru.traindriver.app.location.LocationSource
import ru.traindriver.app.route.AirGap
import ru.traindriver.app.route.AirGapAssetLoader
import ru.traindriver.app.route.ArrivalDepartureState
import ru.traindriver.app.route.ArrivalDepartureTracker
import ru.traindriver.app.route.BlockPost
import ru.traindriver.app.route.BlockPostAssetLoader
import ru.traindriver.app.route.BrakeTest
import ru.traindriver.app.route.BrakeTestAssetLoader
import ru.traindriver.app.route.Ktsm
import ru.traindriver.app.route.KtsmAssetLoader
import ru.traindriver.app.route.ChainageFormatter
import ru.traindriver.app.route.Crossing
import ru.traindriver.app.route.CrossingAssetLoader
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.DirectionSelection
import ru.traindriver.app.route.PathStatus
import ru.traindriver.app.route.NeutralInsert
import ru.traindriver.app.route.NeutralInsertAssetLoader
import ru.traindriver.app.route.PointAnnouncementScheduler
import ru.traindriver.app.route.ReliefAssetLoader
import ru.traindriver.app.route.ReliefProfile
import ru.traindriver.app.route.Restriction
import ru.traindriver.app.route.RestrictionAwareSpeedResolver
import ru.traindriver.app.route.RestrictionsStore
import ru.traindriver.app.route.RouteAnchorAssetLoader
import ru.traindriver.app.route.RouteAssetLoader
import ru.traindriver.app.route.RouteTrack
import ru.traindriver.app.route.Signal
import ru.traindriver.app.route.SignalAssetLoader
import ru.traindriver.app.route.isVisibleFor
import ru.traindriver.app.route.SpeedLimit
import ru.traindriver.app.route.SpeedLimitAssetLoader
import ru.traindriver.app.route.SpeedLimitResolver
import ru.traindriver.app.route.Station
import ru.traindriver.app.route.StationAnnouncement
import ru.traindriver.app.route.StationAnnouncementAssetLoader
import ru.traindriver.app.route.StationAssetLoader
import ru.traindriver.app.route.InspectorRole
import ru.traindriver.app.route.TrainInspectionPoint
import ru.traindriver.app.route.TrainInspectionAssetLoader
import ru.traindriver.app.route.TrainParams
import ru.traindriver.app.route.TrainParamsStore
import ru.traindriver.app.route.Washout
import ru.traindriver.app.route.WashoutAssetLoader
import ru.traindriver.app.route.nearestStationName
import ru.traindriver.app.route.nextStationAhead
import ru.traindriver.app.ui.BatteryIconView
import ru.traindriver.app.ui.OpasnoeIconView
import ru.traindriver.app.ui.RecenterIconView
import ru.traindriver.app.ui.SignalBarsView
import ru.traindriver.app.ui.StopwatchIconView
import ru.traindriver.app.ui.TrackProfileView

class MainActivity : AppCompatActivity() {

    private lateinit var coordinateText: TextView
    private lateinit var gpsStatusText: TextView
    private lateinit var destinationText: TextView
    private lateinit var timeText: TextView
    private lateinit var signalSyncButton: Button
    private lateinit var statusUklonText: TextView
    private lateinit var statusVText: TextView
    private lateinit var statusSText: TextView
    private lateinit var statusHeadText: TextView
    private lateinit var statusTailText: TextView
    private lateinit var directionButton: Button
    private lateinit var pathButton: Button
    private lateinit var k1Button: Button
    // Кнопка "Меню" в статусной строке (раньше была прямая кнопка "Параметры") — открывает
    // AppMenuActivity: первый пункт "Параметры" (TrainParamsActivity), последний — "База данных"
    // (DatabaseActivity), машинист: "у нас пока там 2 пункта, дальше ещё будем добавлять".
    private lateinit var menuButton: Button
    private lateinit var recenterButton: RecenterIconView
    private lateinit var restrictionsButton: OpasnoeIconView
    private lateinit var dayNightButton: Button

    // Уровень сигнала SIM (ТЗ раздел 7, шапка) — своя замена системной строке состояния,
    // которую мы теперь скрываем целиком (см. hideSystemUi). ДВА слота — у машиниста две
    // SIM-карты, обе должны быть видны (см. startPhoneSignalUpdates); второй слот прячется,
    // если активна только одна SIM. PhoneStateListener устарел в пользу TelephonyCallback
    // (API 31+), но у нас minSdk=26, а он по-прежнему работает на всех версиях вплоть до
    // текущей targetSdk — заводить два разных пути ради одной функции не стали.
    private lateinit var simSlot0Group: View
    private lateinit var simSlot0Bars: SignalBarsView
    private lateinit var simSlot0Text: TextView
    private lateinit var simSlot1Group: View
    private lateinit var simSlot1Bars: SignalBarsView
    private lateinit var simSlot1Text: TextView

    // Заряд батареи телефона в шапке (машинист, не из ТЗ/макета) — см. refreshBatteryLevel,
    // вызывается раз в секунду из timeUpdater вместе с остальной шапкой.
    private lateinit var batteryIcon: BatteryIconView
    private lateinit var batteryText: TextView

    // Каждый активный слот — свой TelephonyManager (привязанный к конкретной SIM через
    // createForSubscriptionId) и свой PhoneStateListener (чтобы знать, какой именно
    // SignalBarsView обновлять) — храним пары, чтобы корректно отписаться в onDestroy.
    private val activeSimListeners = mutableListOf<Pair<TelephonyManager, PhoneStateListener>>()

    private var isDayMode = false
    private lateinit var trackProfileView: TrackProfileView

    // Окошко прибытия/отправления + история остановок (ТЗ раздел 10).
    private lateinit var arrivalWindow: View
    private lateinit var arrivalTimeText: TextView
    private lateinit var arrivalMidText: TextView
    private lateinit var arrivalDepartureText: TextView
    private lateinit var arrivalToggleButton: StopwatchIconView
    private lateinit var historyPanel: View
    private lateinit var historyRowsContainer: LinearLayout
    private lateinit var historyToggleButton: Button
    private lateinit var toneGenerator: ToneGenerator
    // Отдельный генератор для сигнала потери/нахождения GPS — НЕ на потоке STREAM_ALARM (тот
    // выше нарочно форсируется на максимум ради сигнала стоянки в шумной кабине), а на
    // STREAM_NOTIFICATION — машинист попросил, чтобы этот сигнал регулировался обычной
    // громкостью уведомлений телефона, а не всегда звучал на полную. См. doc-комментарий у
    // playGpsTransitionBeep.
    private lateinit var gpsBeepToneGenerator: ToneGenerator
    // Отложенные гудки 25-минутного сигнала стоянки (см. playStopAlarm) — отменяются в
    // onDestroy, чтобы не дёргать toneGenerator после его release(), если активность закрылась
    // ровно посреди серии гудков.
    private val pendingStopAlarmBeeps = mutableListOf<Runnable>()
    private val arrivalDepartureTracker = ArrivalDepartureTracker()
    private var arrivalWindowVisible = true
    private var historyPanelVisible = false
    // Прибытие/отправление (окошко стоянки + история) — всегда по МСК, независимо от часового
    // пояса самого телефона: РЖД работает по московскому времени (см. комментарий у mskFormat
    // ниже), местное — только для справки в основных часах, а не для этих событий.
    private val arrivalWallFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    // "Место стоянки" в истории (ТЗ раздел 10) — станция, если остановка в её пределах, иначе
    // км+пк (см. Station.kt, nearestStationName — приближение, без точных границ вход/выход).
    // Также — значки станций на линии сигналов (TrackProfileView.setStations).
    //
    // Все поля ниже раньше были "by lazy" (читались из assets один раз за всё время жизни
    // Activity) — машинист: "если я файл изменил... в базе данных, и у меня в приложении тоже
    // оно поменялось". MainActivity НЕ уничтожается при переходе на "База данных" и обратно
    // (это просто соседняя Activity в стеке, тот же процесс) — с "by lazy" однажды прочитанные
    // данные держались бы в памяти до перезапуска всего приложения, несмотря на новый файл.
    // Теперь это обычные var, перечитываются целиком в reloadRouteData() — при запуске и на
    // каждом onResume (см. там же).
    private var stations: List<Station> = emptyList()

    // Линия сигналов и профиль рельефа (ТЗ раздел 7/8/9/15) — портировано из HTML-макета, см.
    // TrackProfileView. Блок-посты — доп. сигналы (не пиктограмма, машинист поправил), переезды
    // загружаются, но визуально пока не нарисованы (нужен настоящий PNG знака, см. README/чат).
    private var signals: List<Signal> = emptyList()
    private var blockPosts: List<BlockPost> = emptyList()
    private var relief: ReliefProfile = ReliefProfile(emptyList())
    private var crossings: List<Crossing> = emptyList()
    private var airGaps: List<AirGap> = emptyList()
    private var washouts: List<Washout> = emptyList()
    private var neutralInserts: List<NeutralInsert> = emptyList()

    // "Ограничения" (ТЗ раздел 11) — временные ограничения скорости, введённые машинистом.
    // Живут только в памяти на время сессии (ни ТЗ, ни макет не говорят о сохранении между
    // запусками — см. Restriction.kt). Рисуются на графике (штриховка + пиктограммы, ТЗ
    // раздел 7/8 — см. TrackProfileView.setRestrictions) и учитываются в V=/S= статусной строки
    // через RestrictionAwareSpeedResolver (см. refreshStatusBar).
    // Сам список теперь хранится не здесь, а в RestrictionsStore — общий с RestrictionsActivity
    // (отдельный полноэкранный экран ввода ограничений, см. openRestrictionsScreen() ниже).
    private val restrictions: MutableList<Restriction> get() = RestrictionsStore.list

    // Ограничение с заданным путём показывается только на этом пути (ТЗ раздел 11: "число —
    // по стандартному правилу чётный/нечётный"); path == null — на любом (это "правильный
    // путь текущего направления", каким бы он ни был).
    private fun refreshTrackProfileRestrictions() {
        val relevant = restrictions.filter { it.path == null || it.path == directionSelection.physicalPath }
        trackProfileView.setRestrictions(relevant)
        // Список ограничений может поменяться в любой момент (RestrictionsActivity), не только
        // при смене направления/пути — обновляем планировщик здесь же, вместе с остальной
        // работой со списком (а не только из updateDirectionUi, как переезды/пробы тормозов).
        // updatePoints (не пересоздание объекта) сохраняет "уже объявлено" для точек, которых
        // это не коснулось, см. doc-комментарий у PointAnnouncementScheduler.updatePoints.
        tempLimitScheduler.updatePoints(relevant)
    }

    // Направление/путь задаёт машинист вручную (ТЗ раздел 3) — по GPS это не определить.
    private var directionSelection = DirectionSelection(Direction.EVEN, 2)

    // По тапу на координату (см. onCreate) переключается обычный км+пк / формат КЛУБ-У.
    private var showKlubU = false

    // lastChainageM/lastSpeedKmh/lastGpsFixAtMs — последняя ПОДТВЕРЖДЁННАЯ GPS-точка (не
    // резервная по сети, см. LocationSource.NETWORK в startLocationUpdates). График скорости,
    // голосовые объявления и статусная строка машинист попросил отчитывать "от головы поезда",
    // а не напрямую от GPS: между фиксами (или вообще без GPS какое-то время — см. README про
    // возможное глушение сигнала) позиция продолжает считаться от этой точки по известной
    // скорости и прошедшему времени, как считает путь настоящий локомотивный прибор при потере
    // спутников — см. estimatedChainageM/updateTrainPositionDerivedState, вызывается из
    // timeUpdater раз в секунду, а не прямо из GPS-колбэка.
    private var lastChainageM = 0.0
    private var lastSpeedKmh = 0.0
    private var lastGpsFixAtMs = 0L
    private var hasGpsFix = false

    // Защита от скачков (машинист прислал разбор "Блокнота машиниста" — у того своей защиты
    // по accuracy тоже нет, но зато есть "привязка к пути" через проекцию на линию, которая у
    // нас уже есть, см. routeTrack/RouteTrack.chainageMetersFor; добавляем то, чего не было ни
    // там, ни тут). Два независимых фильтра:
    //  - MAX_GPS_ACCURACY_M: обычный шум — отбрасываем фиксы с совсем плохой точностью;
    //  - MAX_PLAUSIBLE_SPEED_KMH: защита от неправильной проекции на линию пути (например, у
    //    станции, где несколько путей проходят рядом) — скачок пикетажа, которого состав не
    //    мог бы пройти за прошедшее время даже на нереально большой скорости, значит,
    //    проекция ошиблась, а не поезд телепортировался.
    // В обоих случаях фикс просто игнорируется — "от головы поезда" (estimatedChainageM)
    // продолжает считать по инерции, ровно как при обычной кратковременной потере GPS.
    private val maxGpsAccuracyM = 50f
    private val maxPlausibleSpeedKmh = 160.0

    private fun isPlausibleGpsFix(location: Location, candidateChainageM: Double): Boolean {
        if (location.hasAccuracy() && location.accuracy > maxGpsAccuracyM) return false
        if (!hasGpsFix) return true
        val elapsedS = (System.currentTimeMillis() - lastGpsFixAtMs) / 1000.0
        if (elapsedS <= 0.0) return true
        val jumpM = kotlin.math.abs(candidateChainageM - lastChainageM)
        val maxPlausibleM = (maxPlausibleSpeedKmh / 3.6) * elapsedS + 100.0 // запас на точность/пикетаж
        return jumpM <= maxPlausibleM
    }

    // Ручная поправка GPS по кнопке "Сигнал" (машинист проверил в реальной поездке: "врёт
    // систематически, метров на 200-300... чтобы GPS воспринимало, что это правильный вариант,
    // и после этого уже не менялось"). Прибавляется к КАЖДОЙ координате от GPS/сети в
    // startLocationUpdates — то есть держится постоянно, не только на один фикс, пока машинист
    // не откалибрует заново по другому сигналу (syncToNearestSignal тогда просто обновит это же
    // поле, не заводит отдельного "старого"/"нового" значения).
    private var chainageOffsetM = 0.0

    // Однократный звуковой сигнал при потере/нахождении GPS (машинист, не из ТЗ/макета) — null,
    // пока не было вообще ни одного колбэка location (самый первый колбэк, каким бы он ни был,
    // это не "потеря", просто первое чтение — поэтому не сравниваем null ни с чем, только
    // настоящие переключения между GPS/NETWORK после него). См. startLocationUpdates.
    private var lastLocationWasGps: Boolean? = null

    private fun formatCoordinate(chainageM: Double): String =
        if (showKlubU) ChainageFormatter.formatKlubU(chainageM) else ChainageFormatter.format(chainageM)

    // "Уклон=-99,9" — знак + число с одной десятичной, запятая вместо точки (как и везде в
    // статусной строке, ТЗ раздел 7). "+" у положительных показываем явно, у отрицательных
    // знак "-" уже даёт сам toString/format.
    private fun formatSlope(permille: Double): String {
        val rounded = Math.round(permille * 10) / 10.0
        val sign = if (rounded > 0) "+" else ""
        return sign + "%.1f".format(Locale.US, rounded).replace('.', ',')
    }

    // Калибровка (см. RouteTrack.Companion) приблизительная — см. README. Пересобирается в
    // reloadRouteData() — см. doc-комментарий там же про то, почему это уже не "by lazy".
    private lateinit var routeTrack: RouteTrack

    private var speedLimits: List<SpeedLimit> = emptyList()
    private var speedLimitResolver: SpeedLimitResolver = SpeedLimitResolver(emptyList())

    // Голосовые оповещения (ТЗ раздел 6). Координаты впереди по маршруту пока есть только для
    // пробы тормозов (brake_tests.json, шаг 5) и предвходного станции (station_announcements.json,
    // выведено из track_signals.json/track_stations.json — шаг 21). Переезд/КТСМ/обрывное
    // место/временное ограничение — фразы записаны и лежат в res/raw, но сыграть их пока
    // НЕЧЕМ: ни для одной из них нет координат ни в одном источнике (см. README). Оба
    // планировщика пересобираются при смене направления/пути (см. updateDirectionUi) — какие
    // именно точки "впереди" зависит от effectiveDataset.
    private val voiceAnnouncer by lazy { VoiceAnnouncer(this) }
    private var brakeTests: List<BrakeTest> = emptyList()
    private val brakeTestScheduler = PointAnnouncementScheduler<BrakeTest>(emptyList(), { it.chainageM }, thresholdOf = { 2000.0 })

    // КТСМ (ТЗ, голос Announcement.KTSM) — тот же статичный asset-список, что и проба тормозов/
    // обрывные места, тот же порог 2000м (отдельного порога машинист не продиктовал — взял по
    // аналогии с остальными точечными оповещениями). Пересобирается в updateDirectionUi.
    private var ktsmPoints: List<Ktsm> = emptyList()
    private val ktsmScheduler = PointAnnouncementScheduler<Ktsm>(emptyList(), { it.chainageM }, thresholdOf = { 2000.0 })
    private var stationAnnouncements: List<StationAnnouncement> = emptyList()
    private val stationScheduler =
        PointAnnouncementScheduler<StationAnnouncement>(emptyList(), { it.chainageM }, thresholdOf = { it.thresholdM })

    // Переезд — ТЗ раздел 6: "Внимание! Впереди переезд!" за 1500 м (CROSSING_FAR), и та же
    // короткая фраза "Впереди переезд!" ДВА раза — за 800 м и за 300 м (CROSSING_NEAR). Это не
    // один порог, а три — отдельный планировщик на каждый порог, иначе PointAnnouncementScheduler
    // пометил бы переезд объявленным уже на первом (дальнем) срабатывании и дальние два не
    // выстрелили бы вовсе (см. doc-комментарий класса — он разовый per-point per-scheduler).
    private val crossingFarScheduler = PointAnnouncementScheduler<Crossing>(emptyList(), { it.chainageM }, thresholdOf = { 1500.0 })
    private val crossingNear800Scheduler = PointAnnouncementScheduler<Crossing>(emptyList(), { it.chainageM }, thresholdOf = { 800.0 })
    private val crossingNear300Scheduler = PointAnnouncementScheduler<Crossing>(emptyList(), { it.chainageM }, thresholdOf = { 300.0 })

    // Нейтральная вставка (ТЗ раздел 8) — "Впереди нейтральная вставка!" за 600 м (голос
    // появился только сейчас — см. doc-комментарий у Announcement.NEUTRAL). Направление — КАК
    // ЕСТЬ (s.direction), а не effectiveDataset, см. doc-комментарий у NeutralInsert/
    // TrackProfileView.setRawDirection — та же причина, по которой рисование уже фильтрует по
    // rawDirection, а не по эффективному датасету.
    private val neutralInsertScheduler = PointAnnouncementScheduler<NeutralInsert>(emptyList(), { it.chainageM }, thresholdOf = { 600.0 })

    // Воздушный промежуток (ТЗ раздел 8) — "Впереди воздушный промежуток!" за 1000 м, тот же
    // статичный asset-список, что и переезды/КТСМ — пересобирается в updateDirectionUi.
    private val airGapScheduler = PointAnnouncementScheduler<AirGap>(emptyList(), { it.chainageM }, thresholdOf = { 1000.0 })

    // Временное ограничение (ТЗ раздел 6/11) — "Внимание! Впереди временное ограничение
    // скорости!" за 2000 м до НАЧАЛА ограничения ([Restriction.startM]). В отличие от
    // переездов/проб тормозов список точек не статичный asset, а меняется в любой момент
    // (машинист может добавить ограничение через RestrictionsActivity прямо во время движения) —
    // пересобирается там же, где и остальная работа со списком, см. refreshTrackProfileRestrictions.
    private val tempLimitScheduler = PointAnnouncementScheduler<Restriction>(emptyList(), { it.startM }, thresholdOf = { 2000.0 })

    // Обрывное место (ТЗ раздел 6) — "Внимание! Впереди обрывное место!" за 2000 м, тот же
    // статичный asset-список, что и переезды — пересобирается в updateDirectionUi.
    private val washoutScheduler = PointAnnouncementScheduler<Washout>(emptyList(), { it.chainageM }, thresholdOf = { 2000.0 })

    // Осмотр поезда бригадой — в отличие от ВСЕХ остальных оповещений выше, это НЕ заблаговременное
    // предупреждение (машинист: "голосовое срабатывает в момент начала этого осмотра", а не за
    // 600-2000 м до него). Порог поэтому не "расстояние для предупреждения заранее", а просто
    // запас, достаточный, чтобы точка не проскочила МЕЖДУ двумя соседними тиками позиции (раз в
    // секунду, см. timeUpdater/estimatedChainageM) даже на максимально реалистичной скорости —
    // 160 км/ч (тот же ориентир, что и у maxPlausibleSpeedKmh) это ~44 м/с, 50 м с запасом
    // покрывает один тик. Два отдельных планировщика (не один общий) — машинист/помощник
    // объявляются РАЗНЫМИ голосами, и PointAnnouncementScheduler разовый per-point, поэтому
    // список каждой роли должен быть отдельным, иначе точка одной роли считалась бы объявленной
    // и для другой.
    private val trainInspectionThresholdM = 50.0
    private var trainInspectionPoints: List<TrainInspectionPoint> = emptyList()
    private val trainInspectionMachinistScheduler =
        PointAnnouncementScheduler<TrainInspectionPoint>(emptyList(), { it.chainageM }, thresholdOf = { trainInspectionThresholdM })
    private val trainInspectionAssistantScheduler =
        PointAnnouncementScheduler<TrainInspectionPoint>(emptyList(), { it.chainageM }, thresholdOf = { trainInspectionThresholdM })

    // "Параметры" (ТЗ раздел 5 / раздел 12.2) — отдельный полноэкранный экран (TrainParamsActivity),
    // теперь открывается через кнопку "Меню" (AppMenuActivity — первый пункт), не напрямую из
    // статусной строки (см. doc-комментарий у menuButton ниже). Значения сохраняются между
    // запусками (TrainParamsStore) — до первого сохранения используются те же дефолты, что и
    // в HTML-макете.
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

            // Средняя строка окошка прибытия/отправления должна тикать вживую каждую секунду,
            // даже когда GPS не шлёт новых точек (поезд стоит на месте — minDistanceM в
            // GpsLocationProvider может вообще не сработать). Поэтому тикаем отсюда, тем же
            // раз-в-секунду хендлером, а не только из GPS-колбэка — так же ловятся и
            // чисто временные переходы (5 минут после отправления, 25 минут стоянки).
            if (arrivalDepartureTracker.onUpdate(lastSpeedKmh, now.time, lastChainageM)) {
                playStopAlarm()
            }
            refreshArrivalWindow()
            if (historyPanelVisible) refreshStopHistory()
            refreshStopwatch()
            refreshSimSignalPolling()
            refreshBatteryLevel()

            // График/объявления/статусная строка — "от головы поезда" (машинист), не прямо от
            // GPS-колбэка, см. doc-комментарий у lastChainageM и estimatedChainageM.
            val estimated = estimatedChainageM(now.time)
            if (estimated != null) {
                updateTrainPositionDerivedState(estimated)
            }

            timeHandler.postDelayed(this, 1000)
        }
    }

    // Сигнал 25-минутной стоянки (ТЗ раздел 10, "скоро выйдет время на перетормаживание",
    // которое наступает через 30 минут) — машинист проверил: один короткий гудок через
    // STREAM_NOTIFICATION прошёл незамеченным (вообще не прозвучал). Несколько гудков подряд
    // через STREAM_ALARM — громче, через отдельный, обычно не приглушённый канал.
    // Машинист уточнил ещё раз: в кабине электровоза шумно, 4 коротких "бип" там легко
    // потерялись бы в собственном шуме — громкость самого тона (ToneGenerator.MAX_VOLUME) тут
    // не помогает, это внутренняя "яркость" синтезированного звука, а не фактическая громкость
    // на динамике — она зависит от того, на какой уровень у телефона выставлена ГРОМКОСТЬ
    // КАНАЛА будильника в настройках, и эту настройку никто не трогал. Поэтому теперь явно
    // выставляем её на максимум прямо перед гудком (setStreamVolume), плюс самих гудков больше
    // и каждый длиннее, и сам тон — TONE_CDMA_ALERT_CALL_GUARD (резкий, предупреждающий, выше
    // по тону, должен пробиваться через ровный гул двигателя заметнее, чем обычный "пип").
    private fun playStopAlarm() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        try {
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (maxVol != null) audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (e: Exception) {
            // Не критично — если не получилось принудительно поднять громкость, гудок всё
            // равно сыграет на той громкости, что уже стоит.
        }

        val beepCount = 6
        val beepGapMs = 550L
        repeat(beepCount) { i ->
            val beep = Runnable { toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500) }
            pendingStopAlarmBeeps.add(beep)
            timeHandler.postDelayed(beep, i * beepGapMs)
        }
    }

    // Однократный (не серия, в отличие от 25-минутного) сигнал потери/нахождения GPS —
    // машинист попросил дополнительно к сигналу стоянки. Раньше были два РАЗНЫХ тона
    // (TONE_PROP_NACK/ACK — "плохой"/"хороший"), чтобы на слух отличать потерю от нахождения;
    // машинист поправил — один и тот же одиночный гудок на оба события, и звучать он должен
    // не на принудительно максимальной громкости потока будильника (как сигнал стоянки выше,
    // там это нарочно), а на обычном потоке уведомлений — то есть регулируется громкостью
    // уведомлений телефона, как любой другой звук уведомления.
    private fun playGpsTransitionBeep() {
        try {
            gpsBeepToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
        } catch (e: Throwable) {
            // см. doc-комментарий у pollSimSignal — та же подстраховка.
        }
    }

    // Сколько секунд после последнего настоящего GPS-фикса ещё доверяем счёту "от головы
    // поезда" по известной скорости — дальше погрешность накопится слишком сильно, и график/
    // объявления просто замирают на последнем известном месте, а не продолжают считать вслепую
    // сколь угодно долго.
    private val deadReckoningWindowS = 180.0

    /**
     * Позиция головы поезда СЕЙЧАС — не сама GPS-точка, а она же, доведённая по известной
     * скорости и прошедшему времени с последнего настоящего GPS-фикса (машинист: считать "от
     * головы поезда", а не замирать/скакать по сырым GPS-точкам) — ровно так же настоящий
     * локомотивный прибор продолжает считать путь по датчику скорости, когда спутники
     * ненадолго пропадают. Null — GPS не было ни разу, или прошло слишком много времени без
     * него (см. [deadReckoningWindowS]).
     */
    private fun estimatedChainageM(nowMs: Long): Double? {
        if (!hasGpsFix) return null
        val elapsedS = (nowMs - lastGpsFixAtMs) / 1000.0
        if (elapsedS < 0.0 || elapsedS > deadReckoningWindowS) return null
        val sign = if (directionSelection.picketsGrowing) 1.0 else -1.0
        return lastChainageM + (lastSpeedKmh / 3.6) * elapsedS * sign
    }

    /** График скорости, статусная строка и голосовые объявления (проба тормозов/станция) —
     *  всё, что раньше обновлялось прямо из GPS-колбэка, теперь приходит сюда раз в секунду
     *  из timeUpdater с позицией от [estimatedChainageM], а не напрямую от GPS. */
    private fun updateTrainPositionDerivedState(chainageM: Double) {
        trackProfileView.setTrainPositionM(chainageM)
        trackProfileView.setActualSpeedKmh(lastSpeedKmh)
        refreshStatusBar(chainageM)
        updateDestinationText(chainageM)

        val brakeTest = brakeTestScheduler.update(chainageM, directionSelection.picketsGrowing)
        if (brakeTest != null) {
            // Машинист: "впереди проба тормозов со скорости 45-50 км/ч" — называет вслух годные
            // диапазоны скорости самой пробы, см. doc-комментарий у BrakeTest.validSpeedRangesKmh.
            voiceAnnouncer.playWithSpeedRanges(
                if (brakeTest.doubleTrain) Announcement.BRAKE_TEST_DOUBLE else Announcement.BRAKE_TEST,
                brakeTest.validSpeedRangesKmh
            )
        }

        val forward = directionSelection.picketsGrowing
        if (crossingFarScheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.CROSSING_FAR)
        if (crossingNear800Scheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.CROSSING_NEAR)
        if (crossingNear300Scheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.CROSSING_NEAR)
        // Машинист: "впереди временное ограничение скорости" + сама цифра (например, "40
        // километров в час") — см. doc-комментарий у VoiceAnnouncer.playWithSpeed.
        val tempLimit = tempLimitScheduler.update(chainageM, forward)
        if (tempLimit != null) voiceAnnouncer.playWithSpeed(Announcement.TEMP_LIMIT, tempLimit.speedKmh)
        if (washoutScheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.WASHOUT)
        if (ktsmScheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.KTSM)
        if (airGapScheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.AIR_GAP)
        if (neutralInsertScheduler.update(chainageM, forward) != null) voiceAnnouncer.play(Announcement.NEUTRAL)

        if (stationScheduler.update(chainageM, directionSelection.picketsGrowing) != null) {
            voiceAnnouncer.play(Announcement.STATION)
        }

        if (trainInspectionMachinistScheduler.update(chainageM, forward) != null) {
            voiceAnnouncer.play(Announcement.TRAIN_INSPECTION_MACHINIST)
        }
        if (trainInspectionAssistantScheduler.update(chainageM, forward) != null) {
            voiceAnnouncer.play(Announcement.TRAIN_INSPECTION_ASSISTANT)
        }
    }

    // Секундомер по тапу на время (не из ТЗ/макета — отдельная просьба машиниста): тап на
    // timeText открывает окошко со счётчиком и кнопкой "Сброс" под ним. Счётчик нигде не
    // хранится между открытиями: каждое открытие окошка — это новый отсчёт с нуля, и закрытие
    // окошка (повторный тап) тоже сбрасывает его — кнопка "Сброс" просто делает то же самое,
    // не закрывая окошко.
    private var stopwatchVisible = false
    private var stopwatchStartMs: Long? = null
    private lateinit var stopwatchWindow: View
    private lateinit var stopwatchText: TextView
    private lateinit var stopwatchResetButton: Button

    private fun toggleStopwatch() {
        stopwatchVisible = !stopwatchVisible
        stopwatchStartMs = if (stopwatchVisible) System.currentTimeMillis() else null
        refreshStopwatch()
    }

    private fun resetStopwatch() {
        stopwatchStartMs = System.currentTimeMillis()
        refreshStopwatch()
    }

    private fun refreshStopwatch() {
        stopwatchWindow.visibility = if (stopwatchVisible) View.VISIBLE else View.GONE
        val start = stopwatchStartMs ?: return
        if (stopwatchVisible) {
            stopwatchText.text = formatDuration(System.currentTimeMillis() - start)
        }
    }

    // НАЙДЕНА НАСТОЯЩАЯ ПРИЧИНА того, что уровень сигнала SIM не появлялся никогда, сколько бы
    // раз ни переделывался сам способ его чтения: оба системных диалога разрешений (геолокация
    // и состояние телефона) запускались ОДИН ЗА ДРУГИМ, без паузы, прямо в onCreate — launch()
    // для второго диалога вызывался сразу следующей строкой, не дожидаясь результата первого.
    // Android не умеет показать два диалога разрешений одновременно — пока активен первый
    // (геолокация, он нужен для GPS и явно показывался, раз GPS у машиниста работает), запрос
    // второго диалога (состояние телефона) МОГ просто потеряться/быть проигнорирован системой.
    // Поэтому ни одно из прошлых исправлений самого чтения сигнала не могло сработать: сам
    // диалог с просьбой разрешения машинист, скорее всего, никогда не видел и не мог на него
    // ответить. Теперь второй запрос идёт строго ПОСЛЕ того, как первый уже разрешился (см.
    // requestPhoneStatePermissionIfNeeded, вызывается и из колбэка геолокации, и напрямую из
    // onCreate, если геолокация уже была разрешена раньше и первый диалог вовсе не показывается).
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                coordinateText.text = getString(R.string.location_permission_denied)
            }
            requestNotificationPermissionIfNeeded()
        }

    // Без POST_NOTIFICATIONS (API 33+) постоянное уведомление GpsForegroundService не
    // показывается вообще — сам GPS-сервис при этом всё равно продолжает работать, уведомление
    // просто невидимо, поэтому результат этого запроса ни на что не влияет, кроме перехода
    // дальше по цепочке разрешений (см. doc-комментарий у requestPermissionLauncher про то,
    // почему диалоги идут строго по одному, а не все разом).
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestPhoneStatePermissionIfNeeded()
        }

    private fun requestNotificationPermissionIfNeeded() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestPhoneStatePermissionIfNeeded()
        } else {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Разрешение на уровень сигнала SIM (ТЗ раздел 7, шапка) — отдельное от геолокации,
    // поэтому отдельный launcher; если машинист откажет — просто остаётся "—" в шапке,
    // ничего не падает.
    private val requestPhoneStatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPhoneSignalUpdates()
            // Подсказка про батарею/автозапуск — показываем в самом конце цепочки разрешений
            // (см. doc-комментарий у requestPermissionLauncher), чтобы не перекрыться с ними.
            BatteryOptimizationHelper.maybeAsk(this)
        }

    private fun requestPhoneStatePermissionIfNeeded() {
        if (hasPhoneStatePermission()) {
            startPhoneSignalUpdates()
            BatteryOptimizationHelper.maybeAsk(this)
        } else {
            requestPhoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ручная поправка GPS (chainageOffsetM, кнопка "Сигнал") НИКОГДА нигде не сохраняется
        // (ни в SharedPreferences, ни в savedInstanceState) — машинист явно потребовал: "после
        // перезапуска приложения, чтобы опять просто ловило через GPS... чтобы не убегал,
        // чтобы нельзя было неправильно сделать". Явно обнуляем её здесь же, в onCreate (а не
        // полагаемся молча на то, что это просто начальное значение поля) — чтобы при любом
        // будущем изменении кода это требование нельзя было случайно нарушить, попутно добавив
        // какую-то форму сохранения состояния. Хватает перезапуска процесса (например, смахнуть
        // приложение из списка недавних и открыть заново) — onCreate вызывается заново, offset
        // снова 0.0.
        chainageOffsetM = 0.0
        // Экран не должен гаснуть сам, пока приложение открыто (машинист — заметка из
        // ОТЛОЖЕНО.md). Обычный способ через android:keepScreenOn на корневом View работал
        // бы так же, но FLAG_KEEP_SCREEN_ON на самом окне надёжнее — действует даже если
        // корневой layout ещё не успел отрисоваться.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemUi()

        coordinateText = findViewById(R.id.coordinateText)
        gpsStatusText = findViewById(R.id.gpsStatusText)
        destinationText = findViewById(R.id.destinationText)
        timeText = findViewById(R.id.timeText)
        stopwatchWindow = findViewById(R.id.stopwatchWindow)
        stopwatchText = findViewById(R.id.stopwatchText)
        stopwatchResetButton = findViewById(R.id.stopwatchResetButton)
        signalSyncButton = findViewById(R.id.signalSyncButton)
        signalSyncButton.setOnClickListener { syncToNearestSignal() }
        statusUklonText = findViewById(R.id.statusUklonText)
        statusVText = findViewById(R.id.statusVText)
        statusSText = findViewById(R.id.statusSText)
        statusHeadText = findViewById(R.id.statusHeadText)
        statusTailText = findViewById(R.id.statusTailText)
        directionButton = findViewById(R.id.directionButton)
        pathButton = findViewById(R.id.pathButton)
        k1Button = findViewById(R.id.k1Button)
        menuButton = findViewById(R.id.menuButton)
        recenterButton = findViewById(R.id.recenterButton)
        restrictionsButton = findViewById(R.id.restrictionsButton)
        dayNightButton = findViewById(R.id.dayNightButton)
        simSlot0Group = findViewById(R.id.simSlot0Group)
        simSlot0Bars = findViewById(R.id.simSlot0Bars)
        simSlot0Text = findViewById(R.id.simSlot0Text)
        simSlot1Group = findViewById(R.id.simSlot1Group)
        simSlot1Bars = findViewById(R.id.simSlot1Bars)
        simSlot1Text = findViewById(R.id.simSlot1Text)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryText = findViewById(R.id.batteryText)
        trackProfileView = findViewById(R.id.trackProfileView)
        arrivalWindow = findViewById(R.id.arrivalWindow)
        arrivalTimeText = findViewById(R.id.arrivalTimeText)
        arrivalMidText = findViewById(R.id.arrivalMidText)
        arrivalDepartureText = findViewById(R.id.arrivalDepartureText)
        arrivalToggleButton = findViewById(R.id.arrivalToggleButton)
        historyPanel = findViewById(R.id.stopHistoryPanel)
        historyRowsContainer = findViewById(R.id.stopHistoryRows)
        historyToggleButton = findViewById(R.id.historyToggleButton)
        // STREAM_ALARM, не STREAM_NOTIFICATION — машинист проверил на телефоне: 25 минут
        // стоянки прошло, сигнал через уведомления не прозвучал вообще (у телефонов часто
        // отдельная, нередко приглушённая/выключенная громкость уведомлений, отдельная от
        // громкости будильника/звонка). Громкость будильника — отдельный канал, обычно всегда
        // слышимый и не затронутый тихим режимом/режимом "Не беспокоить".
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        gpsBeepToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)

        // "Координата (км+пк, по тапу переключается в формат КЛУБ-У)" — ТЗ раздел 7.
        coordinateText.setOnClickListener {
            showKlubU = !showKlubU
            coordinateText.text = formatCoordinate(lastChainageM)
        }

        // Секундомер по тапу на время — отдельная просьба машиниста, см. doc-комментарий у
        // toggleStopwatch/stopwatchWindow.
        timeText.setOnClickListener { toggleStopwatch() }
        stopwatchResetButton.setOnClickListener { resetStopwatch() }

        trainParams = trainParamsStore.load()
        trackProfileView.setTrainLengthM(trainParams.composition.lengthM)
        reloadRouteData()
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
        // К1 (сигналы среднего пути Тургутуй—Яблоновая/Карымская) — кнопка пока без реальной
        // логики переключения путей (см. doc-комментарий в activity_main.xml/README), поэтому
        // только сообщает об этом, а не молчит без всякой реакции на нажатие.
        k1Button.setOnClickListener {
            Toast.makeText(this, "К1: логика переключения путей ещё не готова", Toast.LENGTH_SHORT).show()
        }
        menuButton.setOnClickListener { startActivity(Intent(this, AppMenuActivity::class.java)) }
        recenterButton.setOnClickListener { trackProfileView.recenter() }
        restrictionsButton.setOnClickListener { openRestrictionsScreen() }
        dayNightButton.setOnClickListener {
            isDayMode = !isDayMode
            dayNightButton.text = if (isDayMode) "☀️" else "🌙"
            trackProfileView.setDayMode(isDayMode)
        }
        arrivalToggleButton.setOnClickListener {
            arrivalWindowVisible = !arrivalWindowVisible
            refreshArrivalWindow()
        }
        historyToggleButton.setOnClickListener {
            historyPanelVisible = !historyPanelVisible
            refreshStopHistory()
        }
        // Отдельный вызов updateDirectionUi() здесь был раньше нужен, чтобы один раз
        // проставить текст кнопок/график сразу после загрузки данных — теперь это делает сам
        // reloadRouteData() выше, а следом сразу же вызовется onResume() (обычный жизненный
        // цикл Activity), который его и так вызовет ещё раз — отдельного вызова не нужно.

        if (hasLocationPermission()) {
            startLocationUpdates()
            // Геолокация уже разрешена раньше — её диалог сейчас не покажется, значит можно
            // сразу просить следующее разрешение по цепочке, гонки нет (см. doc-комментарий у
            // requestPermissionLauncher выше).
            requestNotificationPermissionIfNeeded()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            // Разрешение на состояние телефона запросится ПОСЛЕ того, как машинист ответит на
            // этот диалог — из колбэка requestPermissionLauncher, а не прямо здесь.
        }
    }

    // Ограничения/параметры состава/любые данные из "База данных" теперь вводятся или
    // обновляются на отдельных экранах (RestrictionsActivity/TrainParamsActivity/
    // DatabaseActivity) — после возврата оттуда нужно заново перечитать всё, что эти экраны
    // могли изменить (TrainParamsActivity/DataOverrideStore сохраняют напрямую, эта копия в
    // памяти о том не узнает сама) и отрисовать график с учётом изменений. reloadRouteData()
    // сама вызывает refreshTrackProfileRestrictions() (через updateDirectionUi()), отдельно
    // вызывать её здесь больше не нужно.
    override fun onResume() {
        super.onResume()
        trainParams = trainParamsStore.load()
        trackProfileView.setTrainLengthM(trainParams.composition.lengthM)
        reloadRouteData()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Системная панель на некоторых телефонах ненадолго возвращается после сворачивания/
        // разворачивания приложения — прячем её снова при каждом получении фокуса (стандартный
        // приём для sticky-immersive режима).
        if (hasFocus) hideSystemUi()
    }

    // Приложение должно открываться во весь экран (машинист): своя шапка теперь сама
    // показывает время и уровень сигнала SIM, поэтому системная строка состояния наверху
    // (и системная навигационная панель снизу) больше не нужны.
    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    // До двух SIM-карт (машинист: "у меня стоит на телефоне два, чтобы я их видел") —
    // SubscriptionManager.getActiveSubscriptionInfoList() отдаёт реально вставленные/активные
    // SIM, TelephonyManager.createForSubscriptionId() привязывает отдельный TelephonyManager
    // к каждой из них, чтобы слушать именно её силу сигнала, а не общую "сим по умолчанию".
    // Если список подписок недоступен (разрешение/прошивка) — используем старое поведение:
    // один слот с SIM по умолчанию.
    @SuppressLint("MissingPermission") // проверяется явно в hasPhoneStatePermission()
    private fun startPhoneSignalUpdates() {
        val baseManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return

        // Машинист: на планшете SIM-карты физически нет вообще ("обычный, без симки") — раз
        // её нет, даже не пытаемся читать сигнал/подписки, просто скрываем оба слота целиком,
        // а не показываем пустые прочерки. getSimState() без аргумента — состояние SIM в слоте
        // по умолчанию; для устройства без единой SIM-карты этого достаточно (на дуал-сим
        // телефоне машиниста, где обе SIM реальные, он как и раньше вернёт SIM_STATE_READY, и
        // код ниже отработает как обычно).
        val simState = try {
            baseManager.simState
        } catch (e: Throwable) {
            TelephonyManager.SIM_STATE_UNKNOWN
        }
        if (simState != TelephonyManager.SIM_STATE_READY) {
            simSlot0Group.visibility = View.GONE
            simSlot1Group.visibility = View.GONE
            return
        }

        val subscriptionManager = getSystemService(SubscriptionManager::class.java)
        val activeSubs = try {
            subscriptionManager?.activeSubscriptionInfoList
        } catch (e: Throwable) {
            // Throwable, не Exception — см. doc-комментарий у pollSimSignal: на некоторых
            // прошивках телефонные API бросают и NoSuchMethodError (Error, не Exception), не
            // только SecurityException. Без этого один такой сбой уронил бы приложение
            // целиком, т.к. эта функция вызывается из onCreate.
            null
        }

        if (activeSubs.isNullOrEmpty()) {
            simSlot1Group.visibility = View.GONE
            safeBindSimSlot(simSlot0Bars, simSlot0Text, baseManager)
            return
        }

        // Каждый слот оборачиваем в свой try/catch (safeBindSimSlot) — раньше, если бы
        // настройка первой SIM чем-то не понравилась телефону и бросила исключение, вторая
        // SIM вообще не получила бы шанса — вызов bindSimSlot для неё просто не выполнился бы,
        // т.к. весь startPhoneSignalUpdates прервался бы на первой же ошибке.
        safeBindSimSlot(simSlot0Bars, simSlot0Text, baseManager.createForSubscriptionId(activeSubs[0].subscriptionId))
        if (activeSubs.size >= 2) {
            simSlot1Group.visibility = View.VISIBLE
            safeBindSimSlot(simSlot1Bars, simSlot1Text, baseManager.createForSubscriptionId(activeSubs[1].subscriptionId))
        } else {
            simSlot1Group.visibility = View.GONE
        }
    }

    private fun safeBindSimSlot(barsView: SignalBarsView, textView: TextView, manager: TelephonyManager) {
        try {
            bindSimSlot(barsView, textView, manager)
        } catch (e: Throwable) {
            // Throwable, не Exception — см. doc-комментарий у pollSimSignal: на планшете
            // машиниста (Android 10) реальный Logcat показал NoSuchMethodError (метода из
            // системного API на этой прошивке физически нет) — а NoSuchMethodError это Error,
            // не Exception, обычный catch (Exception) его пропускал мимо и ронял всё
            // приложение прямо при запуске ("тёмный экран и вылет", машинист прислал Logcat).
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // проверяется явно в hasPhoneStatePermission()
    private fun bindSimSlot(barsView: SignalBarsView, textView: TextView, manager: TelephonyManager) {
        // PhoneStateListener.listen() официально устарел с Android 12 (API 31) — на свежих
        // версиях Android заменён на TelephonyCallback. Машинист подтвердил: телефон свежий
        // (Android 16/17), так что регистрируем именно новый API — он активно поддерживается
        // производителем прошивки, в отличие от оставленного только для старых телефонов
        // устаревшего метода ниже.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    barsView.level = signalStrength.level
                }
            }
            manager.registerTelephonyCallback(mainExecutor, callback)
            activeTelephonyCallbacks.add(manager to callback)
        } else {
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    barsView.level = signalStrength.level
                }
            }
            manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            activeSimListeners.add(manager to listener)
        }
        activeSimPolls.add(manager to barsView)
        textView.text = networkTypeLabel(manager.networkType)
        pollSimSignal(manager, barsView)
    }

    private val activeTelephonyCallbacks = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()

    // onSignalStrengthsChanged выше срабатывает только на ИЗМЕНЕНИЕ сигнала — если он уже
    // стабилен к моменту запуска приложения, событие может вообще не прийти, и столбики так и
    // останутся на нуле (машинист: "опять не показывает уровень сигнала сим-карты", несмотря
    // на уже сделанное одноразовое чтение сразу после подписки — похоже, на его телефоне и
    // этого было недостаточно). Поэтому вдобавок перечитываем ТЕКУЩЕЕ значение каждую секунду
    // из timeUpdater (см. refreshSimSignalPolling), а не полагаемся только на событие.
    private val activeSimPolls = mutableListOf<Pair<TelephonyManager, SignalBarsView>>()

    // ИСПРАВЛЕНИЕ ПОСЛЕ ПОВТОРНОЙ ПРОВЕРКИ: в прошлый раз здесь было написано, что
    // CellInfo.getCellSignalStrength() (без приведения к конкретному подклассу вроде
    // CellInfoLte) работает аж с API 17 — это неверно. Общий абстрактный CellSignalStrength
    // (из которого и берётся .level) появился только в Android 10 (API 29), вместе с
    // getSignalStrength() — то есть оба способа ниже на самом деле требуют одну и ту же
    // версию Android, и преимущество getAllCellInfo() не в "работает на старых телефонах"
    // (это было ошибкой), а в том, что это НЕЗАВИСИМЫЙ второй способ прочитать значение
    // ПРЯМО СЕЙЧАС, не дожидаясь события "сигнал изменился" — если на конкретной прошивке
    // барахлит один из двух API, есть шанс, что сработает другой. У машиниста телефон
    // заведомо современный (Android 16/17), так что для него оба пути доступны.
    @Suppress("DEPRECATION")
    private fun pollSimSignal(manager: TelephonyManager, barsView: SignalBarsView) {
        // ВАЖНО: эта функция вызывается каждую секунду из timeUpdater. Телефонные API на
        // некоторых прошивках бросают не только SecurityException, а что угодно (проверено на
        // практике) — если здесь вылетит необработанное исключение, оно уронит весь таймер
        // обновлений (а с ним — скорость, объявления, график) на главном потоке, не только
        // столбики сигнала. Ловим Throwable, а НЕ Exception — машинист прислал реальный Logcat
        // со своего планшета (Android 10, "тёмный экран и вылет при запуске"): там именно
        // java.lang.NoSuchMethodError на cellSignalStrength.getCellSignalStrength() — метода
        // из системного API на этой конкретной прошивке физически нет, хотя версия Android
        // формально его поддерживает. NoSuchMethodError — это Error, не Exception (они оба
        // наследуются от Throwable, но НЕ друг от друга), и catch (Exception) пропускал его
        // мимо — именно поэтому прошлая подстраховка не защитила от этого краха.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val level = try {
                val cells = manager.allCellInfo
                (cells?.firstOrNull { it.isRegistered } ?: cells?.firstOrNull())?.cellSignalStrength?.level
            } catch (e: Throwable) {
                null
            }
            if (level != null) {
                barsView.level = level
                return
            }
            try {
                manager.signalStrength?.let { barsView.level = it.level }
            } catch (e: Throwable) {
                // см. комментарий выше — та же подстраховка.
            }
        }
    }

    private fun refreshSimSignalPolling() {
        for ((manager, barsView) in activeSimPolls) pollSimSignal(manager, barsView)
    }

    /** Заряд батареи телефона в шапке (машинист, не из ТЗ/макета) — getIntProperty читает
     *  текущее значение сразу, без регистрации BroadcastReceiver на ACTION_BATTERY_CHANGED,
     *  тем же простым способом, что и остальная шапка (раз в секунду из timeUpdater). */
    private fun refreshBatteryLevel() {
        val batteryManager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) {
            batteryIcon.level = level
            batteryText.text = "$level%"
        }
    }

    private fun networkTypeLabel(networkType: Int): String = when (networkType) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        else -> "—"
    }

    override fun onDestroy() {
        GpsForegroundService.listener = null
        stopService(Intent(this, GpsForegroundService::class.java))
        timeHandler.removeCallbacks(timeUpdater)
        voiceAnnouncer.release()
        for (beep in pendingStopAlarmBeeps) timeHandler.removeCallbacks(beep)
        toneGenerator.release()
        gpsBeepToneGenerator.release()
        @Suppress("DEPRECATION")
        for ((manager, listener) in activeSimListeners) {
            manager.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for ((manager, callback) in activeTelephonyCallbacks) {
                manager.unregisterTelephonyCallback(callback)
            }
        }
        super.onDestroy()
    }

    // ТЗ раздел 10. Показывается только пока state != Hidden И машинист не скрыл окно кнопкой.
    // "—:—:—" для отправления, пока поезд стоит (ещё не уехал) — как в HTML-макете/ТЗ-раскадровке.
    private fun refreshArrivalWindow() {
        val s = arrivalDepartureTracker.state
        val visible = arrivalWindowVisible && s !is ArrivalDepartureState.Hidden
        arrivalWindow.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        // "Приб.:"/"Отпр.:" вместо полных слов (машинист попросил: окошко было слишком
        // широкое, нужно уже, а не ниже).
        when (s) {
            is ArrivalDepartureState.Stopped -> {
                arrivalTimeText.text = "Приб.: ${arrivalWallFormat.format(Date(s.arrivalTime))}"
                arrivalMidText.text = formatDuration(System.currentTimeMillis() - s.arrivalTime)
                arrivalDepartureText.text = "Отпр.: —:—:—"
            }
            is ArrivalDepartureState.JustDeparted -> {
                arrivalTimeText.text = "Приб.: ${arrivalWallFormat.format(Date(s.arrivalTime))}"
                // Раньше было "${...} → итог" — машинист: "убери эту надпись 'Итог'... она
                // там вообще не нужна, стрелка и 'Итог'. И она увеличивает только окошко, я и
                // так знаю, что это значит".
                arrivalMidText.text = formatDuration(s.stopDurationMs)
                arrivalDepartureText.text = "Отпр.: ${arrivalWallFormat.format(Date(s.departureTime))}"
            }
            is ArrivalDepartureState.Hidden -> Unit
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    // ТЗ раздел 10: "Место стоянки" | Приб | Отпр | "Время стоянки", одна компактная строка на
    // остановку. Панель полупрозрачная — не модалка (см. arrivalWindow выше), пересобирается
    // целиком на каждый тик, пока видна (список коротких, накладных расходов не создаёт).
    private fun refreshStopHistory() {
        historyPanel.visibility = if (historyPanelVisible) View.VISIBLE else View.GONE
        if (!historyPanelVisible) return

        historyRowsContainer.removeAllViews()
        for (rec in arrivalDepartureTracker.history) {
            val place = nearestStationName(rec.locationM, directionSelection.effectiveDataset, stations)
                ?: ChainageFormatter.format(rec.locationM)
            val row = TextView(this).apply {
                text = "%-14s %5s %5s %8s".format(
                    place,
                    arrivalWallFormat.format(Date(rec.arrivalTime)),
                    arrivalWallFormat.format(Date(rec.departureTime)),
                    formatDuration(rec.durationMs)
                )
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            historyRowsContainer.addView(row)
        }
    }

    /**
     * (Пере)читает ВСЕ справочные данные (сигналы/станции/скорости/КТСМ/переезды/... — всё, что
     * можно обновить через "База данных", см. DataOverrideStore.Category) и применяет их — к
     * самому графику (TrackProfileView.set*) и ко всем голосовым планировщикам (пересобираются
     * внутри updateDirectionUi()). Вызывается и при запуске (onCreate), и на каждом onResume —
     * машинист: "если я файл изменил... в базе данных, и у меня в приложении тоже оно
     * поменялось". MainActivity не уничтожается при переходе на "База данных" и обратно (это
     * просто соседняя Activity в том же процессе) — без принудительной перезагрузки здесь
     * старые данные держались бы в памяти до перезапуска всего приложения, несмотря на новый
     * файл (см. doc-комментарий у stations выше про то, почему это больше не "by lazy").
     */
    private fun reloadRouteData() {
        stations = StationAssetLoader.loadStations(this)
        signals = SignalAssetLoader.loadMainTrackSignals(this)
        blockPosts = BlockPostAssetLoader.loadBlockPosts(this)
        relief = ReliefAssetLoader.loadRelief(this)
        crossings = CrossingAssetLoader.loadCrossings(this)
        airGaps = AirGapAssetLoader.loadAirGaps(this)
        washouts = WashoutAssetLoader.loadWashouts(this)
        neutralInserts = NeutralInsertAssetLoader.loadNeutralInserts(this)
        brakeTests = BrakeTestAssetLoader.loadBrakeTests(this)
        ktsmPoints = KtsmAssetLoader.loadKtsm(this)
        stationAnnouncements = StationAnnouncementAssetLoader.loadStationAnnouncements(this)
        trainInspectionPoints = TrainInspectionAssetLoader.loadTrainInspectionPoints(this)

        // Калибровка — кусочно-линейная по опорным точкам (см. doc-комментарий класса
        // RouteTrack) — оба файла (линия маршрута и опорные точки) пересчитываются друг
        // относительно друга заново при каждой загрузке, так что обновление ЛЮБОГО из них
        // через "База данных" остаётся согласованным само по себе.
        routeTrack = RouteTrack(
            rawPoints = RouteAssetLoader.loadLatLonList(this, RouteTrack.MAIN_ROUTE_ASSET),
            anchors = RouteAnchorAssetLoader.loadAnchors(this, RouteTrack.MAIN_ANCHORS_ASSET)
        )

        speedLimits = SpeedLimitAssetLoader.loadSpeedLimits(this)
        speedLimitResolver = SpeedLimitResolver(speedLimits)

        trackProfileView.setSpeedLimits(speedLimits)
        trackProfileView.setStations(stations)
        trackProfileView.setSignals(signals)
        trackProfileView.setBlockPosts(blockPosts)
        trackProfileView.setRelief(relief)
        trackProfileView.setCrossings(crossings)
        trackProfileView.setAirGaps(airGaps)
        trackProfileView.setNeutralInserts(neutralInserts)
        trackProfileView.setWashouts(washouts)
        trackProfileView.setBrakeTests(brakeTests)
        trackProfileView.setKtsm(ktsmPoints)

        // Пересобирает ВСЕ планировщики голосовых оповещений (проба тормозов/КТСМ/переезды/
        // станции/нейтральная вставка/осмотр поезда/временные ограничения) из свежих списков
        // выше, и обновляет шапку/график — см. doc-комментарий у updateDirectionUi.
        updateDirectionUi()
    }

    private fun updateDirectionUi() {
        val s = directionSelection
        // Короче, чем "Чётное"/"Нечётное" — машинист попросил, чтобы влезало компактнее.
        directionButton.text = if (s.direction == Direction.EVEN) "Чёт" else "Нечёт"
        pathButton.text = "П${s.physicalPath}"
        updateDestinationText(lastChainageM)

        trackProfileView.setDirection(s.effectiveDataset)
        // Нейтральная вставка — по направлению движения как есть, без поправки на
        // неправильный путь (см. doc-комментарий NeutralInsert/TrackProfileView.setRawDirection).
        trackProfileView.setRawDirection(s.direction)
        // Римский номер физического пути в табличке сигнала — только на неправильном пути
        // (машинист: "13 (I) - 200"), см. doc-комментарий у TrackProfileView.isWrongPath.
        trackProfileView.setPathStatus(s.pathStatus == PathStatus.WRONG, s.physicalPath)

        // Смена направления/пути меняет, какой датасет действует (effectiveDataset) — значит
        // и какие именно точки впереди по пути вообще относятся к делу; данные тоже могли
        // обновиться (см. reloadRouteData). updatePoints (не пересоздание объекта) сохраняет
        // "уже объявлено" для точек, которых это не коснулось — см. doc-комментарий у
        // PointAnnouncementScheduler.updatePoints.
        val relevantBrakeTests = brakeTests.filter { it.direction == s.effectiveDataset }
        brakeTestScheduler.updatePoints(relevantBrakeTests)

        val relevantKtsm = ktsmPoints.filter { it.direction == s.effectiveDataset }
        ktsmScheduler.updatePoints(relevantKtsm)

        // Направление КАК ЕСТЬ (s.direction), не effectiveDataset — см. doc-комментарий у поля.
        val relevantNeutralInserts = neutralInserts.filter { it.direction == s.direction }
        neutralInsertScheduler.updatePoints(relevantNeutralInserts)

        val relevantStations = stationAnnouncements.filter { it.direction == s.effectiveDataset }
        stationScheduler.updatePoints(relevantStations)

        val relevantCrossings = crossings.filter { it.direction == s.effectiveDataset }
        crossingFarScheduler.updatePoints(relevantCrossings)
        crossingNear800Scheduler.updatePoints(relevantCrossings)
        crossingNear300Scheduler.updatePoints(relevantCrossings)

        val relevantAirGaps = airGaps.filter { it.direction == s.effectiveDataset }
        airGapScheduler.updatePoints(relevantAirGaps)

        val relevantWashouts = washouts.filter { it.direction == s.effectiveDataset }
        washoutScheduler.updatePoints(relevantWashouts)

        val relevantInspectionPoints = trainInspectionPoints.filter { it.direction == s.effectiveDataset }
        trainInspectionMachinistScheduler.updatePoints(relevantInspectionPoints.filter { it.inspector == InspectorRole.MACHINIST })
        trainInspectionAssistantScheduler.updatePoints(relevantInspectionPoints.filter { it.inspector == InspectorRole.ASSISTANT })

        refreshTrackProfileRestrictions()
    }

    // Станция назначения в шапке — следующая станция ПО ХОДУ движения (не конечная станция
    // всего маршрута, машинист поправил), пересчитывается и при смене направления/пути
    // (updateDirectionUi), и на каждой GPS-позиции (startLocationUpdates) — см. doc-комментарий
    // у nextStationAhead.
    private fun updateDestinationText(chainageM: Double) {
        val s = directionSelection
        val next = nextStationAhead(chainageM, s.effectiveDataset, s.picketsGrowing, stations)
        // Принудительный перенос по дефису нужен только длинным составным названиям
        // (например, "Чернышевск-Забайкальский") — у коротких, вроде "Чита-1"/"Чита-2", он
        // ломал название пополам без всякой нужды (машинист прислал скриншот с "Чита-"/"1"
        // на двух строках).
        destinationText.text = when {
            next == null -> "—"
            next.length > 10 -> next.replaceFirst("-", "-\n")
            else -> next
        }
    }

    // "Ограничения" (ТЗ раздел 11) — отдельный полноэкранный экран (RestrictionsActivity), а не
    // диалог поверх главного: ни AlertDialog, ни полноэкранный Dialog не удалось сделать
    // надёжно прокручиваемым на невысоком альбомном экране — клавиатура(своя цифровая)
    // раз за разом оставалась обрезанной (см. doc-комментарий в RestrictionsActivity.kt и
    // activity_restrictions.xml). Список ограничений общий через RestrictionsStore, поэтому
    // при возврате с этого экрана нужно обновить график — см. onResume() ниже.
    private fun openRestrictionsScreen() {
        startActivity(
            Intent(this, RestrictionsActivity::class.java)
                .putExtra(RestrictionsActivity.EXTRA_EFFECTIVE_DATASET, directionSelection.effectiveDataset.name)
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // проверяется явно в hasLocationPermission()
    private fun startLocationUpdates() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            coordinateText.text = getString(R.string.gps_provider_disabled)
        }

        if (!hasLocationPermission()) return

        // Сама подписка на LocationManager теперь живёт в GpsForegroundService (foreground-
        // сервис с постоянным уведомлением + wake lock), а не прямо в Activity — см.
        // doc-комментарий в GpsForegroundService.kt про то, почему. Слушатель подставляется
        // СЮДА же, до запуска сервиса — тело колбэка не изменилось, кроме новой защиты от
        // скачков/плохой точности (isPlausibleGpsFix).
        GpsForegroundService.listener = { location, source ->
            // + chainageOffsetM — ручная поправка по кнопке "Сигнал" (машинист: GPS на этом
            // участке врёт СИСТЕМАТИЧЕСКИ, метров на 200-300, не случайным шумом — значит,
            // поправку надо прибавлять к КАЖДОЙ дальнейшей координате, а не один раз подставить
            // правильное значение, которое тут же перезапишет следующий такой же неверный фикс).
            // См. doc-комментарий у chainageOffsetM/syncToNearestSignal.
            val chainageM = routeTrack.chainageMetersFor(location.latitude, location.longitude) + chainageOffsetM

            // Однократный сигнал ровно в момент переключения GPS<->резервный источник — не на
            // каждый колбэк резервного источника подряд (их может быть много, пока GPS не
            // вернётся), а только один раз на САМ переход, см. doc-комментарий у
            // lastLocationWasGps.
            val isGpsNow = source != LocationSource.NETWORK
            val previouslyWasGps = lastLocationWasGps
            if (previouslyWasGps != null && previouslyWasGps != isGpsNow) {
                playGpsTransitionBeep()
            }
            lastLocationWasGps = isGpsNow

            if (source == LocationSource.NETWORK) {
                // Резервный источник (по сотовым вышкам/Wi-Fi) — машинист попросил, чтобы
                // экран не оставался совсем пустым при пропаже GPS. НАМНОГО грубее настоящего
                // GPS (сотни метров — километр), поэтому используем его ТОЛЬКО для текста
                // координаты, с явной пометкой неточности — график, объявления, V=/S= и
                // остальное продолжают показывать последнюю настоящую GPS-позицию, а не эту
                // грубую оценку (см. doc-комментарий GpsLocationProvider). Статус — ОТДЕЛЬНОЙ
                // маленькой строкой под координатой (gpsStatusText), а не дописан в саму
                // координату: раньше это была одна длинная строка, и шапка целиком уезжала
                // вправо, время с SIM-индикатором пропадали за край экрана (машинист поправил).
                coordinateText.text = formatCoordinate(chainageM)
                val accuracyM = if (location.hasAccuracy()) " ±${location.accuracy.toInt()}м" else ""
                gpsStatusText.text = "нет GPS$accuracyM"
            } else if (isPlausibleGpsFix(location, chainageM)) {
                // Только запоминаем настоящую GPS-точку — график/объявления/статусную строку
                // отсюда больше не трогаем, они теперь считаются "от головы поезда" раз в
                // секунду из timeUpdater (см. estimatedChainageM/updateTrainPositionDerivedState
                // и doc-комментарий у lastChainageM) — это даёт им продолжать двигаться
                // по инерции, даже если GPS на несколько секунд пропадёт.
                lastChainageM = chainageM
                if (location.hasSpeed()) lastSpeedKmh = location.speed * 3.6
                lastGpsFixAtMs = System.currentTimeMillis()
                hasGpsFix = true
                coordinateText.text = formatCoordinate(chainageM)
                gpsStatusText.text = ""
            }
            // else: плохая точность или неправдоподобный скачок пикетажа — фикс просто
            // игнорируется, см. doc-комментарий у isPlausibleGpsFix.
        }
        ContextCompat.startForegroundService(this, Intent(this, GpsForegroundService::class.java))
    }

    /** Ближайший ещё не пройденный сигнал в направлении движения от точки [fromM] — для "Голова"
     *  это голова поезда (chainageM), для "Хвост" — хвост (chainageM минус длина состава, та же
     *  формула, что и у TrackProfileView при рисовании хвостового маркера на графике). */
    private fun distanceToNextSignalM(fromM: Double, forward: Boolean): Double? {
        // isVisibleFor, а не сырой Signal.direction и не просто effectiveDirection — см.
        // doc-комментарий у Signal.isVisibleFor (Signal.kt): сырой тег означает только "из
        // какого PDF-документа", а ЧД/НД вообще особый случай (показываются только на
        // неправильном пути, не по обычному совпадению направления).
        val relevant = signals.filter {
            it.isVisibleFor(directionSelection.effectiveDataset, directionSelection.direction)
        }
        val nextSignal = if (forward) {
            relevant.filter { it.chainageM > fromM }.minByOrNull { it.chainageM }
        } else {
            relevant.filter { it.chainageM < fromM }.maxByOrNull { it.chainageM }
        }
        return nextSignal?.let { kotlin.math.abs(it.chainageM - fromM) }
    }

    // Кнопка "Сигнал" в нижней статусной строке (машинист, не из ТЗ/макета): координата по
    // GPS на этом участке врёт СИСТЕМАТИЧЕСКИ, метров на 200-300 (машинист проверил в реальной
    // поездке — "если пятый пикет, программа показывает третий, а то и второй"). Машинист
    // уточнил: разового "прыжка" на сигнал мало, т.к. следующий же настоящий фикс GPS снова
    // съедет на ту же ошибку — поправка должна держаться ПОСТОЯННО, прибавляясь к каждой
    // будущей координате, пока машинист не поправит её заново по другому сигналу (chainageOffsetM,
    // см. doc-комментарий там же и в startLocationUpdates).
    private fun syncToNearestSignal() {
        val currentM = estimatedChainageM(System.currentTimeMillis()) ?: lastChainageM
        val relevant = signals.filter {
            it.isVisibleFor(directionSelection.effectiveDataset, directionSelection.direction)
        }
        val nearest = relevant.minByOrNull { kotlin.math.abs(it.chainageM - currentM) }
        val distanceM = nearest?.let { kotlin.math.abs(it.chainageM - currentM) }
        if (nearest == null || distanceM == null || distanceM > 500.0) {
            Toast.makeText(this, "Рядом нет сигнала (в пределах 500 м)", Toast.LENGTH_SHORT).show()
            return
        }

        // currentM уже включает в себя предыдущую поправку (lastChainageM всегда хранится
        // С НЕЙ) — поэтому прибавляем РАЗНИЦУ к уже имеющемуся смещению, а не заменяем его
        // целиком: так последовательные калибровки по нескольким сигналам подряд накапливаются
        // правильно, вместо того чтобы каждая новая "забывала" предыдущую.
        chainageOffsetM += nearest.chainageM - currentM
        lastChainageM = nearest.chainageM
        lastGpsFixAtMs = System.currentTimeMillis()
        hasGpsFix = true
        coordinateText.text = formatCoordinate(nearest.chainageM)
        updateTrainPositionDerivedState(nearest.chainageM)
        Toast.makeText(
            this,
            "Привязано к сигналу ${nearest.name}, ${ChainageFormatter.format(nearest.chainageM)}",
            Toast.LENGTH_LONG
        ).show()
    }

    // Нижняя статусная строка (ТЗ раздел 7): уклон | V= | S= | голова | хвост — каждое
    // значение теперь в своём отдельном секторе (см. statusBarRow в activity_main.xml), а не
    // одной строкой текста, которая вся целиком съезжала влево, стоило где-то одному значению
    // показать "?". Голова/Хвост раньше были "—" — не было данных о км+пк сигналов (см. README),
    // теперь signals (SignalAssetLoader) загружены и используются графиком, так что считаем
    // по-настоящему: "Голова" — сколько осталось голове до сигнала, к которому она идёт; "Хвост" —
    // сколько осталось хвосту до сигнала, который он ещё не прошёл (если состав длинный и сигналы
    // частые, это может быть сигнал, который голова уже проехала — то есть хвост ещё не до конца
    // его освободил). Уклон — из relief (машинист прислал данные с настоящим уклоном в
    // промилле, см. TrackRelief.ReliefProfile.slopeAt) — "—" только вне покрытия профиля.
    private fun refreshStatusBar(chainageM: Double) {
        val slope = relief.slopeAt(directionSelection.effectiveDataset, chainageM)
        statusUklonText.text = "Уклон=" + if (slope != null) formatSlope(slope) else "—"

        // V=/S= должны учитывать и постоянные ограничения (speedLimitResolver), и введённые
        // машинистом временные (restrictions, ТЗ раздел 11) — иначе строка врала бы, если
        // впереди временное ограничение ближе или строже постоянного. Список тот же самый,
        // что уже отфильтрован по текущему пути для графика (refreshTrackProfileRestrictions).
        val effectiveResolver = RestrictionAwareSpeedResolver(
            speedLimitResolver,
            restrictions.filter { it.path == null || it.path == directionSelection.physicalPath }
        )
        val next = effectiveResolver.findNextChange(
            direction = directionSelection.effectiveDataset,
            fromMeters = chainageM,
            forward = directionSelection.picketsGrowing
        )
        statusVText.text = if (next != null) "V=${next.speedKmh}" else "V=?"
        statusSText.text = if (next != null) "S=${next.distanceM.toInt()}м" else "S=?"

        val forward = directionSelection.picketsGrowing
        val tailM = chainageM - trainParams.composition.lengthM
        statusHeadText.text = distanceToNextSignalM(chainageM, forward)?.let { "Голова=${it.toInt()}м" } ?: "Голова=—"
        statusTailText.text = distanceToNextSignalM(tailM, forward)?.let { "Хвост=${it.toInt()}м" } ?: "Хвост=—"
    }
}
