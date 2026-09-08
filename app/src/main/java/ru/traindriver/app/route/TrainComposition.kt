package ru.traindriver.app.route

/** Вес/длина по серии локомотива (ТЗ раздел 5, таблица серий) — тот же список, что в макете. */
data class LocomotiveSpec(val weightT: Double, val lengthM: Double)

object LocomotiveTable {
    val SERIES: Map<String, LocomotiveSpec> = linkedMapOf(
        "Э5К" to LocomotiveSpec(100.0, 19.3),
        "2ЭС5К" to LocomotiveSpec(200.0, 35.0),
        "3ЭС5К" to LocomotiveSpec(300.0, 52.5),
        "4ЭС5К" to LocomotiveSpec(400.0, 70.0),
        "ВЛ85 №001–092" to LocomotiveSpec(288.0, 45.0),
        "ВЛ85 №093+" to LocomotiveSpec(276.0, 45.0),
        "ВЛ80р(2)" to LocomotiveSpec(192.0, 32.8),
        "ВЛ80р(3)" to LocomotiveSpec(288.0, 49.2),
    )
}

/**
 * Параметры состава (ТЗ раздел 5): серия локомотива + условная длина вагонов, в "условных
 * вагонах" — машинист подтвердил формулу: 1 усл. вагон = 14 м (стандартная железнодорожная
 * единица длины состава, не то же самое, что физическое число вагонов — см. README).
 *
 * [lengthM] — полная физическая длина состава. Нужна в двух местах: (1) полоска поезда на
 * графике рисуется от головы (GPS-координата) назад ровно на эту длину, не на произвольную
 * иллюстративную константу; (2) [ShortSegmentSpeedResolver.effectiveSpeedAt] — раздел 9 ТЗ
 * ("короткий участок повышенной скорости") тоже сравнивает длину короткого участка именно
 * с этой величиной.
 */
data class TrainComposition(
    val locomotiveSeries: String,
    val conditionalLengthUslVag: Double
) {
    init {
        require(locomotiveSeries in LocomotiveTable.SERIES) {
            "Неизвестная серия локомотива: $locomotiveSeries"
        }
        require(conditionalLengthUslVag >= 0) { "Условная длина состава не может быть отрицательной" }
    }

    val locomotive: LocomotiveSpec get() = LocomotiveTable.SERIES.getValue(locomotiveSeries)

    val lengthM: Double get() = conditionalLengthUslVag * CONDITIONAL_WAGON_LENGTH_M + locomotive.lengthM

    companion object {
        const val CONDITIONAL_WAGON_LENGTH_M = 14.0
    }
}
