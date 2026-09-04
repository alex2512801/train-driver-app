package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainageFormatterTest {

    // Примеры из ТЗ (раздел 7): 0000.050 -> 1км 1пк; 6234.670 -> 6235км 7пк.
    @Test
    fun `formats first meter of first km as 1km 1pk`() {
        assertEquals("1км 1пк", ChainageFormatter.format(50.0))
    }

    @Test
    fun `matches spec example for large chainage`() {
        assertEquals("6235км 7пк", ChainageFormatter.format(6_234_670.0))
    }

    @Test
    fun `start of route is 1km 1pk`() {
        assertEquals("1км 1пк", ChainageFormatter.format(0.0))
    }

    @Test
    fun `last meter of a km still belongs to that km`() {
        assertEquals("1км 10пк", ChainageFormatter.format(999.0))
    }

    @Test
    fun `next km starts a new label`() {
        assertEquals("2км 1пк", ChainageFormatter.format(1000.0))
    }

    @Test
    fun `negative input is clamped to route start`() {
        assertEquals("1км 1пк", ChainageFormatter.format(-100.0))
    }

    @Test
    fun `toMeters is the inverse of format`() {
        assertEquals(0.0, ChainageFormatter.toMeters(1, 1), 0.0)
        assertEquals(6_234_600.0, ChainageFormatter.toMeters(6235, 7), 0.0)
        assertEquals("6235км 7пк", ChainageFormatter.format(ChainageFormatter.toMeters(6235, 7)))
    }
}
