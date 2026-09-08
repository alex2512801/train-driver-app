package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Точка "предвходного" сигнала станции (ТЗ раздел 6, "Внимание! Впереди станция!" за 1500 м).
 * "Предвходной" — не входной сигнал станции (Ч/Н) и не сама станция, а последний перегонный
 * светофор перед входным: т.2 для чётного направления, т.1 для нечётного (см.
 * tools/build_station_announcements.py — проверено на реальных данных, Гыршелун).
 */
data class StationAnnouncement(
    val stationName: String,
    val direction: Direction,
    val chainageM: Double
)

/** Читает station_announcements.json в список [StationAnnouncement]. */
object StationAnnouncementAssetLoader {

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
                add(StationAnnouncement(obj.getString("station"), direction, chainageM))
            }
        }
    }
}
