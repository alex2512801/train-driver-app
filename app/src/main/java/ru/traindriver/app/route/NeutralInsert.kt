package ru.traindriver.app.route

import android.content.Context
import org.json.JSONObject

/**
 * Нейтральная вставка (ТЗ раздел 8) — см. tools/parse_catenary_points.py. В отличие от
 * [Crossing]/[AirGap], км+пк здесь РАЗНЫЕ для чётного/нечётного (neutral_inserts.json хранит
 * отдельные списки "even"/"odd") — но это одна и та же физическая точка на контактной сети,
 * просто пикетаж считается по каждому направлению по-своему. Поэтому при рисовании эту точку
 * нужно брать по текущему направлению движения НАПРЯМУЮ (state.direction), БЕЗ поправки на
 * неправильный путь (в отличие от переездов/воздушных промежутков) — см. комментарий у
 * getAllPictograms в HTML-макете.
 */
data class NeutralInsert(
    val direction: Direction,
    val chainageM: Double,
    val lengthM: Double
)

/** Читает neutral_inserts.json ({"even": [...], "odd": [...]}) в список [NeutralInsert]. */
object NeutralInsertAssetLoader {

    fun loadNeutralInserts(context: Context, assetFileName: String = "neutral_inserts.json"): List<NeutralInsert> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val root = JSONObject(json)
        val result = mutableListOf<NeutralInsert>()
        for ((key, direction) in listOf("even" to Direction.EVEN, "odd" to Direction.ODD)) {
            val array = root.getJSONArray(key)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                result.add(NeutralInsert(direction, chainageM, obj.getDouble("length_m")))
            }
        }
        return result
    }
}
