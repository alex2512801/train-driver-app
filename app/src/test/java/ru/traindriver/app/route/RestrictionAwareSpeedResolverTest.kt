package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestrictionAwareSpeedResolverTest {

    private val constantLimits = listOf(
        SpeedLimit(Direction.EVEN, 0.0, 5000.0, 80, "0-5000: 80"),
        SpeedLimit(Direction.EVEN, 5000.0, 10000.0, 60, "5000-10000: 60"),
    )
    private val speedLimitResolver = SpeedLimitResolver(constantLimits)

    @Test
    fun `without restrictions, matches the plain SpeedLimitResolver`() {
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, emptyList())
        assertEquals(80, resolver.speedAt(Direction.EVEN, 1000.0))
        assertEquals(60, resolver.speedAt(Direction.EVEN, 6000.0))
    }

    @Test
    fun `a restriction wins inside its range, even though it's WIDER than the constant step under it`() {
        // Ограничение 30 км/ч на 2000-8000 перекрывает границу постоянных ступеней 80/60 —
        // должно побеждать целиком, а не только там, где оно "уже" постоянной ступени.
        val restrictions = listOf(Restriction(startM = 2000.0, endM = 8000.0, speedKmh = 30, path = null))
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, restrictions)

        assertEquals(80, resolver.speedAt(Direction.EVEN, 1000.0)) // до ограничения — постоянное
        assertEquals(30, resolver.speedAt(Direction.EVEN, 2000.0)) // ровно на границе начала
        assertEquals(30, resolver.speedAt(Direction.EVEN, 5000.0)) // внутри, там где была бы граница 80/60
        assertEquals(30, resolver.speedAt(Direction.EVEN, 8000.0)) // ровно на границе конца
        assertEquals(60, resolver.speedAt(Direction.EVEN, 8001.0)) // сразу после — снова постоянное
    }

    @Test
    fun `findNextChange reports entering a restriction ahead`() {
        val restrictions = listOf(Restriction(startM = 3000.0, endM = 3500.0, speedKmh = 20, path = null))
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, restrictions)

        val next = resolver.findNextChange(Direction.EVEN, fromMeters = 1000.0, forward = true)
        assertEquals(20, next?.speedKmh)
        assertEquals(2000.0, next?.distanceM ?: -1.0, 0.01)
    }

    @Test
    fun `findNextChange reports leaving a restriction ahead, back to the constant limit`() {
        val restrictions = listOf(Restriction(startM = 1000.0, endM = 1500.0, speedKmh = 20, path = null))
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, restrictions)

        // Стоим внутри ограничения (1200), впереди — конец ограничения на 1500 (включительно,
        // ещё 20), дальше опять 80. Шаг сканирования 10 м, поэтому первая точка ВНЕ диапазона —
        // 1510, а не 1500 (1500 — последняя точка, ещё внутри).
        val next = resolver.findNextChange(Direction.EVEN, fromMeters = 1200.0, forward = true)
        assertEquals(80, next?.speedKmh)
        assertEquals(310.0, next?.distanceM ?: -1.0, 0.01)
    }

    @Test
    fun `works for the odd (decreasing chainage) direction too`() {
        val restrictions = listOf(Restriction(startM = 2000.0, endM = 2500.0, speedKmh = 15, path = null))
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, restrictions)

        val next = resolver.findNextChange(Direction.EVEN, fromMeters = 4000.0, forward = false)
        assertEquals(15, next?.speedKmh)
        assertEquals(1500.0, next?.distanceM ?: -1.0, 0.01)
    }

    @Test
    fun `no change within lookahead returns null`() {
        val resolver = RestrictionAwareSpeedResolver(speedLimitResolver, emptyList())
        assertNull(resolver.findNextChange(Direction.EVEN, fromMeters = 6000.0, forward = true, maxLookaheadM = 500.0))
    }
}
