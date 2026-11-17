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

    // Масштаб рельефа — СКОЛЬЗЯЩЕЕ окно ±WINDOW_RADIUS_M вокруг конкретной точки, а не видимое
    // окно экрана и не весь маршрут целиком, и не фиксированный блок 15км (три предыдущих
    // варианта, каждый поправлен машинистом по своей причине):
    //  1) окно просмотра — та же гора меняла нарисованную высоту просто от панорамирования
    //     ("высота профиля меняется... горы не должны увеличиваться... нужны точные данные");
    //  2) весь маршрут сразу — единственный по-настоящему огромный перепад высоты на маршруте
    //     (Яблоновый перевал км6106 против впадины у км6524, ~900 "pt") занял всю шкалу, и обычные
    //     подъёмы/спуски (типично ~50-60 "pt" на 15-20км) сжались почти до прямой линии ("ни
    //     бугров не видно");
    //  3) фиксированный 15-километровый блок — пропорции перестали "резиниться" и бугры снова
    //     стали видны, но на границе блока (раз в 15км) масштаб скачком менялся, и кривая рельефа
    //     рисовалась ЛОМАНОЙ ("он идёт ступенями, и он не двигается вверх-вниз... нужно, чтобы он
    //     не был ступенями, а весь полностью, который поднимался или опускался").
    // Скользящее окно даёт ПЛАВНЫЙ (непрерывный) масштаб, который при этом зависит ТОЛЬКО от
    // собственного км+пк точки (а не от текущего окна просмотра) — высота конкретной точки по-
    // прежнему не меняется при панорамировании, но min/max в соседних точках отличаются совсем
    // чуть-чуть (а не скачком), поэтому сама кривая остаётся гладкой. Диапазон окна — тот же
    // порядок величины (15км), что и у страницы исходных PDF-карт, так что бугры видны так же
    // чётко, как на бумажных режимных картах.
    private data class LocalRangeArrays(val localMin: DoubleArray, val localMax: DoubleArray)

    private val localRanges: Map<Direction, LocalRangeArrays> = byDirection.mapValues { (_, pts) ->
        val (lo, hi) = slidingMinMax(pts, WINDOW_RADIUS_M)
        LocalRangeArrays(lo, hi)
    }

    private fun slidingMinMax(pts: List<ReliefPoint>, radiusM: Double): Pair<DoubleArray, DoubleArray> {
        val n = pts.size
        val localMin = DoubleArray(n)
        val localMax = DoubleArray(n)
        // Два монотонных дека (классический "sliding window minimum/maximum") — каждая точка
        // входит и выходит из окна не больше одного раза, итого O(n) на весь проход.
        val minDeque = ArrayDeque<Int>()
        val maxDeque = ArrayDeque<Int>()
        var left = 0
        var right = 0
        for (i in 0 until n) {
            val windowHi = pts[i].chainageM + radiusM
            while (right < n && pts[right].chainageM <= windowHi) {
                while (minDeque.isNotEmpty() && pts[minDeque.last()].heightStitched >= pts[right].heightStitched) {
                    minDeque.removeLast()
                }
                minDeque.addLast(right)
                while (maxDeque.isNotEmpty() && pts[maxDeque.last()].heightStitched <= pts[right].heightStitched) {
                    maxDeque.removeLast()
                }
                maxDeque.addLast(right)
                right++
            }
            val windowLo = pts[i].chainageM - radiusM
            while (left < right && pts[left].chainageM < windowLo) {
                if (minDeque.isNotEmpty() && minDeque.first() == left) minDeque.removeFirst()
                if (maxDeque.isNotEmpty() && maxDeque.first() == left) maxDeque.removeFirst()
                left++
            }
            localMin[i] = pts[minDeque.first()].heightStitched
            localMax[i] = pts[maxDeque.first()].heightStitched
        }
        return localMin to localMax
    }

    /** Плавный локальный диапазон высот вокруг точки [meters] (скользящее окно, см. выше),
     *  линейно интерполированный между соседними точками — так же, как [heightAt] саму высоту.
     *  Null, если для направления вообще нет данных или [meters] вне покрытия. */
    fun localRangeAt(direction: Direction, meters: Double): Pair<Double, Double>? {
        val pts = byDirection[direction] ?: return null
        if (pts.isEmpty() || meters < pts.first().chainageM || meters > pts.last().chainageM) return null
        val arrays = localRanges[direction] ?: return null
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].chainageM <= meters) lo = mid else hi = mid
        }
        if (pts[hi].chainageM == pts[lo].chainageM) return arrays.localMin[lo] to arrays.localMax[lo]
        val t = (meters - pts[lo].chainageM) / (pts[hi].chainageM - pts[lo].chainageM)
        val minV = arrays.localMin[lo] + (arrays.localMin[hi] - arrays.localMin[lo]) * t
        val maxV = arrays.localMax[lo] + (arrays.localMax[hi] - arrays.localMax[lo]) * t
        return minV to maxV
    }

    companion object {
        private const val WINDOW_RADIUS_M = 7500.0
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
