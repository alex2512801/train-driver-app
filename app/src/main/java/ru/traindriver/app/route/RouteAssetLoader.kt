package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Читает точки пути из JSON-файла в assets: массив объектов {"lat":.., "lon":..},
 * по порядку от начала маршрута к концу (см. скрипт построения маршрута из export.geojson).
 */
object RouteAssetLoader {

    fun loadLatLonList(context: Context, assetFileName: String): List<LatLon> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(LatLon(obj.getDouble("lat"), obj.getDouble("lon")))
            }
        }
    }
}
