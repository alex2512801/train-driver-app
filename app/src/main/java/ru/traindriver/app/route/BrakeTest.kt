package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Точка пробы тормозов (ТЗ раздел 6, оповещение "Впереди проба тормозов!" за 2000 м до точки,
 * "...сдвоенного поезда!" — если [doubleTrain]). См. tools/parse_brake_tests.py, README шаг 5.
 */
data class BrakeTest(
    val direction: Direction,
    val chainageM: Double,
    val doubleTrain: Boolean
)

/** Читает brake_tests.json в список [BrakeTest]. */
object BrakeTestAssetLoader {

    fun loadBrakeTests(context: Context, assetFileName: String = "brake_tests.json"): List<BrakeTest> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "even") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                add(BrakeTest(direction, chainageM, obj.getBoolean("double_train")))
            }
        }
    }
}
