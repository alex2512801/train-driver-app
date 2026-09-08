package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationTest {

    private val stations = listOf(
        Station("Хилок", Direction.EVEN, 5933000.0),
        Station("Гыршелун", Direction.EVEN, 5950000.0),
        Station("Хилок", Direction.ODD, 5934000.0),
    )

    @Test
    fun `resolves the nearest station within tolerance`() {
        assertEquals("Хилок", nearestStationName(5933200.0, Direction.EVEN, stations))
    }

    @Test
    fun `returns null when outside every station's tolerance (mid-peregon stop)`() {
        assertNull(nearestStationName(5941000.0, Direction.EVEN, stations))
    }

    @Test
    fun `only considers stations of the given direction`() {
        // 5934000 совпадает точно с Хилок (ODD) — но при направлении EVEN должен найтись
        // ближайший EVEN-кандидат (Хилок EVEN на 5933000, в пределах допуска), а не ODD.
        assertEquals("Хилок", nearestStationName(5934000.0, Direction.EVEN, stations))
    }

    @Test
    fun `picks the closer of two candidates`() {
        // Ровно посередине между Хилок(5933000) и Гыршелун(5950000) - оба вне допуска 1500м,
        // должно вернуть null.
        assertNull(nearestStationName(5941500.0, Direction.EVEN, stations, toleranceM = 1500.0))
    }
}
