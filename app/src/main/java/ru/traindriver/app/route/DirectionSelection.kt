package ru.traindriver.app.route

/**
 * Направление движения (растут или убывают пикеты), см. ТЗ раздел 3.
 * Задаётся вручную машинистом — по одной лишь GPS-позиции этого не определить.
 */
enum class Direction {
    EVEN, // чётное
    ODD;  // нечётное

    fun opposite(): Direction = if (this == EVEN) ODD else EVEN
}

/** Правильный или неправильный физический путь — итог сочетания направления и пути. */
enum class PathStatus {
    CORRECT,
    WRONG
}

/**
 * Выбор машиниста: направление (чётное/нечётное) + физический путь (1 или 2). Оба поля
 * задаются вручную (ТЗ раздел 3 / "УСАВП-аналог_ИТОГ.pdf" раздел 4).
 *
 * Из этой пары выводится:
 *  - [pathStatus] — правильный путь или нет;
 *  - [effectiveDataset] — какой из двух готовых наборов данных (speed_limits.json,
 *    yellow_signal_warnings.json, brake_tests.json — поле "direction" в каждой записи)
 *    использовать. По таблице из ТЗ это сочетание всегда сводится к тому, что путь 1 — это
 *    нечётный датасет, путь 2 — чётный, независимо от выбранного направления движения;
 *  - [picketsGrowing] — растут пикеты или убывают; зависит только от направления, не от пути.
 *
 * Пикетаж (км+пк) сам по себе не пересчитывается по-разному в зависимости от направления —
 * он просто отражает физическую GPS-позицию на пути (см. RouteTrack), которая естественным
 * образом растёт или убывает в зависимости от того, куда реально едет поезд.
 */
data class DirectionSelection(
    val direction: Direction,
    val physicalPath: Int
) {
    init {
        require(physicalPath == 1 || physicalPath == 2) { "Путь должен быть 1 или 2" }
    }

    val pathStatus: PathStatus
        get() {
            val correctPath = if (direction == Direction.EVEN) 2 else 1
            return if (physicalPath == correctPath) PathStatus.CORRECT else PathStatus.WRONG
        }

    val effectiveDataset: Direction
        get() = if (pathStatus == PathStatus.CORRECT) direction else direction.opposite()

    val picketsGrowing: Boolean
        get() = direction == Direction.EVEN
}
