package ru.traindriver.app.route

/**
 * Одно постоянное ограничение скорости на главном пути (из speed_limits.json,
 * см. tools/parse_speed_limits.py), уже переведённое в метры от начала маршрута.
 *
 * [startM]/[endM] всегда упорядочены по возрастанию (min/max), хотя в исходном файле для
 * нечётного датасета диапазон часто записан в обратном порядке (км+пк убывают).
 */
data class SpeedLimit(
    val direction: Direction,
    val startM: Double,
    val endM: Double,
    val speedKmh: Int,
    val sourceText: String
) {
    val lengthM: Double get() = endM - startM

    fun contains(meters: Double): Boolean = meters >= startM && meters < endM
}
