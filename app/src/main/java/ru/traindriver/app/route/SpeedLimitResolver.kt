package ru.traindriver.app.route

import kotlin.math.abs

/** Следующее ограничение с ДРУГОЙ скоростью впереди по ходу движения и расстояние до него. */
data class NextRestriction(val speedLimit: SpeedLimit, val distanceM: Double)

/**
 * Ищет действующее ограничение скорости в точке пути. В данных есть "общая" строка на весь
 * перегон/станцию и более узкие строки-исключения внутри неё (см. README, раздел про
 * speed_limits.json) — оба диапазона попадают в файл как есть, поэтому побеждает САМЫЙ УЗКИЙ
 * подходящий диапазон, а не первый найденный.
 */
class SpeedLimitResolver(private val limits: List<SpeedLimit>) {

    fun speedAt(direction: Direction, meters: Double): SpeedLimit? =
        limits.asSequence()
            .filter { it.direction == direction && it.contains(meters) }
            .minByOrNull { it.lengthM }

    /**
     * Ищет ближайшую впереди точку, где действующая скорость меняется (нижняя статусная
     * строка ТЗ раздел 7: "V=[скорость] км/ч" / "S=[расстояние] м"). [forward] — двигаться ли
     * в сторону роста метров (чётное) или убывания (нечётное), см. DirectionSelection.picketsGrowing.
     */
    fun findNextChange(
        direction: Direction,
        fromMeters: Double,
        forward: Boolean,
        maxLookaheadM: Double = 5000.0,
        stepM: Double = 10.0
    ): NextRestriction? {
        val current = speedAt(direction, fromMeters)?.speedKmh
        var m = fromMeters
        val limit = if (forward) fromMeters + maxLookaheadM else fromMeters - maxLookaheadM
        while (if (forward) m < limit else m > limit) {
            m = if (forward) m + stepM else m - stepM
            val found = speedAt(direction, m)
            if (found != null && found.speedKmh != current) {
                return NextRestriction(found, abs(m - fromMeters))
            }
        }
        return null
    }
}
