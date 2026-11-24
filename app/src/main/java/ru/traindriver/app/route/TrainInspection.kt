package ru.traindriver.app.route

import android.content.Context
import org.json.JSONObject

/** Кто именно осматривает состав в этой точке (ТЗ, "Осмотр поезда бригадой") — от этого
 *  зависит, какой из двух голосов проигрывается (см. MainActivity.updateDirectionUi). */
enum class InspectorRole { MACHINIST, ASSISTANT }

/** Одна точка начала осмотра поезда бригадой на ходу. В отличие от остальных голосовых
 *  оповещений (которые предупреждают ЗАРАНЕЕ, за 600-2000 м) эта должна звучать РОВНО в момент
 *  начала осмотра, а не загодя (машинист: "голосовое срабатывает в момент начала этого осмотра") —
 *  см. doc-комментарий у TRAIN_INSPECTION_THRESHOLD_M в MainActivity. */
data class TrainInspectionPoint(
    val direction: Direction,
    val chainageM: Double,
    val inspector: InspectorRole
)

/**
 * Читает train_inspection.json — машинист прислал отдельным файлом км+пк, где на ходу
 * осматривает состав машинист и где помощник машиниста, по четырём смежным перегонам
 * (Хилок-Карымская и Карымская-Чернышевск, по обоим направлениям каждый), которые вместе и
 * составляют весь уже обслуживаемый маршрут Хилок-Чернышевск (см. RouteTrack). Остальные четыре
 * листа исходной таблицы — П-Завод-Хилок и Карымская-Оловянная (оба направления) — относятся к
 * участкам, для которых в приложении пока нет линии маршрута (GPS по ним не определить, см. чат),
 * поэтому сохранены в файле под ключом "unrouted_segments" про запас, но этим загрузчиком не
 * читаются и ни на что не влияют, пока для них не появится собственная линия маршрута.
 */
object TrainInspectionAssetLoader {

    fun loadTrainInspectionPoints(context: Context, assetFileName: String = "train_inspection.json"): List<TrainInspectionPoint> {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val root = JSONObject(json)
        val result = mutableListOf<TrainInspectionPoint>()
        for ((directionKey, direction) in listOf("even" to Direction.EVEN, "odd" to Direction.ODD)) {
            val group = root.getJSONObject(directionKey)
            for ((roleKey, role) in listOf("machinist" to InspectorRole.MACHINIST, "assistant" to InspectorRole.ASSISTANT)) {
                val array = group.getJSONArray(roleKey)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                    result.add(TrainInspectionPoint(direction, chainageM, role))
                }
            }
        }
        return result
    }
}
