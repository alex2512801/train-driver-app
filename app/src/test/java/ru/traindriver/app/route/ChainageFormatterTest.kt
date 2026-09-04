package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainageFormatterTest {

    // Обычный км+пк (по умолчанию, ТЗ раздел 7) — им же записаны speed_limits.json и т.п.:
    // км = floor(метры/1000), без +1.
    @Test
    fun `format uses plain km, no offset`() {
        assertEquals("0км 1пк", ChainageFormatter.format(50.0))
        assertEquals("6234км 7пк", ChainageFormatter.format(6_234_670.0))
    }

    @Test
    fun `format start of route is km 0`() {
        assertEquals("0км 1пк", ChainageFormatter.format(0.0))
    }

    @Test
    fun `format last meter of a km still belongs to that km`() {
        assertEquals("0км 10пк", ChainageFormatter.format(999.0))
    }

    @Test
    fun `format next km starts a new label`() {
        assertEquals("1км 1пк", ChainageFormatter.format(1000.0))
    }

    @Test
    fun `format negative input is clamped to route start`() {
        assertEquals("0км 1пк", ChainageFormatter.format(-100.0))
    }

    // КЛУБ-У — отдельный формат, включается по тапу, +1 к км. Примеры из ТЗ раздел 7.
    @Test
    fun `formatKlubU matches the spec examples`() {
        assertEquals("1км 1пк", ChainageFormatter.formatKlubU(50.0))
        assertEquals("6235км 7пк", ChainageFormatter.formatKlubU(6_234_670.0))
    }

    // toMeters — обратное к обычному км+пк (не к КЛУБ-У), т.к. именно в этом формате
    // записаны все остальные данные.
    @Test
    fun `toMeters is the inverse of the plain format, not KlubU`() {
        assertEquals(0.0, ChainageFormatter.toMeters(0, 1), 0.0)
        assertEquals(6_234_600.0, ChainageFormatter.toMeters(6234, 7), 0.0)
        assertEquals(
            "6234км 7пк",
            ChainageFormatter.format(ChainageFormatter.toMeters(6234, 7))
        )
    }

    @Test
    fun `toMeters matches how source data is written, e g Khilok station limits`() {
        // "Хилок (5932 пк 7 - 5935 пк 3)" из speed_limits.json — источник истины для формата.
        assertEquals(5_932_600.0, ChainageFormatter.toMeters(5932, 7), 0.0)
    }
}
