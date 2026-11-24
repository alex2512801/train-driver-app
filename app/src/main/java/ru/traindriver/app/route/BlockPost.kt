package ru.traindriver.app.route

import android.content.Context
import org.json.JSONObject

/**
 * Блок-пост (ТЗ раздел 8) — машинист поправил: это НЕ пиктограмма, а ещё один входной сигнал
 * прямо на перегоне, рисуется как обычный сигнал на линии сигналов (см. [label]). Два случая:
 *  - дополнительный, между двумя существующими сигналами (номера раньше не было) — подпись
 *    "Ч Б/п" / "Н Б/п";
 *  - встал НА МЕСТО существующего сигнала (тот номер/литера исчезает) — подпись
 *    "Ч (N) Б/п" / "Н (N) Б/п", где N — номер заменённого сигнала ([replacesSignal]).
 * block_posts.json пока не различает эти два случая (нет поля "replaces") — все точки сейчас
 * трактуются как дополнительные, [replacesSignal] всегда null, пока машинист не пришлёт, какие
 * из точек — замена и какого именно сигнала.
 */
data class BlockPost(
    val direction: Direction,
    val chainageM: Double,
    val replacesSignal: String? = null
) {
    val label: String
        get() {
            val dirLetter = if (direction == Direction.EVEN) "Ч" else "Н"
            return if (replacesSignal != null) "$dirLetter ($replacesSignal) Б/п" else "$dirLetter Б/п"
        }
}

/** Читает block_posts.json ({"even": [...], "odd": [...]}) в список [BlockPost]. */
object BlockPostAssetLoader {

    fun loadBlockPosts(context: Context, assetFileName: String = "block_posts.json"): List<BlockPost> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val root = JSONObject(json)
        val result = mutableListOf<BlockPost>()
        for ((key, direction) in listOf("even" to Direction.EVEN, "odd" to Direction.ODD)) {
            val array = root.getJSONArray(key)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                val replaces = if (obj.has("replaces") && !obj.isNull("replaces")) obj.getString("replaces") else null
                result.add(BlockPost(direction, chainageM, replaces))
            }
        }
        return result
    }
}
