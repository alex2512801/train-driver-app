package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackReliefTest {

    private val evenPoints = listOf(
        ReliefPoint(Direction.EVEN, 100.0, 10.0),
        ReliefPoint(Direction.EVEN, 200.0, 20.0),
        ReliefPoint(Direction.EVEN, 300.0, 15.0)
    )

    @Test
    fun `heightAt interpolates linearly between two points`() {
        val profile = ReliefProfile(evenPoints)
        assertEquals(15.0, profile.heightAt(Direction.EVEN, 150.0)!!, 0.0001)
    }

    @Test
    fun `heightAt returns exact value at a known point`() {
        val profile = ReliefProfile(evenPoints)
        assertEquals(20.0, profile.heightAt(Direction.EVEN, 200.0)!!, 0.0001)
    }

    @Test
    fun `heightAt is null outside coverage`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.heightAt(Direction.EVEN, 50.0))
        assertNull(profile.heightAt(Direction.EVEN, 350.0))
    }

    @Test
    fun `heightAt is null for a direction with no data`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.heightAt(Direction.ODD, 150.0))
    }

    @Test
    fun `heightAt works regardless of input order (points get sorted)`() {
        val shuffled = listOf(evenPoints[2], evenPoints[0], evenPoints[1])
        val profile = ReliefProfile(shuffled)
        assertEquals(15.0, profile.heightAt(Direction.EVEN, 150.0)!!, 0.0001)
    }

    @Test
    fun `minHeightInRange finds the lowest point within the window`() {
        val profile = ReliefProfile(evenPoints)
        assertEquals(10.0, profile.minHeightInRange(Direction.EVEN, 100.0, 300.0)!!, 0.0001)
        // Самая низкая точка видимого окна (не всего профиля) — для панорамирования.
        assertEquals(15.0, profile.minHeightInRange(Direction.EVEN, 250.0, 300.0)!!, 0.0001)
    }

    @Test
    fun `minHeightInRange is null when the window has no coverage`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.minHeightInRange(Direction.EVEN, 1000.0, 2000.0))
    }

    @Test
    fun `maxHeightInRange finds the highest point within the window`() {
        val profile = ReliefProfile(evenPoints)
        assertEquals(20.0, profile.maxHeightInRange(Direction.EVEN, 100.0, 300.0)!!, 0.0001)
        // Самая высокая точка видимого окна (не всего профиля) — графику нужно сидеть у
        // неё верхней границей, см. doc-комментарий у maxHeightInRange.
        assertEquals(15.0, profile.maxHeightInRange(Direction.EVEN, 250.0, 300.0)!!, 0.0001)
    }

    @Test
    fun `maxHeightInRange is null when the window has no coverage`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.maxHeightInRange(Direction.EVEN, 1000.0, 2000.0))
    }

    @Test
    fun `hasCoverage reflects which directions have data`() {
        val profile = ReliefProfile(evenPoints)
        assertTrue(profile.hasCoverage(Direction.EVEN))
        assertTrue(!profile.hasCoverage(Direction.ODD))
    }

    @Test
    fun `localRangeAt returns min-max of points within the sliding window around the point`() {
        // evenPoints: 100м/10.0, 200м/20.0, 300м/15.0 — все внутри окна ±7500м вокруг 150м.
        val profile = ReliefProfile(evenPoints)
        val (min, max) = profile.localRangeAt(Direction.EVEN, 150.0)!!
        assertEquals(10.0, min, 0.0001)
        assertEquals(20.0, max, 0.0001)
    }

    @Test
    fun `localRangeAt ignores points far outside the sliding window`() {
        val farPoint = ReliefPoint(Direction.EVEN, 20000.0, 999.0)
        val profile = ReliefProfile(evenPoints + farPoint)
        // 20000м от 150м — намного дальше окна ±7500м, далёкий экстремум не должен влиять на
        // локальный диапазон рядом, иначе редкий экстремум на всём маршруте сжал бы обычный
        // рельеф почти до прямой линии (машинист: "ни бугров не видно... сделай как на
        // режимных картах").
        val (min, max) = profile.localRangeAt(Direction.EVEN, 150.0)!!
        assertEquals(10.0, min, 0.0001)
        assertEquals(20.0, max, 0.0001)
    }

    @Test
    fun `localRangeAt changes gradually between widely spaced points, not in one jump`() {
        // Три далёкие друг от друга точки (0, 40000, 80000 — за пределами двойного радиуса
        // окна 7500м) с нарастающей высотой: максимум у каждой точки должен расти ПОСТЕПЕННО
        // от одной к другой, а не одинаковым скачком на какой-то фиксированной границе между
        // ними (машинист: "не был ступенями, а весь полностью, который поднимался").
        val points = listOf(
            ReliefPoint(Direction.EVEN, 0.0, 10.0),
            ReliefPoint(Direction.EVEN, 40000.0, 10.0),
            ReliefPoint(Direction.EVEN, 80000.0, 200.0)
        )
        val profile = ReliefProfile(points)
        val nearSecond = profile.localRangeAt(Direction.EVEN, 35000.0)!!.second
        val midway = profile.localRangeAt(Direction.EVEN, 60000.0)!!.second
        val nearThird = profile.localRangeAt(Direction.EVEN, 75000.0)!!.second
        // Монотонный, непрерывный рост — не "плоско-плоско-скачок".
        assertTrue(nearSecond <= midway)
        assertTrue(midway <= nearThird)
        assertTrue(nearSecond < nearThird)
    }

    @Test
    fun `localRangeAt is null for a direction with no data`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.localRangeAt(Direction.ODD, 150.0))
    }

    @Test
    fun `slopeAt interpolates linearly and keeps the sign for even direction`() {
        val points = listOf(
            ReliefPoint(Direction.EVEN, 100.0, 10.0, slopePermille = 5.0),
            ReliefPoint(Direction.EVEN, 200.0, 20.0, slopePermille = -3.0)
        )
        val profile = ReliefProfile(points)
        assertEquals(1.0, profile.slopeAt(Direction.EVEN, 150.0)!!, 0.0001)
    }

    @Test
    fun `slopeAt flips the sign for odd direction`() {
        // Уклон в данных посчитан по направлению роста пикетажа (чётное) — для нечётного
        // (едем в сторону убывания) подъём/спуск физически меняются местами.
        val points = listOf(
            ReliefPoint(Direction.EVEN, 100.0, 10.0, slopePermille = 5.0),
            ReliefPoint(Direction.EVEN, 200.0, 20.0, slopePermille = 5.0),
            ReliefPoint(Direction.ODD, 100.0, 10.0, slopePermille = 5.0),
            ReliefPoint(Direction.ODD, 200.0, 20.0, slopePermille = 5.0)
        )
        val profile = ReliefProfile(points)
        assertEquals(5.0, profile.slopeAt(Direction.EVEN, 150.0)!!, 0.0001)
        assertEquals(-5.0, profile.slopeAt(Direction.ODD, 150.0)!!, 0.0001)
    }

    @Test
    fun `slopeAt is null outside coverage`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.slopeAt(Direction.EVEN, 1000.0))
    }
}
