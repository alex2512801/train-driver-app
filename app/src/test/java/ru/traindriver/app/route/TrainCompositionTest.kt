package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrainCompositionTest {

    @Test
    fun `length is conditional length times 14 plus locomotive length - mockup default example`() {
        // Значения по умолчанию из HTML-макета ("Параметры" ТЗ раздел 5): 3ЭС5К (52.5 м) +
        // 58 усл. вагонов. Машинист подтвердил формулу: 58 * 14 + 52.5 = 864.5 м.
        val train = TrainComposition(locomotiveSeries = "3ЭС5К", conditionalLengthUslVag = 58.0)
        assertEquals(864.5, train.lengthM, 0.001)
    }

    @Test
    fun `zero conditional length is just the locomotive`() {
        val train = TrainComposition(locomotiveSeries = "Э5К", conditionalLengthUslVag = 0.0)
        assertEquals(19.3, train.lengthM, 0.001)
    }

    @Test
    fun `unknown locomotive series is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainComposition(locomotiveSeries = "не бывает такой серии", conditionalLengthUslVag = 10.0)
        }
    }

    @Test
    fun `negative conditional length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainComposition(locomotiveSeries = "3ЭС5К", conditionalLengthUslVag = -1.0)
        }
    }
}
