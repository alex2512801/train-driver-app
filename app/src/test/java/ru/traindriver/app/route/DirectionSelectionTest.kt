package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionSelectionTest {

    // Таблица из ТЗ (раздел 3):
    // Чётное + путь 2 -> правильный, чётный датасет
    // Чётное + путь 1 -> неправильный, нечётный датасет (весь, в обратном порядке)
    // Нечётное + путь 1 -> правильный, нечётный датасет
    // Нечётное + путь 2 -> неправильный, чётный датасет

    @Test
    fun `even direction on path 2 is correct and uses the even dataset`() {
        val s = DirectionSelection(Direction.EVEN, 2)
        assertEquals(PathStatus.CORRECT, s.pathStatus)
        assertEquals(Direction.EVEN, s.effectiveDataset)
    }

    @Test
    fun `even direction on path 1 is wrong and uses the odd dataset`() {
        val s = DirectionSelection(Direction.EVEN, 1)
        assertEquals(PathStatus.WRONG, s.pathStatus)
        assertEquals(Direction.ODD, s.effectiveDataset)
    }

    @Test
    fun `odd direction on path 1 is correct and uses the odd dataset`() {
        val s = DirectionSelection(Direction.ODD, 1)
        assertEquals(PathStatus.CORRECT, s.pathStatus)
        assertEquals(Direction.ODD, s.effectiveDataset)
    }

    @Test
    fun `odd direction on path 2 is wrong and uses the even dataset`() {
        val s = DirectionSelection(Direction.ODD, 2)
        assertEquals(PathStatus.WRONG, s.pathStatus)
        assertEquals(Direction.EVEN, s.effectiveDataset)
    }

    // Пикеты растут при чётном и убывают при нечётном — независимо от пути (ТЗ раздел 3).
    @Test
    fun `pickets grow on even direction regardless of path`() {
        assertEquals(true, DirectionSelection(Direction.EVEN, 1).picketsGrowing)
        assertEquals(true, DirectionSelection(Direction.EVEN, 2).picketsGrowing)
    }

    @Test
    fun `pickets shrink on odd direction regardless of path`() {
        assertEquals(false, DirectionSelection(Direction.ODD, 1).picketsGrowing)
        assertEquals(false, DirectionSelection(Direction.ODD, 2).picketsGrowing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only tracks 1 and 2 exist`() {
        DirectionSelection(Direction.EVEN, 3)
    }
}
