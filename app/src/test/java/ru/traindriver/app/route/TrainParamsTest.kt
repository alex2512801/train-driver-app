package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainParamsTest {

    @Test
    fun `default params match the mockup and give the same 864,5 m composition length`() {
        val params = TrainParamsStore.DEFAULT
        assertEquals(864.5, params.composition.lengthM, 0.001)
    }

    @Test
    fun `tare per wagon is (brutto minus netto) over wagon count`() {
        val params = TrainParamsStore.DEFAULT.copy(bruttoT = 4200.0, nettoT = 3100.0, wagonCount = 40)
        assertEquals(27.5, params.tarePerWagonT!!, 0.001)
    }

    @Test
    fun `tare per wagon is null when there are no wagons`() {
        val params = TrainParamsStore.DEFAULT.copy(wagonCount = 0)
        assertNull(params.tarePerWagonT)
    }
}
