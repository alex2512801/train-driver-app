package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * "Скорость проследования сигнала с жёлтым показанием не более N км/ч" (ТЗ раздел 6). Машинист
 * объяснил логику целиком: исходная таблица размечена "от [сигнал] до [сигнал]" (начало и
 * конец зоны с ограничением), но фраза звучит ОДИН раз, перед НАЧАЛОМ зоны — конец приложению
 * не нужен вообще, поэтому [chainageM] здесь — позиция именно начала (from_signal у
 * tools/match_yellow_signals.py, где и разобрана вся логика сопоставления литер/дистанций).
 *
 * [wrongPath] — та же механика, что у ЧД/НД (см. [Signal.isVisibleFor]): машинист про
 * Хилок ("от точки 11 до ЧД") и Хилок — Гыршелун ("от точки 14 до нечётного дополнительного"):
 * "она говорится только, когда мы едем по неправильному" — [direction] здесь это ФАКТИЧЕСКОЕ
 * направление движения (как у ЧД/НД), не effectiveDataset.
 *
 * [track] — та же кнопка К1, что у [Signal.track] (null/"к1"/"без_к1"), унаследована от того
 * конца зоны, который совпал с литерой в track_signals.json — машинист, пример Чита-2: "от Ч51,
 * Ч55 до входного ЧА станции Чита-2... это когда мы включаем кнопку К1 и переключаемся на
 * четвёртый путь, по которому стоит ЧА, то он нам эту фразу говорит".
 */
data class YellowSignalWarning(
    val direction: Direction,
    val chainageM: Double,
    val speedKmh: Int,
    val wrongPath: Boolean,
    val track: String?
)

/** Та же видимость, что у [Signal.isVisibleFor] (неправильный путь) + [Signal.track] (кнопка
 *  К1) сразу в одном месте — семантика обоих полей здесь ровно та же, что у [Signal]. */
fun YellowSignalWarning.isVisibleFor(
    effectiveDataset: Direction,
    actualDirection: Direction,
    k1Enabled: Boolean
): Boolean {
    val directionOk = if (wrongPath) {
        direction == actualDirection && effectiveDataset != actualDirection
    } else {
        direction == effectiveDataset
    }
    val trackOk = track == null || (k1Enabled && track == "к1") || (!k1Enabled && track == "без_к1")
    return directionOk && trackOk
}

/** Читает yellow_signal_warnings.json в список [YellowSignalWarning]. Большая часть исходной
 *  таблицы (157 строк) пока НЕ резолвится в км+пк (см. doc-комментарий tools/match_yellow_signals.py
 *  про unresolved:range/not_found/out_of_scope) — такие строки (from_km == null) здесь
 *  пропускаются целиком, а не озвучиваются "примерно". */
object YellowSignalWarningAssetLoader {

    fun loadYellowSignalWarnings(
        context: Context,
        assetFileName: String = "yellow_signal_warnings.json"
    ): List<YellowSignalWarning> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val result = mutableListOf<YellowSignalWarning>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.isNull("from_km") || obj.isNull("from_pk")) continue
            // main_track=false — машинист явно разобрал весь список и исключил записи,
            // относящиеся не к главному пути (боковые/парковые пути станций, например
            // "вх. 1Ч ст. Чита-2" или ЧМ4А/ЧМ5А/ЧМ6А на Шилке-товарной): "у нас только
            // главные пути". Поле проставлено вручную (см. git log), match_yellow_signals.py
            // его не трогает — переживает повторный запуск скрипта.
            if (!obj.optBoolean("main_track", true)) continue
            val direction = if (obj.getString("direction") == "even") Direction.EVEN else Direction.ODD
            val chainageM = ChainageFormatter.toMeters(obj.getInt("from_km"), obj.getInt("from_pk"))
            val track = if (obj.isNull("track")) null else obj.getString("track")
            result.add(
                YellowSignalWarning(
                    direction = direction,
                    chainageM = chainageM,
                    speedKmh = obj.getInt("yellow_speed_kmh"),
                    wrongPath = obj.getBoolean("wrong_path"),
                    track = track
                )
            )
        }
        // Несколько строк исходной таблицы делят один и тот же FROM (например три разных
        // "ЧМ2Г" на Шилке-товарной, ведущие к разным путям станции) — раз озвучиваем только
        // начало зоны, такие строки дают абсолютно одинаковое предупреждение в одной и той же
        // точке; без distinct() машинист услышал бы одну и ту же фразу подряд несколько раз.
        return result.distinct()
    }
}
