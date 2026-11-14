package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Test

/** Правило подписи блок-поста, продиктованное машинистом (см. чат/README): дополнительный
 *  блок-пост (между двумя существующими сигналами) — "Ч Б/п"/"Н Б/п"; блок-пост НА МЕСТЕ
 *  существующего сигнала — "Ч (N) Б/п"/"Н (N) Б/п", где N — номер заменённого сигнала. */
class BlockPostTest {

    @Test
    fun `additional block post has no replaced signal in the label`() {
        val even = BlockPost(Direction.EVEN, 100.0)
        assertEquals("Ч Б/п", even.label)

        val odd = BlockPost(Direction.ODD, 100.0)
        assertEquals("Н Б/п", odd.label)
    }

    @Test
    fun `block post replacing an existing signal names it in parentheses`() {
        val even = BlockPost(Direction.EVEN, 100.0, replacesSignal = "6")
        assertEquals("Ч (6) Б/п", even.label)

        val odd = BlockPost(Direction.ODD, 100.0, replacesSignal = "5")
        assertEquals("Н (5) Б/п", odd.label)
    }
}
