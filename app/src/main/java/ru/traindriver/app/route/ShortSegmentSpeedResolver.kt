package ru.traindriver.app.route

/**
 * Один "естественный" отрезок скорости после точного восстановления кусочно-постоянного
 * профиля скорости из перекрывающихся общих/узких диапазонов (см. SpeedLimit/SpeedLimitResolver:
 * в speed_limits.json есть "общая" строка на весь перегон и более узкие строки-исключения
 * внутри неё) — соседние отрезки с одинаковой скоростью уже склеены в один, поэтому это ровно
 * то, что машинист реально видит как последовательность ступеней на графике.
 */
data class SpeedSegment(val startM: Double, val endM: Double, val speedKmh: Int) {
    val lengthM: Double get() = endM - startM
}

/**
 * Натуральная (как на схеме) и эффективная (реально достижимая) скорость в точке — см.
 * [ShortSegmentSpeedResolver.effectiveSpeedAt]. Отличаются только внутри короткого участка
 * повышенной скорости (ТЗ раздел 9); везде больше [isShortSegment] == false и оба поля равны.
 */
data class EffectiveSpeed(
    val naturalSpeedKmh: Int,
    val effectiveSpeedKmh: Int,
    val isShortSegment: Boolean
)

/**
 * Логика «короткого участка повышенной скорости» (ТЗ раздел 9): если короткий участок с БОЛЕЕ
 * ВЫСОКОЙ скоростью зажат между двумя другими и его физическая длина короче длины поезда —
 * состав физически не может там разогнаться (часть состава всё ещё физически находится на
 * соседнем участке всё время прохождения короткого отрезка). Эффективная безопасная скорость
 * на этом коротком участке — МАКСИМУМ из скоростей двух соседних участков, а не обязательно
 * скорость предыдущего и не обязательно меньшая (см. примеры в ТЗ и тесты):
 *   60 → 70(коротко) → 50  ⇒ эффективная = 60 (max из 60 и 50)
 *   50 → 80(коротко) → 65  ⇒ эффективная = 65 (max из 50 и 65 — СЛЕДУЮЩИЙ, а не предыдущий)
 *
 * [trainLengthM] передаётся при каждом запросе, а не хранится здесь — это параметр состава
 * (ТЗ раздел 5, "Параметры" в меню), который машинист может изменить в любой момент рейса.
 */
class ShortSegmentSpeedResolver(limits: List<SpeedLimit>) {

    private val segmentsByDirection: Map<Direction, List<SpeedSegment>> =
        Direction.entries.associateWith { buildSegments(limits, it) }

    private fun buildSegments(limits: List<SpeedLimit>, direction: Direction): List<SpeedSegment> {
        val relevant = limits.filter { it.direction == direction }
        if (relevant.isEmpty()) return emptyList()

        // Границы, где теоретически может смениться победитель "самый узкий диапазон побеждает"
        // (SpeedLimitResolver) — это ровно начала/концы всех диапазонов, больше нигде профиль
        // измениться не может. Между соседними границами берём скорость в средней точке.
        val bounds = (relevant.map { it.startM } + relevant.map { it.endM }).distinct().sorted()
        val raw = mutableListOf<SpeedSegment>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            val mid = (a + b) / 2
            val winner = relevant.filter { it.startM <= mid && mid < it.endM }
                .minByOrNull { it.lengthM } ?: continue
            raw.add(SpeedSegment(a, b, winner.speedKmh))
        }

        val merged = mutableListOf<SpeedSegment>()
        for (seg in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.speedKmh == seg.speedKmh && last.endM == seg.startM) {
                merged[merged.size - 1] = last.copy(endM = seg.endM)
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    /** Натуральная ступень (без учёта раздела 9) в точке — та, что резолвит [SpeedLimitResolver]. */
    fun segmentAt(direction: Direction, meters: Double): SpeedSegment? =
        segmentsByDirection[direction]?.firstOrNull { meters >= it.startM && meters < it.endM }

    fun effectiveSpeedAt(direction: Direction, meters: Double, trainLengthM: Double): EffectiveSpeed? {
        val segments = segmentsByDirection[direction] ?: return null
        val idx = segments.indexOfFirst { meters >= it.startM && meters < it.endM }
        if (idx == -1) return null
        val seg = segments[idx]

        if (idx == 0 || idx == segments.size - 1) {
            return EffectiveSpeed(seg.speedKmh, seg.speedKmh, isShortSegment = false)
        }
        val prev = segments[idx - 1]
        val next = segments[idx + 1]

        // "Зажат между двумя другими участками" — считаем только вплотную прилегающие соседи
        // (не через разрыв в данных, где между отрезками может быть неизвестный кусок пути).
        val sandwiched = prev.endM == seg.startM && seg.endM == next.startM
        val isShort = sandwiched &&
            seg.lengthM < trainLengthM &&
            seg.speedKmh > prev.speedKmh &&
            seg.speedKmh > next.speedKmh

        return if (isShort) {
            EffectiveSpeed(seg.speedKmh, maxOf(prev.speedKmh, next.speedKmh), isShortSegment = true)
        } else {
            EffectiveSpeed(seg.speedKmh, seg.speedKmh, isShortSegment = false)
        }
    }
}
