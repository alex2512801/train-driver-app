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
    val track: String?
)

/** Читает track_signals.json в список [Signal]. */
object SignalAssetLoader {

    fun loadSignals(context: Context, assetFileName: String = "track_signals.json"): List<Signal> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                val track = if (obj.isNull("track")) null else obj.getString("track")
                add(Signal(obj.getString("name"), direction, chainageM, track))
            }
        }
    }

    /** Сигналы главного пути (без среднего пути Тургутуй — Яблоновая, см. [Signal.track]). */
    fun loadMainTrackSignals(context: Context, assetFileName: String = "track_signals.json"): List<Signal> =
        loadSignals(context, assetFileName).filter { it.track == null }
}
