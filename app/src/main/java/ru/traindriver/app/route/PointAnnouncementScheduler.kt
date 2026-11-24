package ru.traindriver.app.route

/**
 * Разовое голосовое оповещение о точке впереди по ходу движения, за [thresholdOf] метров до
 * неё (ТЗ раздел 6 — например "Впереди проба тормозов!" за 2000 м). Каждая точка объявляется
 * не более одного раза за проезд: как только она попала в зону порога (или уже осталась
 * позади — например, приложение запустили, когда поезд уже был рядом), помечается
 * объявленной и больше не возвращается — не хотим объявлять задним числом или повторно на
 * каждый GPS-тик, пока едем внутри зоны.
 *
 * [thresholdOf] — функция, а не одно число на весь список: порог может отличаться от точки к
 * точке (например у станций — обычно 1500 м до предвходного, но 3000 м там, где вместо
 * предвходного объявляется сам входной сигнал станции — см. StationAnnouncement.thresholdM
 * и README, "Лесная"/"Яблоновая").
 *
 * Параметризован по [T] нарочно — та же логика подходит для любых будущих point-based
 * оповещений (переезд/КТСМ/станция/обрывное место — как только появятся координаты, см.
 * README), не только для [BrakeTest].
 */
class PointAnnouncementScheduler<T>(
    private var points: List<T>,
    private val chainageOf: (T) -> Double,
    private val thresholdOf: (T) -> Double
) {
    private val announced = mutableSetOf<T>()

    /**
     * Подменяет список точек целиком (направление/путь изменились, либо данные обновились через
     * "База данных" — см. MainActivity.reloadRouteData) БЕЗ сброса состояния "уже объявлено" для
     * точек, которые есть и в старом, и в новом списке (сравнение структурное, как у data class).
     * Раньше вместо этого MainActivity просто создавал НОВЫЙ PointAnnouncementScheduler с пустым
     * announced — если это происходило, пока машинист был внутри уже активной зоны оповещения
     * (600-2000 м до точки, например просто заглянул в "Параметры" и закрыл их), объявление
     * звучало ПОВТОРНО при следующем тике. Точки, которых в новом списке больше нет, убираются
     * из announced — иначе [announced] рос бы бесконечно при каждом обновлении данных.
     */
    fun updatePoints(newPoints: List<T>) {
        points = newPoints
        announced.retainAll(newPoints.toSet())
    }

    /** Точка, которую нужно объявить именно сейчас (впервые вошла в зону), или null. */
    fun update(positionM: Double, growing: Boolean): T? {
        for (point in points) {
            if (point in announced) continue
            val distance = if (growing) chainageOf(point) - positionM else positionM - chainageOf(point)
            if (distance < 0.0) {
                announced.add(point) // проехали, не попав в порог — не объявляем задним числом
                continue
            }
            if (distance <= thresholdOf(point)) {
                announced.add(point)
                return point
            }
        }
        return null
    }

    fun reset() = announced.clear()
}
