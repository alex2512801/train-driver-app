package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Светофор/литера (ТЗ раздел 7, "Значок сигнала") — см. tools/parse_track_profile.py,
 * README шаг 12. [track] — null для главного пути, "средний" для сигналов среднего пути на
 * перегоне Тургутуй — Яблоновая (те же номера, что у главного пути в той же точке, но другие,
 * физические светофоры). Кнопка К1 (переключение на средний путь) — отдельный, пока не
 * реализованный шаг; [SignalAssetLoader.loadMainTrackSignals] берёт только [track] == null.
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
        val conditional = ConditionalSignalAssetLoader.loadConditionalChainages(context)
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        val raw = buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                val track = if (obj.isNull("track")) null else obj.getString("track")
                val name = obj.getString("name")
                val conditionallyPermissive = (name to chainageM) in conditional
                add(Signal(name, direction, chainageM, track, conditionallyPermissive))
            }
        }
        // Одна и та же запись (та же литера на том же км+пк) часто встречается в ОБОИХ
        // исходных PDF-документах перегона под разными "сырыми" тегами direction — то же самое,
        // из-за чего понадобился effectiveDirection (см. его doc-комментарий). После того как
        // фильтрация по направлению стала смотреть на effectiveDirection, а не на сырой тег,
        // такие записи с ОДИНАКОВЫМ именем и км+пк стали бы проходить фильтр ДВАЖДЫ и рисовались
        // бы на графике как два полностью совпадающих значка в одной точке — схлопываем их в
        // один (сырой direction при этом больше ни на что не влияет, т.к. effectiveDirection его
        // не использует для букв/голых номеров — см. выше).
        return raw.distinctBy { Triple(it.name, it.chainageM, it.track) }
    }

    /** Сигналы главного пути (без среднего пути Тургутуй — Яблоновая, см. [Signal.track]). */
    fun loadMainTrackSignals(context: Context, assetFileName: String = "track_signals.json"): List<Signal> =
        loadSignals(context, assetFileName).filter { it.track == null }
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
