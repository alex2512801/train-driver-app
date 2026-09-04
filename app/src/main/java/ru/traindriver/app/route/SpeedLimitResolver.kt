package ru.traindriver.app.route

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
}
