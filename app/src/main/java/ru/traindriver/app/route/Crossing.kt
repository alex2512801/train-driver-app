package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Переезд (ТЗ раздел 8, пиктограмма "ЖД переезд") — см. tools/parse_catenary_points.py. Одна
 * физическая точка может быть актуальна для обоих направлений сразу — crossings.json хранит
 * один км+пк с двумя флагами (even/odd), поэтому у актуальных для обоих [chainageM] совпадает.
 */
data class Crossing(
    val direction: Direction,
    val chainageM: Double,
    val guarded: Boolean
)

/** Читает crossings.json в список [Crossing] (по записи на каждое актуальное направление). */
object CrossingAssetLoader {

    fun loadCrossings(context: Context, assetFileName: String = "crossings.json"): List<Crossing> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val result = mutableListOf<Crossing>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
            val guarded = obj.getBoolean("guarded")
            if (obj.getBoolean("even")) result.add(Crossing(Direction.EVEN, chainageM, guarded))
            if (obj.getBoolean("odd")) result.add(Crossing(Direction.ODD, chainageM, guarded))
        }
        return result
    }
}
