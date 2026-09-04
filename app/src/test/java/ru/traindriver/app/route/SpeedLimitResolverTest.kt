package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitResolverTest {

    // Имитация "Хилок (5932 пк 7 - 5935 пк 3) 7 путь" -> 60, с исключением
    // "5932 пк 7 - 5933 пк 1" -> 50 внутри неё (см. README).
    private val broad = SpeedLimit(
        direction = Direction.EVEN,
        startM = ChainageFormatter.toMeters(5932, 7),
        endM = ChainageFormatter.toMeters(5935, 3) + 100.0,
        speedKmh = 60,
        sourceText = "broad"
    )
    private val narrow = SpeedLimit(
        direction = Direction.EVEN,
        startM = ChainageFormatter.toMeters(5932, 7),
        endM = ChainageFormatter.toMeters(5933, 1) + 100.0,
        speedKmh = 50,
        sourceText = "narrow"
    )
    private val resolver = SpeedLimitResolver(listOf(broad, narrow))

    @Test
    fun `narrower range wins inside the exception`() {
        val at = ChainageFormatter.toMeters(5932, 8)
        assertEquals(50, resolver.speedAt(Direction.EVEN, at)?.speedKmh)
    }

    @Test
    fun `broad range applies outside the exception but inside the block`() {
        val at = ChainageFormatter.toMeters(5934, 5)
        assertEquals(60, resolver.speedAt(Direction.EVEN, at)?.speedKmh)
    }

    @Test
    fun `wrong direction never matches`() {
        val at = ChainageFormatter.toMeters(5932, 8)
        assertNull(resolver.speedAt(Direction.ODD, at))
    }

    @Test
    fun `outside every range returns null`() {
        val at = ChainageFormatter.toMeters(6000, 1)
        assertNull(resolver.speedAt(Direction.EVEN, at))
    }
}
