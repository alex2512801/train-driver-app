package ru.traindriver.app.route

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Полилиния железнодорожного пути с накопленным километражем (пикетажем) вдоль неё.
 *
 * Линия строится из точек, выгруженных из OpenStreetMap (см. ТЗ, раздел 1). Для реального
 * маршрута перегона Хилок — Карымская — Чернышевск-Забайкальский точки загружаются из
 * assets через [RouteAssetLoader], а константы калибровки лежат в [RouteTrack.Companion].
 *
 * @param rawPoints точки пути по порядку, от начала маршрута к концу.
 * @param startChainageM пикетаж первой точки маршрута (в метрах). Для реального маршрута
 *   это абсолютный км по нумерации РЖД (например, ~5 930 000 м = км 5930), а не 0 —
 *   именно так получаются "настоящие" номера км, а не "км 1" от произвольного начала.
 * @param lengthCorrectionFactor поправочный коэффициент на длину, которым компенсируется
 *   систематическое укорочение линии при её упрощении в OSM (сглаженные кривые короче
 *   реальных). Подобран по разнице между длиной этой полилинии и разницей реальных
 *   км в начале и конце маршрута (см. профиль пути) — грубая оценка, а не точная калибровка.
 */
class RouteTrack(
    rawPoints: List<LatLon>,
    startChainageM: Double = 0.0,
    private val lengthCorrectionFactor: Double = 1.0
) {

    private class Node(val lat: Double, val lon: Double, val chainageM: Double)

    private val refLat = rawPoints.first().lat
    private val refLon = rawPoints.first().lon
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))

    private val nodes: List<Node>

    init {
        require(rawPoints.size >= 2) { "Маршрут должен содержать минимум 2 точки" }
        var chainage = startChainageM
        val built = mutableListOf(Node(rawPoints[0].lat, rawPoints[0].lon, chainage))
        for (i in 1 until rawPoints.size) {
            val prev = toXY(rawPoints[i - 1].lat, rawPoints[i - 1].lon)
            val cur = toXY(rawPoints[i].lat, rawPoints[i].lon)
            chainage += distance(prev, cur) * lengthCorrectionFactor
            built.add(Node(rawPoints[i].lat, rawPoints[i].lon, chainage))
        }
        nodes = built
    }

    /** Пикетаж последней точки маршрута (в метрах, с учётом [startChainageM]). */
    val endChainageM: Double get() = nodes.last().chainageM

    /**
     * Проецирует точку [lat]/[lon] на ближайший отрезок пути и возвращает
     * пикетаж (в метрах от начала маршрута) этой проекции.
     */
    fun chainageMetersFor(lat: Double, lon: Double): Double {
        val p = toXY(lat, lon)
        var best = nodes.first().chainageM
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
                best = a.chainageM + t * (b.chainageM - a.chainageM)
            }
        }
        return best
    }

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
        /**
         * Точка отсчёта реального маршрута: откалибровано методом наименьших квадратов по
         * трём GPS-якорям машиниста (GPS + реальный км+пк в одной точке) на пересобранной
         * линии (после исправления бага с концом маршрута — см. build_route_asset.py):
         *   - 51.359412, 110.462016 → 5934км 7пк (156 м от линии, пикетаж по линии 0.0 км)
         *   - 51.940295, 113.619818 → 6213км 5пк (6.8 м от линии, пикетаж 279.7 км)
         *   - 52.501740, 117.004039 → 6585км 3пк (2.3 м от линии, пикетаж 653.3 км)
         * Невязки МНК по всем трём точкам — от 65 до 151 м на участке ~650 км, то есть в
         * пределах одного пикета.
         */
        const val HILOK_START_KM = 5934.7364187739195

        /**
         * Коэффициент растяжения линии, откалиброван МНК по тем же трём якорям (см.
         * [HILOK_START_KM]). Раньше здесь было 659/642.8 ≈ 1.025 (грубая оценка по разнице
         * длин полилинии и профиля пути) — новое значение точнее и подтверждено по всей
         * длине маршрута, а не только у одного конца.
         */
        const val HILOK_CHERNYSHEVSK_LENGTH_CORRECTION = 0.995847167879799

        /** Имя файла в assets/ с точками реального маршрута Хилок — Чернышевск-Забайкальский. */
        const val HILOK_CHERNYSHEVSK_ASSET = "route_hilok_chernyshevsk.json"
    }
}
