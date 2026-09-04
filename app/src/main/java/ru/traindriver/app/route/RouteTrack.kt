package ru.traindriver.app.route

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Полилиния железнодорожного пути с накопленным километражем (пикетажем) вдоль неё.
 *
 * Реальная линия пути должна выгружаться из OpenStreetMap (Overpass Turbo, ~86 800 точек —
 * см. ТЗ, раздел 1) и импортироваться в приложение вместе с остальными данными (раздел 2).
 * Пока такого файла нет, здесь используется короткая заглушка из нескольких точек, чтобы
 * можно было проверить саму логику "GPS -> ближайшая точка пути -> км+пк".
 */
class RouteTrack(rawPoints: List<LatLon>) {

    private class Node(val lat: Double, val lon: Double, val chainageM: Double)

    private val refLat = rawPoints.first().lat
    private val refLon = rawPoints.first().lon
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))

    private val nodes: List<Node>

    init {
        require(rawPoints.size >= 2) { "Маршрут должен содержать минимум 2 точки" }
        var chainage = 0.0
        val built = mutableListOf(Node(rawPoints[0].lat, rawPoints[0].lon, 0.0))
        for (i in 1 until rawPoints.size) {
            val prev = toXY(rawPoints[i - 1].lat, rawPoints[i - 1].lon)
            val cur = toXY(rawPoints[i].lat, rawPoints[i].lon)
            chainage += distance(prev, cur)
            built.add(Node(rawPoints[i].lat, rawPoints[i].lon, chainage))
        }
        nodes = built
    }

    /** Полная длина маршрута (в метрах) от первой до последней точки. */
    val totalLengthM: Double get() = nodes.last().chainageM

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
         * ЗАГЛУШКА: примерные координаты станций перегона Хилок — Карымская —
         * Чернышевск-Забайкальский, нужны только чтобы было на чём проверить проекцию
         * GPS-точки на путь. Точность этих координат НЕ гарантирована и пикетаж,
         * полученный по ним, не соответствует реальному km+пк на местности.
         * Заменить на реальную линию пути, когда появится выгрузка из OSM/Overpass.
         */
        fun placeholderRoute(): RouteTrack = RouteTrack(
            listOf(
                LatLon(51.3549, 110.4692), // Хилок (примерно)
                LatLon(51.2033, 116.0522), // Карымская (примерно)
                LatLon(52.5192, 117.7519)  // Чернышевск-Забайкальский (примерно)
            )
        )
    }
}
