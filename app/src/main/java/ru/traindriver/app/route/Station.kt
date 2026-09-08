package ru.traindriver.app.route

import android.content.Context
import kotlin.math.abs
import org.json.JSONArray

/** Станция (название + позиция), см. tools/parse_track_profile.py, README шаг 12. */
data class Station(
    val name: String,
    val direction: Direction,
    val chainageM: Double
)

/** Читает track_stations.json в список [Station]. */
object StationAssetLoader {

    fun loadStations(context: Context, assetFileName: String = "track_stations.json"): List<Station> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                add(Station(obj.getString("name"), direction, chainageM))
            }
        }
    }
}

/**
 * "Место стоянки" для истории остановок (ТЗ раздел 10): название станции, если остановка была
 * внутри её границ (между входным и выходным сигналами), иначе — null (вызывающий код форматирует
 * km+пк сам). Настоящих границ вход/выход сигналов под рукой нет — приближение: станция, если
 * остановка в пределах [toleranceM] от позиции самой станции (track_stations.json). Не то же
 * самое, что "между входным и выходным" (которое обычно короче), но лучшего источника нет.
 */
fun nearestStationName(
    positionM: Double,
    direction: Direction,
    stations: List<Station>,
    toleranceM: Double = 1500.0
): String? {
    val candidates = stations.filter { it.direction == direction }
    val nearest = candidates.minByOrNull { abs(it.chainageM - positionM) } ?: return null
    return if (abs(nearest.chainageM - positionM) <= toleranceM) nearest.name else null
}
