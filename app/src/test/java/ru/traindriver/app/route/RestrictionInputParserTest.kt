package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionInputParserTest {

    @Test
    fun `point restriction - only start filled`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", speed = "40")
        )
        assertTrue(result is RestrictionInputResult.Success)
        val r = (result as RestrictionInputResult.Success).restriction
        assertEquals(ChainageFormatter.toMeters(6315, 5), r.startM, 0.001)
        assertEquals(r.startM, r.endM, 0.001) // точка: конец == начало
        assertEquals(40, r.speedKmh)
        assertEquals(null, r.path)
    }

    @Test
    fun `within one km - only end pk filled`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", endPk = "8", speed = "40")
        )
        val r = (result as RestrictionInputResult.Success).restriction
        assertEquals(ChainageFormatter.toMeters(6315, 8) + 100.0, r.endM, 0.001)
    }

    @Test
    fun `across km boundary - end km and end pk filled`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", endKm = "6318", endPk = "2", speed = "40")
        )
        val r = (result as RestrictionInputResult.Success).restriction
        assertEquals(ChainageFormatter.toMeters(6318, 2) + 100.0, r.endM, 0.001)
    }

    @Test
    fun `end km without end pk is treated as no end at all (point)`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", endKm = "6318", speed = "40")
        )
        val r = (result as RestrictionInputResult.Success).restriction
        assertEquals(r.startM, r.endM, 0.001)
    }

    @Test
    fun `path blank means null (current path)`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", speed = "40")
        )
        assertEquals(null, (result as RestrictionInputResult.Success).restriction.path)
    }

    @Test
    fun `path filled is parsed as int`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", speed = "40", path = "2")
        )
        assertEquals(2, (result as RestrictionInputResult.Success).restriction.path)
    }

    @Test
    fun `missing start km, start pk or speed reports which fields`() {
        val result = RestrictionInputParser.parse(RestrictionFormFields(startKm = "6315"))
        assertTrue(result is RestrictionInputResult.Error)
        val error = result as RestrictionInputResult.Error
        assertEquals(setOf(RestrictionField.START_PK, RestrictionField.SPEED), error.fields)
    }

    @Test
    fun `start pk below 1 is rejected`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "0", speed = "40")
        )
        assertTrue(result is RestrictionInputResult.Error)
        assertEquals(setOf(RestrictionField.START_PK), (result as RestrictionInputResult.Error).fields)
    }

    @Test
    fun `end before start is rejected`() {
        val result = RestrictionInputParser.parse(
            RestrictionFormFields(startKm = "6315", startPk = "5", endKm = "6310", endPk = "2", speed = "40")
        )
        assertTrue(result is RestrictionInputResult.Error)
    }

    @Test
    fun `sorting ascending for even, descending for odd`() {
        val restrictions = listOf(
            Restriction(3000.0, 3000.0, 40, null),
            Restriction(1000.0, 1000.0, 40, null),
            Restriction(2000.0, 2000.0, 40, null),
        )
        assertEquals(
            listOf(1000.0, 2000.0, 3000.0),
            restrictions.sortedForDirection(Direction.EVEN).map { it.startM }
        )
        assertEquals(
            listOf(3000.0, 2000.0, 1000.0),
            restrictions.sortedForDirection(Direction.ODD).map { it.startM }
        )
    }
}
