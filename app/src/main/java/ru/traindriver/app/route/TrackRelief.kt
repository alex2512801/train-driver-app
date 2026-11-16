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
     * Самая низкая точка рельефа в видимом окне [fromM, toM] — нужна для масштабирования
     * полосы рельефа (машинист: "самая нижняя точка... внизу, чтобы поезд был как можно
     * ниже, чтобы не заезжал за линию сигналов" — см. drawReliefAndTrain, где эта точка
     * вместе с [maxHeightInRange] задаёт масштаб так, чтобы весь видимый перепад высот
     * укладывался в полосу ровно от пола до потолка). Границы окна учитываются так же, как в
     * [maxHeightInRange] — и по той же причине (при панорамировании настоящий минимум кривой
     * на интерполированной границе экрана мог оказаться НИЖЕ посчитанного здесь, если
     * смотреть только на точки данных строго внутри окна).
     * Null, если в окне вообще нет покрытия.
     */
    fun minHeightInRange(direction: Direction, fromM: Double, toM: Double): Double? {
        val pts = byDirection[direction] ?: return null
        val interior = pts.asSequence().filter { it.chainageM in fromM..toM }.map { it.heightStitched }
        val boundary = sequenceOf(heightAt(direction, fromM), heightAt(direction, toM)).filterNotNull()
        return (interior + boundary).minOrNull()
    }

    /**
     * Самая высокая точка рельефа в видимом окне [fromM, toM] — машинист: рельеф рисуется
     * "от максимума в видимом окне" (см. HTML-макет), именно эта точка должна сидеть у
     * верхней границы полосы рельефа, а не какая-то произвольная. Раньше полоса считалась
     * только от минимума (см. [minHeightInRange]) без верхней привязки — из-за этого высокий
     * участок рельефа мог нарисоваться ВЫШЕ линии сигналов (машинист прислал скриншот такого
     * наложения).
     *
     * ВАЖНО: учитываются не только точки данных СТРОГО ВНУТРИ окна, но и интерполированная
     * высота ровно на его границах ([fromM], [toM]) — иначе при панорамировании (машинист:
     * "когда рукой пролистываю, слева, где самый высокий") максимум мог оказаться ЗАНИЖЕННЫМ:
     * если самая высокая точка рельефа лежит чуть ЗА пределами окна, [heightAt] всё равно
     * рисует интерполированную (между этой точкой и соседней внутри окна) высоту ровно у
     * самого края экрана, и эта нарисованная высота могла оказаться больше, чем здесь же
     * посчитанный максимум — то есть рельеф рисовался выше собственной верхней границы.
     * Для кусочно-линейной интерполяции максимум на отрезке всегда достигается либо в одной
     * из известных точек данных внутри него, либо на самой границе отрезка — третьего не дано,
     * поэтому достаточно добавить эти две граничные точки.
     * Null, если в окне вообще нет покрытия.
     */
    fun maxHeightInRange(direction: Direction, fromM: Double, toM: Double): Double? {
        val pts = byDirection[direction] ?: return null
        val interior = pts.asSequence().filter { it.chainageM in fromM..toM }.map { it.heightStitched }
        val boundary = sequenceOf(heightAt(direction, fromM), heightAt(direction, toM)).filterNotNull()
        return (interior + boundary).maxOrNull()
    }

    fun hasCoverage(direction: Direction): Boolean = byDirection[direction]?.isNotEmpty() == true

    /**
     * Минимум и максимум высоты по ВСЕМУ маршруту этого направления — не по видимому окну (то
     * был неверный путь, см. doc-комментарий у drawReliefAndTrain в TrackProfileView: машинист
     * поправил — "высота профиля меняется, хотя должна быть пропорционально... горы не должны
     * увеличиваться... мне нужно, чтобы были точные данные"). Масштаб рельефа строится ОДИН РАЗ
     * по этому глобальному диапазону (см. [TrackProfileView.setRelief]) и дальше остаётся
     * постоянным при любом панорамировании — пропорции реального перепада высот сохраняются
     * настоящими, а не "резиновыми", как было при пересчёте от видимого окна на каждый кадр.
     * Null, если для направления вообще нет данных.
     */
    fun globalRange(direction: Direction): Pair<Double, Double>? {
        val pts = byDirection[direction] ?: return null
        if (pts.isEmpty()) return null
        var lo = pts[0].heightStitched
        var hi = lo
        for (p in pts) {
            if (p.heightStitched < lo) lo = p.heightStitched
            if (p.heightStitched > hi) hi = p.heightStitched
        }
        return lo to hi
    }
}

/** Читает track_relief.json в [ReliefProfile]. */
object ReliefAssetLoader {

    fun loadRelief(context: Context, assetFileName: String = "track_relief.json"): ReliefProfile {
        val json = DataOverrideStore.readJson(context, assetFileName)
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
