package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Точка КТСМ (Комплекс Технических Средств Многофункциональный — аппаратура контроля букс на
 * ходу поезда) — голосовое оповещение [ru.traindriver.app.audio.Announcement.KTSM]. Машинист
 * прислал полный список от Петровска до Чернышевска плюс ветку на Борзю (своя, отдельная
 * нумерация км от развилки — в файле она идёт отдельным "задним" блоком после основного), но
 * пока в приложении размечен только участок Хилок — Чернышевск (км5930-6604, см. README шаг
 * 15) — остальное (до км5930, и вся ветка на Борзю) не включено в ktsm.json.
 */
data class Ktsm(
    val direction: Direction,
    val chainageM: Double
)

/** Читает ktsm.json в список [Ktsm]. */
object KtsmAssetLoader {

    fun loadKtsm(context: Context, assetFileName: String = "ktsm.json"): List<Ktsm> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                add(Ktsm(direction, chainageM))
            }
        }
    }
}
