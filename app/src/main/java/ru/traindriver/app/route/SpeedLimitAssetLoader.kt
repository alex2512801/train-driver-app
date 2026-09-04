package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/** Читает speed_limits.json (см. tools/parse_speed_limits.py) в список [SpeedLimit]. */
object SpeedLimitAssetLoader {

    fun loadSpeedLimits(context: Context, assetFileName: String = "speed_limits.json"): List<SpeedLimit> {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                val direction = if (obj.getString("direction") == "even") Direction.EVEN else Direction.ODD
                val a = ChainageFormatter.toMeters(obj.getInt("km_start"), obj.getInt("pk_start"))
                val b = ChainageFormatter.toMeters(obj.getInt("km_end"), obj.getInt("pk_end"))

                add(
                    SpeedLimit(
                        direction = direction,
                        startM = minOf(a, b),
                        endM = maxOf(a, b) + PICKET_LENGTH_M,
                        speedKmh = obj.getInt("speed_kmh"),
                        sourceText = obj.getString("source_text")
                    )
                )
            }
        }
    }

    private const val PICKET_LENGTH_M = 100.0
}
