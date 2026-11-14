package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Одна точка рельефа (ТЗ раздел 7/15) — [heightPt] честен только в пределах одной страницы
 * исходного PDF-профиля, [heightStitched] уже склеен в одну сквозную шкалу по всему маршруту
 * (tools/stitch_relief.py, README шаг 15). Для графика нужен именно [heightStitched]. Высота —
 * координата в pt исходного PDF, НЕ метры (калибровки в реальные метры нет и не предвидится).
 */
data class ReliefPoint(
    val direction: Direction,
    val chainageM: Double,
    val heightStitched: Double
)

/**
 * Профиль рельефа по направлениям — отсортированные по km+пк точки + линейная интерполяция
 * между соседними (как в HTML-макете, realReliefHeightAt). Вне диапазона покрытия (между
 * km5930 и km6604, см. README шаг 15) возвращает null — вызывающий код должен сам решить, что
 * показать вместо рельефа (пока нигде за пределами этого участка реальных данных нет).
 */
class ReliefProfile(points: List<ReliefPoint>) {

    private val byDirection: Map<Direction, List<ReliefPoint>> =
        points.groupBy { it.direction }.mapValues { (_, pts) -> pts.sortedBy { it.chainageM } }

    /** Высота рельефа (pt, склеенная шкала) в точке [meters], либо null — вне покрытия. */
    fun heightAt(direction: Direction, meters: Double): Double? {
        val pts = byDirection[direction] ?: return null
        if (pts.isEmpty() || meters < pts.first().chainageM || meters > pts.last().chainageM) return null
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].chainageM <= meters) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        if (b.chainageM == a.chainageM) return a.heightStitched
        val t = (meters - a.chainageM) / (b.chainageM - a.chainageM)
        return a.heightStitched + (b.heightStitched - a.heightStitched) * t
    }

    /**
     * Самая низкая точка рельефа в видимом окне [fromM, toM] — нужна для панорамирования
     * (машинист: самая низкая точка видимого окна лежит у пола полосы, см. drawGridAndRelief
     * в HTML-макете). Null, если в окне вообще нет покрытия.
     */
    fun minHeightInRange(direction: Direction, fromM: Double, toM: Double): Double? =
        byDirection[direction]
            ?.asSequence()
            ?.filter { it.chainageM in fromM..toM }
            ?.minOfOrNull { it.heightStitched }

    /**
     * Самая высокая точка рельефа в видимом окне [fromM, toM] — машинист: рельеф рисуется
     * "от максимума в видимом окне" (см. HTML-макет), именно эта точка должна сидеть у
     * верхней границы полосы рельефа, а не какая-то произвольная. Раньше полоса считалась
     * только от минимума (см. [minHeightInRange]) без верхней привязки — из-за этого высокий
     * участок рельефа мог нарисоваться ВЫШЕ линии сигналов (машинист прислал скриншот такого
     * наложения). Null, если в окне вообще нет покрытия.
     */
    fun maxHeightInRange(direction: Direction, fromM: Double, toM: Double): Double? =
        byDirection[direction]
            ?.asSequence()
            ?.filter { it.chainageM in fromM..toM }
            ?.maxOfOrNull { it.heightStitched }

    fun hasCoverage(direction: Direction): Boolean = byDirection[direction]?.isNotEmpty() == true
}

/** Читает track_relief.json в [ReliefProfile]. */
object ReliefAssetLoader {

    fun loadRelief(context: Context, assetFileName: String = "track_relief.json"): ReliefProfile {
        val json = context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
        val array = JSONArray(json)
        val points = buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val direction = if (obj.getString("direction") == "чётное") Direction.EVEN else Direction.ODD
                val chainageM = ChainageFormatter.toMeters(obj.getInt("km"), obj.getInt("pk"))
                add(ReliefPoint(direction, chainageM, obj.getDouble("height_stitched")))
            }
        }
        return ReliefProfile(points)
    }
}
