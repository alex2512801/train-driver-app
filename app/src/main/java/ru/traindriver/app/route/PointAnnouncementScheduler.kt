package ru.traindriver.app.route

/**
 * Разовое голосовое оповещение о точке впереди по ходу движения, за [thresholdM] метров до
 * неё (ТЗ раздел 6 — например "Впереди проба тормозов!" за 2000 м). Каждая точка объявляется
 * не более одного раза за проезд: как только она попала в зону порога (или уже осталась
 * позади — например, приложение запустили, когда поезд уже был рядом), помечается
 * объявленной и больше не возвращается — не хотим объявлять задним числом или повторно на
 * каждый GPS-тик, пока едем внутри зоны.
 *
 * Параметризован по [T] нарочно — та же логика подходит для любых будущих point-based
 * оповещений (переезд/КТСМ/станция/обрывное место — как только появятся координаты, см.
 * README), не только для [BrakeTest].
 */
class PointAnnouncementScheduler<T>(
    private val points: List<T>,
    private val chainageOf: (T) -> Double,
    private val thresholdM: Double
) {
    private val announced = mutableSetOf<T>()

    /** Точка, которую нужно объявить именно сейчас (впервые вошла в зону), или null. */
    fun update(positionM: Double, growing: Boolean): T? {
        for (point in points) {
            if (point in announced) continue
            val distance = if (growing) chainageOf(point) - positionM else positionM - chainageOf(point)
            if (distance < 0.0) {
                announced.add(point) // проехали, не попав в порог — не объявляем задним числом
                continue
            }
            if (distance <= thresholdM) {
                announced.add(point)
                return point
            }
        }
        return null
    }

    fun reset() = announced.clear()
}
