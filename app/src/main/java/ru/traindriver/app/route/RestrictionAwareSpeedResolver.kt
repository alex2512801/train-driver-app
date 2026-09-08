package ru.traindriver.app.route

import kotlin.math.abs

/** Действующая скорость в точке впереди и расстояние до неё (та же форма, что [NextRestriction]). */
data class EffectiveSpeedChange(val speedKmh: Int, val distanceM: Double)

/**
 * "V=[скорость] км/ч" / "S=[расстояние] м" для следующего ограничения (ТЗ раздел 7, нижняя
 * статусная строка) должны учитывать не только постоянные ограничения из speed_limits.json
 * ([SpeedLimitResolver]), но и временные, введённые машинистом (ТЗ раздел 11, [Restriction]).
 * В точке, накрытой активным временным ограничением, оно ВСЕГДА побеждает — независимо от
 * того, шире оно или уже постоянной ступени под ним (это отдельная, более свежая команда
 * машинисту, а не просто "самый узкий диапазон", как при выборе между постоянными записями).
 *
 * [restrictions] должен быть уже отфильтрован вызывающим кодом по текущему пути (см.
 * MainActivity.refreshTrackProfileRestrictions) — этот класс сам ничего не решает про путь.
 */
class RestrictionAwareSpeedResolver(
    private val speedLimitResolver: SpeedLimitResolver,
    private val restrictions: List<Restriction>
) {
    fun speedAt(direction: Direction, meters: Double): Int? {
        val restriction = restrictions.firstOrNull { meters in it.startM..it.endM }
        if (restriction != null) return restriction.speedKmh
        return speedLimitResolver.speedAt(direction, meters)?.speedKmh
    }

    /** Тот же алгоритм сканирования, что и [SpeedLimitResolver.findNextChange], но по [speedAt]. */
    fun findNextChange(
        direction: Direction,
        fromMeters: Double,
        forward: Boolean,
        maxLookaheadM: Double = 5000.0,
        stepM: Double = 10.0
    ): EffectiveSpeedChange? {
        val current = speedAt(direction, fromMeters)
        var m = fromMeters
        val limit = if (forward) fromMeters + maxLookaheadM else fromMeters - maxLookaheadM
        while (if (forward) m < limit else m > limit) {
            m = if (forward) m + stepM else m - stepM
            val found = speedAt(direction, m)
            if (found != null && found != current) {
                return EffectiveSpeedChange(found, abs(m - fromMeters))
            }
        }
        return null
    }
}
