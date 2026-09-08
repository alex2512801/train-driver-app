package ru.traindriver.app.route

/** Поля формы ввода ограничения (ТЗ раздел 11) — те же 6, в том же порядке ввода. */
enum class RestrictionField { START_KM, START_PK, END_KM, END_PK, SPEED, PATH }

/** Значения полей как строки (то, что реально печатает машинист) — до всякой валидации. */
data class RestrictionFormFields(
    val startKm: String = "",
    val startPk: String = "",
    val endKm: String = "",
    val endPk: String = "",
    val speed: String = "",
    val path: String = ""
)

sealed class RestrictionInputResult {
    data class Success(val restriction: Restriction) : RestrictionInputResult()
    data class Error(val message: String, val fields: Set<RestrictionField>) : RestrictionInputResult()
}

/**
 * Разбирает форму ввода в [Restriction] (ТЗ раздел 11), 1:1 с логикой HTML-макета
 * (preview.html, `limitsAdd` обработчик) — то же самое поведение, тот же порядок проверок.
 *
 * Тип протяжённости определяется по тому, какие поля заполнены: и конечный км, и конечный
 * пк = ограничение через границу км; только конечный пк (без конечного км) = в пределах
 * одного км начала; ничего из конца = точка. Если задан только конечный км без конечного пк —
 * это НЕ отдельный случай (в макете тоже нет такой ветки) — конец молча игнорируется, как
 * будто оба поля конца пустые: получится точка. Так проще для машиниста ошибиться в одну
 * сторону (нет "полу-введённого" конца), чем отдельно объяснять эту комбинацию.
 */
object RestrictionInputParser {

    private const val PICKET_LENGTH_M = 100.0

    fun parse(f: RestrictionFormFields): RestrictionInputResult {
        val missing = mutableSetOf<RestrictionField>()
        if (f.startKm.isBlank()) missing += RestrictionField.START_KM
        if (f.startPk.isBlank()) missing += RestrictionField.START_PK
        if (f.speed.isBlank()) missing += RestrictionField.SPEED
        if (missing.isNotEmpty()) {
            return RestrictionInputResult.Error("Заполните км+пк начала и скорость", missing)
        }

        val startKm = f.startKm.toInt()
        val startPk = f.startPk.toInt()
        val speed = f.speed.toInt()
        if (startPk < 1) {
            return RestrictionInputResult.Error(
                "Пикет начала должен быть не меньше 1",
                setOf(RestrictionField.START_PK)
            )
        }
        val startM = ChainageFormatter.toMeters(startKm, startPk)

        val endM = when {
            f.endKm.isNotBlank() && f.endPk.isNotBlank() ->
                ChainageFormatter.toMeters(f.endKm.toInt(), f.endPk.toInt()) + PICKET_LENGTH_M
            f.endKm.isBlank() && f.endPk.isNotBlank() ->
                ChainageFormatter.toMeters(startKm, f.endPk.toInt()) + PICKET_LENGTH_M
            else -> startM
        }
        if (endM < startM) {
            return RestrictionInputResult.Error(
                "Конец раньше начала",
                setOf(RestrictionField.END_KM, RestrictionField.END_PK)
            )
        }

        val path = f.path.toIntOrNull()
        return RestrictionInputResult.Success(Restriction(startM, endM, speed, path))
    }
}
