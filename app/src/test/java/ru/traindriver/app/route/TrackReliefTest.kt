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
}
