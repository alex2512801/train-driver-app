package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointAnnouncementSchedulerTest {

    @Test
    fun `fires once when crossing into the threshold, growing direction`() {
        val scheduler = PointAnnouncementScheduler(listOf(1000.0), { it }, thresholdM = 2000.0)

        assertNull(scheduler.update(positionM = -5000.0, growing = true)) // 6000 м впереди, ещё рано
        assertEquals(1000.0, scheduler.update(positionM = -1000.0, growing = true)) // 2000 м впереди — порог
        assertNull(scheduler.update(positionM = -500.0, growing = true)) // всё ещё внутри зоны, не повторяем
        assertNull(scheduler.update(positionM = 1500.0, growing = true)) // уже проехали
    }

    @Test
    fun `works for the odd (decreasing chainage) direction too`() {
        val scheduler = PointAnnouncementScheduler(listOf(5000.0), { it }, thresholdM = 2000.0)

        assertNull(scheduler.update(positionM = 8000.0, growing = false)) // ещё далеко
        assertEquals(5000.0, scheduler.update(positionM = 7000.0, growing = false)) // ровно порог
    }

    @Test
    fun `does not announce a point already behind when the app starts nearby`() {
        // Машинист запустил приложение, когда поезд уже проехал точку (или она вообще позади) —
        // не объявляем задним числом.
        val scheduler = PointAnnouncementScheduler(listOf(1000.0), { it }, thresholdM = 2000.0)
        assertNull(scheduler.update(positionM = 1500.0, growing = true))
        // и не должна сработать повторно, даже если проехать по кругу назад и снова вперёд
        assertNull(scheduler.update(positionM = -1000.0, growing = true))
    }

    @Test
    fun `each point fires independently and only once`() {
        val scheduler = PointAnnouncementScheduler(listOf(1000.0, 5000.0), { it }, thresholdM = 2000.0)

        assertEquals(1000.0, scheduler.update(positionM = 0.0, growing = true))
        assertNull(scheduler.update(positionM = 0.0, growing = true)) // первая точка уже объявлена
        assertEquals(5000.0, scheduler.update(positionM = 3500.0, growing = true))
        assertNull(scheduler.update(positionM = 3500.0, growing = true))
    }

    @Test
    fun `reset allows points to fire again`() {
        val scheduler = PointAnnouncementScheduler(listOf(1000.0), { it }, thresholdM = 2000.0)
        assertEquals(1000.0, scheduler.update(positionM = 0.0, growing = true))
        assertNull(scheduler.update(positionM = 0.0, growing = true))

        scheduler.reset()
        assertEquals(1000.0, scheduler.update(positionM = 0.0, growing = true))
    }
}
