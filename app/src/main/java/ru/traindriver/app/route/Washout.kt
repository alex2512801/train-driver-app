package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Обрывное место (ТЗ раздел 6/9) — в отличие от [Crossing]/[AirGap] (одна точка км+пк с
 * флагами even/odd на обе стороны сразу) здесь каждое направление — СВОЯ, отдельная запись
 * км+пк (машинист прислал таблицу: нечётных точек 8, чётных 3, и даже там, где они рядом,
 * км+пк у них не совпадают один в один — это разные записи, не общая точка с двумя флагами).
 */
data class Washout(
    val direction: Direction,
    val chainageM: Double
)

/** Читает washouts.json в список [Washout]. */
object WashoutAssetLoader {

    fun loadWashouts(context: Context, assetFileName: String = "washouts.json"): List<Washout> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        val result = mutableListOf<Washout>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
            val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
            result.add(Washout(direction, chainageM))
        }
        return result
    }
}
