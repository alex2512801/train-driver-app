package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Точка пробы тормозов (ТЗ раздел 6, оповещение "Впереди проба тормозов!" за 2000 м до точки,
 * "...сдвоенного поезда!" — если [doubleTrain]). См. tools/parse_brake_tests.py, README шаг 5.
 *
 * [validSpeedRangesKmh] — годные диапазоны скорости для самой пробы (например 45-50 км/ч),
 * машинист: "впереди проба тормозов со скорости 45-50 км/ч" — голосовое оповещение называет их
 * вслух (см. VoiceAnnouncer.playWithSpeedRanges). Обычно один диапазон, но бывает несколько
 * (например 45-50, 55-60, 65-70) — тогда называются все подряд.
 */
data class BrakeTest(
    val direction: Direction,
    val chainageM: Double,
    val doubleTrain: Boolean,
    val validSpeedRangesKmh: List<IntRange>
)

/** Читает brake_tests.json в список [BrakeTest]. */
object BrakeTestAssetLoader {

    fun loadBrakeTests(context: Context, assetFileName: String = "brake_tests.json"): List<BrakeTest> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "even") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                val rangesArray = obj.getJSONArray("valid_speed_ranges_kmh")
                val ranges = buildList(rangesArray.length()) {
                    for (j in 0 until rangesArray.length()) {
                        val pair = rangesArray.getJSONArray(j)
                        add(pair.getInt(0)..pair.getInt(1))
                    }
                }
                add(BrakeTest(direction, chainageM, obj.getBoolean("double_train"), ranges))
            }
        }
    }
}
