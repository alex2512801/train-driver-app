package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Светофор/литера (ТЗ раздел 7, "Значок сигнала") — см. tools/parse_track_profile.py,
 * README шаг 12.
 *
 * [track] — управляет видимостью кнопкой К1 (переключение на физически другой путь в месте,
 * где маршрут на время раздваивается — машинист: "по перегону Тургутуй — Яблоновая... по
 * станции Чита-2... по станции Карымская" — там у одной и той же точки реально стоят РАЗНЫЕ
 * физические светофоры в зависимости от того, по какому из путей идёт поезд):
 *  - null — обычный сигнал, виден всегда, К1 на него не влияет (подавляющее большинство);
 *  - "к1" — виден, ТОЛЬКО когда К1 нажата (путь, который включается кнопкой — средний путь
 *    Тургутуй — Яблоновая, путь 4 по Карымской, путь 10 по Чита-2 и т.п.);
 *  - "без_к1" — виден, ТОЛЬКО когда К1 НЕ нажата (путь по умолчанию в том же самом месте —
 *    например путь 2 по Карымской/Чита-2); без этого сигналы обоих путей рисовались бы
 *    одновременно, хотя поезд реально может быть только на одном из них.
 * См. [SignalAssetLoader.loadMainTrackSignals].
 */
data class Signal(
    val name: String,
    val direction: Direction,
    val chainageM: Double,
    val track: String?,
    // Условно разрешающий сигнал (ТЗ/машинист: "если условно разрешающий, пишем номер и
    // букву Т, тире и расстояние" — см. drawSignalTags, где label получает суффикс "Т").
    // Список таких точек машинист диктует перегонами по мере вождения — conditional_signals.json.
    val conditionallyPermissive: Boolean = false
)

/**
 * Направление, которому сигнал реально служит — НЕ то же самое, что [Signal.direction]!
 * [Signal.direction] — это просто "из какого PDF-документа извлечена запись"
 * (tools/parse_track_profile.py, SOURCE_FILES: Хилок-Крм.pdf → чётное, Крм-Хилок.pdf —
 * нечётное, и т.д.), а не "к какому пути относится конкретный светофор". Проверено по самим
 * данным track_signals.json: голые номера автоблокировки (например, "3" и "4" у км6210пк2)
 * ПОЧТИ ПОРОВНУ встречаются и под "чётное", и под "нечётное" тегом (совпадает с собственным
 * комментарием скрипта: "голые числа... действительно совпадают в обоих документах почти
 * всегда" — ожидаемо, раз оба документа одного перегона физически видят одни и те же столбы).
 * Даже у буквенных литер (Ч.../Н...) тег не всегда совпадает с самой буквой (например, литеры
 * на "Ч" встречаются 154 раза под тегом "чётное", но 80 раз — под тегом "нечётное") — машинист
 * подтвердил напрямую: "У нас сигналы
 * только четного пути... идут четные там 6, 4, 2, предвходной, входной" — то есть машинисту
 * нужны сигналы ТОЛЬКО своего направления движения, а не оба сразу, и правильный критерий —
 * не тег документа, а сама литера/номер:
 *  - буква Ч → чётное, буква Н → нечётное (однозначно, сама буква это и означает);
 *  - голый номер (автоблокировка, без буквы) → по чётности числа: чётный номер — чётное
 *    направление, нечётный — нечётное (ровно то, что машинист продиктовал).
 */
val Signal.effectiveDirection: Direction
    get() {
        val letter = name.firstOrNull { it.isLetter() }
        return when (letter) {
            'Ч' -> Direction.EVEN
            'Н' -> Direction.ODD
            else -> name.toIntOrNull()?.let { if (it % 2 == 0) Direction.EVEN else Direction.ODD } ?: direction
        }
    }

/**
 * Показывать ли этот сигнал, если машинист сейчас реально едет в направлении [actualDirection]
 * (DirectionSelection.direction — куда именно едет поезд, не путая с эффективным датасетом), а
 * действующий (для построения графика/расчётов) датасет — [effectiveDataset]
 * (DirectionSelection.effectiveDataset).
 *
 * ЧД/НД — особый случай, НЕ описываемый обычным [effectiveDirection] (машинист поправил:
 * "ЧД — это входной станции по неправильному пути... ЧД и НД должны показываться ТОЛЬКО по
 * неправильному пути"). Это не "сигнал своего направления", а специальный предвходной сигнал,
 * который существует ИМЕННО для случая, когда машинист едет в направлении Х, но физически
 * оказался на пути, который обычно обслуживает противоположное направление (тогда effectiveDataset
 * разворачивается в противоположный [actualDirection] — см. DirectionSelection.effectiveDataset).
 * Поэтому:
 *  - ЧД виден, когда едем чётным направлением, но effectiveDataset развернулся в нечётное
 *    (значит путь неправильный) — т.е. actualDirection == EVEN И effectiveDataset != actualDirection;
 *  - НД — то же самое для нечётного направления;
 *  - все остальные сигналы (Ч, Н, ЧМ..., голые номера) — как и раньше, по [effectiveDirection].
 */
fun Signal.isVisibleFor(effectiveDataset: Direction, actualDirection: Direction): Boolean {
    val isWrongPath = effectiveDataset != actualDirection
    return when (name) {
        "ЧД" -> isWrongPath && actualDirection == Direction.EVEN
        "НД" -> isWrongPath && actualDirection == Direction.ODD
        else -> effectiveDirection == effectiveDataset
    }
}

/** Читает track_signals.json в список [Signal]. */
object SignalAssetLoader {

    fun loadSignals(context: Context, assetFileName: String = "track_signals.json"): List<Signal> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val raw = buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                val track = if (obj.isNull("track")) null else obj.getString("track")
                add(Signal(obj.getString("name"), direction, chainageM, track))
            }
        }
        // Одна и та же запись (та же литера на том же км+пк, БИТ В БИТ) часто встречается в
        // ОБОИХ исходных PDF-документах перегона под разными "сырыми" тегами direction —
        // схлопываем точные совпадения в один (сырой direction сам по себе больше ни на что не
        // влияет, т.к. effectiveDirection его не использует для букв/голых номеров — см. выше).
        val exactDeduped = raw.distinctBy { Triple(it.name, it.chainageM, it.track) }
        // Машинист прислал скриншот: "сигнал, который есть ещё рядом через 100 метров — такого
        // на самом деле быть не может". Причина — те же два исходных PDF почти всегда, но НЕ
        // БИТ В БИТ: один и тот же физический сигнал измерен в них с разницей в 1-5 пк (100-
        // 500м), поэтому exactDeduped выше их не ловит, и на графике рисовались два отдельных
        // значка рядом. Проверено по самим данным: 181 из 185 таких "сближенных" пар (≤500м) —
        // именно это (у двух записей РАЗНЫЙ сырой тег direction, т.е. они из разных файлов); 4
        // оставшиеся — на перегоне Тургутуй — Яблоновая, где у среднего пути действительно свои,
        // физически другие сигналы с теми же номерами (см. doc-комментарий у [Signal.track]) — у
        // этих четырёх сырой тег ОДИНАКОВЫЙ (обе записи из одного и того же файла), поэтому
        // схлопывать их нельзя: это не дубль измерения, а два разных столба.
        val merged = mergeCrossPdfDuplicates(exactDeduped)

        val conditional = ConditionalSignalAssetLoader.loadConditionalChainages(context)
        return merged.map { s ->
            val isConditional = conditional.any { (name, m) ->
                name == s.name && kotlin.math.abs(m - s.chainageM) <= 300.0
            }
            if (isConditional) s.copy(conditionallyPermissive = true) else s
        }
    }

    /** Схлопывает "почти-дубли" — см. doc-комментарий у вызова в [loadSignals]. Группируем по
     *  (номер/литера, [effectiveDirection], путь), а не по сырому direction — ровно тот же
     *  критерий "один и тот же физический сигнал", что и везде в этом файле. Внутри группы
     *  соседние (по км+пк) записи с РАЗНЫМ сырым тегом direction и расстоянием между ними не
     *  больше 500м считаем одной и той же точкой, измеренной дважды, и усредняем км+пк. */
    private fun mergeCrossPdfDuplicates(signals: List<Signal>): List<Signal> {
        val merged = mutableListOf<Signal>()
        val byIdentity = signals.groupBy { Triple(it.name, it.effectiveDirection, it.track) }
        for ((_, group) in byIdentity) {
            val sorted = group.sortedBy { it.chainageM }
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size &&
                    sorted[j + 1].chainageM - sorted[j].chainageM <= 500.0 &&
                    sorted[j + 1].direction != sorted[j].direction
                ) {
                    j++
                }
                val cluster = sorted.subList(i, j + 1)
                merged.add(
                    if (cluster.size == 1) cluster[0]
                    else cluster[0].copy(chainageM = cluster.sumOf { it.chainageM } / cluster.size)
                )
                i = j + 1
            }
        }
        return merged
    }

    /** Сигналы с учётом кнопки К1 (см. [Signal.track]) — [k1Enabled] решает, какой из двух
     *  физически разных путей сейчас показывать там, где они есть. */
    fun loadMainTrackSignals(context: Context, k1Enabled: Boolean, assetFileName: String = "track_signals.json"): List<Signal> =
        loadSignals(context, assetFileName).filter {
            it.track == null || (k1Enabled && it.track == "к1") || (!k1Enabled && it.track == "без_к1")
        }
}

/**
 * Список точек (голый номер автоблокировки + км/пк), которые являются "условно разрешающим
 * сигналом" — машинист диктует их перегонами по мере вождения (пока не весь маршрут размечен,
 * список будет пополняться), см. [Signal.conditionallyPermissive].
 */
object ConditionalSignalAssetLoader {

    fun loadConditionalChainages(
        context: Context,
        assetFileName: String = "conditional_signals.json"
    ): Set<Pair<String, Double>> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val result = mutableSetOf<Pair<String, Double>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
            result.add(obj.getString("name") to chainageM)
        }
        return result
    }
}
