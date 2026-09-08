package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Точка, за [thresholdM] метров до которой играть "Внимание! Впереди станция!" (ТЗ раздел 6).
 * Обычно это "предвходной" — не входной сигнал станции (Ч/Н) и не сама станция, а последний
 * перегонный светофор перед входным: т.2 для чётного направления, т.1 для нечётного (см.
 * tools/build_station_announcements.py — проверено на реальных данных, Гыршелун), порог 1500 м.
 *
 * Исключение — станции без проходных светофоров автоблокировки на этом перегоне (Лесная/
 * Яблоновая — машинист объяснил, что там другая система сигнализации, т.2/т.1 просто не
 * существует): для них [chainageM] — это позиция САМОГО входного сигнала (Ч/Н), а [thresholdM]
 * увеличен до 3000 м, чтобы объявление всё равно звучало заранее.
 */
data class StationAnnouncement(
    val stationName: String,
    val direction: Direction,
    val chainageM: Double,
    val thresholdM: Double
)

/** Читает station_announcements.json в список [StationAnnouncement]. */
object StationAnnouncementAssetLoader {

    private const val DEFAULT_THRESHOLD_M = 1500.0

    fun loadStationAnnouncements(
        context: Context,
        assetFileName: String = "station_announcements.json"
    ): List<StationAnnouncement> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(
                    obj.getInt("predvhodnoy_km"),
                    obj.getInt("predvhodnoy_pk")
                )
                val thresholdM = obj.optDouble("threshold_m", DEFAULT_THRESHOLD_M)
                add(StationAnnouncement(obj.getString("station"), direction, chainageM, thresholdM))
            }
        }
    }
}
