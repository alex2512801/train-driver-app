package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray

/**
 * Одна точка рельефа (ТЗ раздел 7/15). [heightStitched] — настоящая высота над уровнем моря,
 * в метрах (машинист прислал новые данные с реальной высотой и уклоном — раньше здесь была
 * координата в pt исходного PDF-скана без привязки к метрам; имя поля осталось прежним, чтобы
 * не трогать всю отрисовку в TrackProfileView, которая его использует, — важен смысл "высота
 * для рисования рельефа", а не единицы измерения как таковые).
 * [direction] больше ничего не меняет в самих значениях (настоящая высота и уклон не зависят от
 * направления движения) — оставлено только чтобы не менять публичный API ReliefProfile, которым
 * уже пользуется TrackProfileView; одна и та же точка попадает в данные дважды, под обоими
 * направлениями, см. [ReliefAssetLoader].
 */
data class ReliefPoint(
    val direction: Direction,
    val chainageM: Double,
    val heightStitched: Double,
    val slopePermille: Double = 0.0
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
     * Уклон (ТЗ раздел 7, статусная строка "Уклон=") в промилле, в точке [meters] — та же
     * линейная интерполяция между соседними точками данных, что и у высоты в [heightAt].
     * Null — вне покрытия (нет данных рельефа в этом месте).
     *
     * Уклон в данных посчитан по направлению РОСТА настоящего пикетажа (т.е. как для чётного
     * направления, см. doc-комментарий у [ReliefAssetLoader]) — для нечётного (едем в сторону
     * УБЫВАНИЯ пикетажа) подъём и спуск физически меняются местами, поэтому знак здесь
     * переворачивается: то, что "в гору" при росте км, становится "под гору" при движении в
     * обратную сторону, и наоборот.
     */
    fun slopeAt(direction: Direction, meters: Double): Double? {
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
        val raw = if (b.chainageM == a.chainageM) {
            a.slopePermille
        } else {
            val t = (meters - a.chainageM) / (b.chainageM - a.chainageM)
            a.slopePermille + (b.slopePermille - a.slopePermille) * t
        }
        return if (direction == Direction.ODD) -raw else raw
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
}

/**
 * Читает track_relief.json в [ReliefProfile] — машинист прислал новые данные (реальная высота
 * над уровнем моря + уклон в промилле, через каждые ~200м по настоящему пикетажу РЖД, из
 * набора данных по всей Забайкальской дороге) — формат плоский (без направления, см.
 * doc-комментарий у [ReliefPoint]): каждая запись попадает в [ReliefProfile] дважды, под ОБА
 * направления, т.к. сама высота/уклон от направления не зависят.
 */
object ReliefAssetLoader {

    fun loadRelief(context: Context, assetFileName: String = "track_relief.json"): ReliefProfile {
        val json = DataOverrideStore.readJson(context, assetFileName)
        val array = JSONArray(json)
        val points = buildList(array.length() * 2) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val chainageM = obj.getDouble("real_m")
                val elevationM = obj.getDouble("elevation_m")
                val slopePermille = obj.getDouble("slope_permille")
                add(ReliefPoint(Direction.EVEN, chainageM, elevationM, slopePermille))
                add(ReliefPoint(Direction.ODD, chainageM, elevationM, slopePermille))
            }
        }
        return ReliefProfile(points)
    }
}
