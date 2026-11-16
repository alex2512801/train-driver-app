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
    fun `blockRangeAt covers only the 15km block the point falls into`() {
        // evenPoints: 100м/10.0, 200м/20.0, 300м/15.0 — все внутри одного блока 0-15000м.
        val profile = ReliefProfile(evenPoints)
        val (min, max) = profile.blockRangeAt(Direction.EVEN, 150.0)!!
        assertEquals(10.0, min, 0.0001)
        assertEquals(20.0, max, 0.0001)
    }

    @Test
    fun `blockRangeAt uses a different block for points 15km+ apart`() {
        val farPoint = ReliefPoint(Direction.EVEN, 20000.0, 999.0)
        val profile = ReliefProfile(evenPoints + farPoint)
        // Точка на 150м по-прежнему видит только свой блок (10.0-20.0), а не 999.0 из
        // дальнего блока — иначе редкий экстремум на всём маршруте сжал бы обычный рельеф
        // почти до прямой линии (машинист: "ни бугров не видно... сделай как на режимных
        // картах").
        val (min, max) = profile.blockRangeAt(Direction.EVEN, 150.0)!!
        assertEquals(10.0, min, 0.0001)
        assertEquals(20.0, max, 0.0001)
    }

    @Test
    fun `blockRangeAt is null for a direction with no data`() {
        val profile = ReliefProfile(evenPoints)
        assertNull(profile.blockRangeAt(Direction.ODD, 150.0))
    }
}
