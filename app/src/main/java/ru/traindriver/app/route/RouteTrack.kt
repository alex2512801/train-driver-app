package ru.traindriver.app.route

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Полилиния железнодорожного пути с накопленным километражем (пикетажем) вдоль неё.
 *
 * Линия строится из точек, выгруженных из OpenStreetMap/GPS-трека (см. ТЗ, раздел 1), для
 * реального маршрута. Калибровка в "настоящий" км РЖД — КУСОЧНО-ЛИНЕЙНАЯ по [anchors]
 * (физическая точка lat/lon + её настоящий км), а не единый коэффициент растяжения на весь
 * маршрут (как было раньше, см. ниже) — машинист прислал набор данных ("zabaikalskaya"-архив)
 * с опорными точками через каждые ~15км вместо прежних трёх на весь маршрут.
 *
 * ВАЖНО: сами собственные "real_km_marker" из этого архива для [anchors] НЕ использованы —
 * машинист нашёл, что профиль/уклон на реальном устройстве совсем не совпадает с режимной
 * картой, местами на 2+ км ("вообще никаких совпадений... врёт на 2,5 километра"). Причина
 * найдена: расстояние вдоль присланной трассы (route.csv) и "real_km_marker" из того же архива
 * расходятся друг с другом НАКОПИТЕЛЬНО — на старте почти совпадают, к концу 650-километрового
 * маршрута расхождение доходит до 5-6 км (разная точность/метод измерения на разных частях
 * оцифрованной трассы). Вместо этого [anchors] построены ПО ИМЕНИ СТАНЦИИ — для каждой станции
 * из присланного архива, чьё имя совпало с уже десятки раз проверенным машинистом км+пк станции
 * из track_stations.json (35 совпадающих названий на весь главный маршрут), опорной точкой
 * калибровки стала (её физическая позиция, этот проверенный км+пк) — это даёт точное совпадение
 * ровно в станциях и пренебрежимо малую (не накопительную) ошибку между ними, в отличие от
 * единого коэффициента на весь маршрут (остаточная ошибка в нём доходила до 400+ м на некоторых
 * перегонах) и от немодифицированных "real_km_marker" (см. выше).
 *
 * [anchors] хранят СОБСТВЕННУЮ физическую позицию (lat/lon), а не заранее посчитанный пикетаж
 * по этой линии — раз опорная точка проецируется на [rawPoints] заново при каждой пересборке
 * (см. [RouteTrack] init), обновление ОДНОГО ТОЛЬКО файла точек маршрута или ОДНОГО ТОЛЬКО
 * файла опорных точек через "База данных" не рассинхронизирует калибровку: оба пересчитываются
 * друг относительно друга заново при каждой загрузке (см. MainActivity.reloadRouteData).
 *
 * @param rawPoints точки пути по порядку, от начала маршрута к концу.
 * @param anchors опорные точки калибровки (минимум 2) — реальная физическая точка (lat/lon) и
 *   её настоящий пикетаж (в метрах от начала нумерации РЖД, НЕ от начала этого файла).
 */
class RouteTrack(
    rawPoints: List<LatLon>,
    anchors: List<RouteAnchor>
) {

    /** Опорная точка калибровки — см. doc-комментарий класса. */
    data class RouteAnchor(val lat: Double, val lon: Double, val realChainageM: Double)

    private class Node(val lat: Double, val lon: Double, val rawChainageM: Double)

    private val refLat = rawPoints.first().lat
    private val refLon = rawPoints.first().lon
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))

    private val nodes: List<Node>

    // (сырой пикетаж по этой линии, настоящий пикетаж) — отсортировано по сырому, см.
    // doc-комментарий класса про кусочно-линейную калибровку.
    private val calibration: List<Pair<Double, Double>>

    init {
        require(rawPoints.size >= 2) { "Маршрут должен содержать минимум 2 точки" }
        require(anchors.size >= 2) { "Нужно минимум 2 опорные точки калибровки" }

        var chainage = 0.0
        val built = mutableListOf(Node(rawPoints[0].lat, rawPoints[0].lon, chainage))
        for (i in 1 until rawPoints.size) {
            val prev = toXY(rawPoints[i - 1].lat, rawPoints[i - 1].lon)
            val cur = toXY(rawPoints[i].lat, rawPoints[i].lon)
            chainage += distance(prev, cur)
            built.add(Node(rawPoints[i].lat, rawPoints[i].lon, chainage))
        }
        nodes = built

        calibration = anchors
            .map { rawChainageFor(it.lat, it.lon) to it.realChainageM }
            .sortedBy { it.first }
    }

    /** Пикетаж последней точки маршрута (в метрах, настоящий — после калибровки). */
    val endChainageM: Double get() = toReal(nodes.last().rawChainageM)

    /**
     * Проецирует точку [lat]/[lon] на ближайший отрезок пути и возвращает
     * настоящий пикетаж (в метрах РЖД) этой проекции.
     */
    fun chainageMetersFor(lat: Double, lon: Double): Double = toReal(rawChainageFor(lat, lon))

    /** Сырой (некалиброванный, от начала [rawPoints]) пикетаж ближайшей проекции точки на линию. */
    private fun rawChainageFor(lat: Double, lon: Double): Double {
        val p = toXY(lat, lon)
        var best = nodes.first().rawChainageM
        var bestDistSq = Double.MAX_VALUE

        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            val ax = toXY(a.lat, a.lon)
            val bx = toXY(b.lat, b.lon)

            val dx = bx.first - ax.first
            val dy = bx.second - ax.second
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq > 0.0) {
                (((p.first - ax.first) * dx + (p.second - ax.second) * dy) / lenSq)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val projX = ax.first + t * dx
            val projY = ax.second + t * dy
            val ddx = p.first - projX
            val ddy = p.second - projY
            val distSq = ddx * ddx + ddy * ddy

            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = a.rawChainageM + t * (b.rawChainageM - a.rawChainageM)
            }
        }
        return best
    }

    /** Кусочно-линейная интерполяция/экстраполяция сырого пикетажа в настоящий — см.
     *  doc-комментарий класса. За пределами первой/последней опорной точки — линейная
     *  экстраполяция по наклону крайнего отрезка (лучше, чем обрыв: такие точки по курсу
     *  обычно рядом с самым первым/последним якорем, не за десятки км от него). */
    private fun toReal(raw: Double): Double {
        val (r0, v0, r1, v1) = when {
            raw <= calibration.first().first -> {
                val a = calibration[0]
                val b = calibration[1]
                Quad(a.first, a.second, b.first, b.second)
            }
            raw >= calibration.last().first -> {
                val a = calibration[calibration.size - 2]
                val b = calibration[calibration.size - 1]
                Quad(a.first, a.second, b.first, b.second)
            }
            else -> {
                var lo = 0
                var hi = calibration.size - 1
                while (lo < hi - 1) {
                    val mid = (lo + hi) / 2
                    if (calibration[mid].first <= raw) lo = mid else hi = mid
                }
                val a = calibration[lo]
                val b = calibration[hi]
                Quad(a.first, a.second, b.first, b.second)
            }
        }
        if (r1 == r0) return v0
        val t = (raw - r0) / (r1 - r0)
        return v0 + (v1 - v0) * t
    }

    private data class Quad(val r0: Double, val v0: Double, val r1: Double, val v1: Double)

    private fun toXY(lat: Double, lon: Double): Pair<Double, Double> {
        val x = (lon - refLon) * metersPerDegLon
        val y = (lat - refLat) * metersPerDegLat
        return x to y
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Имя файла в assets/ с точками главного маршрута Хилок — Чернышевск-Забайкальский
         *  (через Читу/Карымскую). */
        const val MAIN_ROUTE_ASSET = "route_main.json"

        /** Имя файла в assets/ с опорными точками калибровки главного маршрута (см.
         *  doc-комментарий класса) — [RouteAnchorAssetLoader]. */
        const val MAIN_ANCHORS_ASSET = "route_main_anchors.json"
    }
}
