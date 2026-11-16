package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/** Воздушный промежуток (ТЗ раздел 8) — см. tools/parse_catenary_points.py, та же схема
 *  одной точки с флагами even/odd на направление, что и у [Crossing]. */
data class AirGap(
    val direction: Direction,
    val chainageM: Double
)

/** Читает air_gaps.json в список [AirGap] (по записи на каждое актуальное направление). */
object AirGapAssetLoader {

    fun loadAirGaps(context: Context, assetFileName: String = "air_gaps.json"): List<AirGap> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val result = mutableListOf<AirGap>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
            if (obj.getBoolean("even")) result.add(AirGap(Direction.EVEN, chainageM))
            if (obj.getBoolean("odd")) result.add(AirGap(Direction.ODD, chainageM))
        }
        return result
    }
}
