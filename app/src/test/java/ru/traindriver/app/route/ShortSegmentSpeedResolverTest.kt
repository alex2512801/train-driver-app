package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortSegmentSpeedResolverTest {

    private fun limit(startM: Double, endM: Double, speedKmh: Int) =
        SpeedLimit(Direction.EVEN, startM, endM, speedKmh, sourceText = "test")

    @Test
    fun `TZ example 1 - 60 to 70(short) to 50 gives effective 60, the max, not the smaller`() {
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 1000.0, 60),
                limit(1000.0, 1300.0, 70), // 300 м — короче состава
                limit(1300.0, 2000.0, 50),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 1150.0, trainLengthM = 600.0)
        assertEquals(70, result?.naturalSpeedKmh)
        assertEquals(60, result?.effectiveSpeedKmh)
        assertTrue(result!!.isShortSegment)
    }

    @Test
    fun `TZ example 2 - 50 to 80(short) to 65 gives effective 65, the NEXT segment, not previous`() {
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 1000.0, 50),
                limit(1000.0, 1300.0, 80), // 300 м — короче состава
                limit(1300.0, 2000.0, 65),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 1150.0, trainLengthM = 600.0)
        assertEquals(80, result?.naturalSpeedKmh)
        assertEquals(65, result?.effectiveSpeedKmh)
        assertTrue(result!!.isShortSegment)
    }

    @Test
    fun `segment at least as long as the train is not capped, even if it is a faster spike`() {
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 1000.0, 60),
                limit(1000.0, 1700.0, 70), // 700 м — не короче состава
                limit(1700.0, 2000.0, 50),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 1350.0, trainLengthM = 600.0)
        assertEquals(70, result?.naturalSpeedKmh)
        assertEquals(70, result?.effectiveSpeedKmh)
        assertFalse(result!!.isShortSegment)
    }

    @Test
    fun `short segment that is NOT a spike (lower than a neighbour) is not capped`() {
        // короткий, но не "выше обоих соседей" — сам по себе более медленный участок это уже
        // обычное, не связанное с разделом 9, ограничение.
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 1000.0, 60),
                limit(1000.0, 1300.0, 40), // короче состава, но НИЖЕ предыдущего
                limit(1300.0, 2000.0, 70),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 1150.0, trainLengthM = 600.0)
        assertEquals(40, result?.naturalSpeedKmh)
        assertEquals(40, result?.effectiveSpeedKmh)
        assertFalse(result!!.isShortSegment)
    }

    @Test
    fun `short spike at the very start of the data has no previous neighbour, so it is not capped`() {
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 300.0, 70),
                limit(300.0, 2000.0, 50),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 150.0, trainLengthM = 600.0)
        assertEquals(70, result?.naturalSpeedKmh)
        assertEquals(70, result?.effectiveSpeedKmh)
        assertFalse(result!!.isShortSegment)
    }

    @Test
    fun `a gap between segments breaks the sandwich - not capped even if short and higher`() {
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(0.0, 1000.0, 60),
                limit(1000.0, 1300.0, 70), // короткий, выше обоих соседей...
                // ...но между ним и следующим диапазоном разрыв (1300..1400 без данных)
                limit(1400.0, 2000.0, 50),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 1150.0, trainLengthM = 600.0)
        assertEquals(70, result?.naturalSpeedKmh)
        assertEquals(70, result?.effectiveSpeedKmh)
        assertFalse(result!!.isShortSegment)
    }

    @Test
    fun `narrow exception inside a broad range is resolved before segmenting, like SpeedLimitResolver`() {
        // Тот же приём, что и в SpeedLimitResolverTest: узкий диапазон внутри широкого.
        val broad = limit(0.0, 3000.0, 60)
        val narrowSpike = limit(1000.0, 1300.0, 80) // короткое "окно" быстрее внутри общего 60
        val resolver = ShortSegmentSpeedResolver(listOf(broad, narrowSpike))

        val inSpike = resolver.effectiveSpeedAt(Direction.EVEN, 1150.0, trainLengthM = 600.0)
        assertEquals(80, inSpike?.naturalSpeedKmh)
        assertEquals(60, inSpike?.effectiveSpeedKmh)
        assertTrue(inSpike!!.isShortSegment)

        val beforeSpike = resolver.effectiveSpeedAt(Direction.EVEN, 500.0, trainLengthM = 600.0)
        assertEquals(60, beforeSpike?.effectiveSpeedKmh)
        assertFalse(beforeSpike!!.isShortSegment)
    }

    @Test
    fun `real Khilok data has a genuine 100m spike shorter than a real train`() {
        // Настоящие числа из app assets/speed_limits.json (чётное): 6105900-6106000 (100 м) —
        // 80 км/ч зажато между 60 и 60 (см. README раздел 9). Длина состава — не число физических
        // вагонов, а условная длина (усл. вагоны × 14 м + локомотив, см. TrainComposition) —
        // дефолт из макета (3ЭС5К, 58 усл.ваг.) даёт 864.5 м, с большим запасом длиннее 100 м.
        val train = TrainComposition(locomotiveSeries = "3ЭС5К", conditionalLengthUslVag = 58.0)
        val resolver = ShortSegmentSpeedResolver(
            listOf(
                limit(6_095_500.0, 6_105_900.0, 60),
                limit(6_105_900.0, 6_106_000.0, 80),
                limit(6_106_000.0, 6_120_700.0, 60),
            )
        )
        val result = resolver.effectiveSpeedAt(Direction.EVEN, 6_105_950.0, trainLengthM = train.lengthM)
        assertEquals(80, result?.naturalSpeedKmh)
        assertEquals(60, result?.effectiveSpeedKmh)
        assertTrue(result!!.isShortSegment)
    }
}
