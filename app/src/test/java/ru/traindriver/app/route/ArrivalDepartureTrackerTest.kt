package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDepartureTrackerTest {

    private val minute = 60_000L

    @Test
    fun `starts hidden`() {
        val tracker = ArrivalDepartureTracker()
        assertEquals(ArrivalDepartureState.Hidden, tracker.state)
    }

    @Test
    fun `stopping shows the Stopped state with the arrival time`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 40.0, nowMs = 0L, positionM = 1000.0) // едем, ничего не меняется
        assertEquals(ArrivalDepartureState.Hidden, tracker.state)

        tracker.onUpdate(speedKmh = 0.0, nowMs = 1000L, positionM = 1500.0)
        val state = tracker.state
        assertTrue(state is ArrivalDepartureState.Stopped)
        assertEquals(1000L, (state as ArrivalDepartureState.Stopped).arrivalTime)
        assertEquals(1500.0, state.locationM, 0.001)
    }

    @Test
    fun `departure freezes the duration and records history, TZ examples`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 5000.0) // остановились в t=0

        // Стоим 3 минуты, скорость всё ещё 0 — состояние не должно меняться.
        tracker.onUpdate(speedKmh = 0.0, nowMs = 3 * minute, positionM = 5000.0)
        assertTrue(tracker.state is ArrivalDepartureState.Stopped)

        // Отправляемся (скорость достигает 1 км/ч) в t=3мин.
        tracker.onUpdate(speedKmh = 1.0, nowMs = 3 * minute, positionM = 5000.0)
        val state = tracker.state
        assertTrue(state is ArrivalDepartureState.JustDeparted)
        state as ArrivalDepartureState.JustDeparted
        assertEquals(0L, state.arrivalTime)
        assertEquals(3 * minute, state.departureTime)
        assertEquals(3 * minute, state.stopDurationMs) // "заморожена на итоговой длительности"

        assertEquals(1, tracker.history.size)
        assertEquals(3 * minute, tracker.history[0].durationMs)
    }

    @Test
    fun `window hides itself ~5 minutes after departure if train keeps moving`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 0.0)
        tracker.onUpdate(speedKmh = 40.0, nowMs = 100L, positionM = 0.0) // отправление в t=100

        tracker.onUpdate(speedKmh = 40.0, nowMs = 100L + 4 * minute, positionM = 5000.0)
        assertTrue("окно ещё должно быть видно (меньше 5 минут)", tracker.state is ArrivalDepartureState.JustDeparted)

        tracker.onUpdate(speedKmh = 40.0, nowMs = 100L + 5 * minute, positionM = 6000.0)
        assertEquals(ArrivalDepartureState.Hidden, tracker.state)
    }

    @Test
    fun `stopping again during the 5-minute window shows Stopped immediately, not waiting for hide`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 0.0)
        tracker.onUpdate(speedKmh = 40.0, nowMs = 100L, positionM = 0.0) // отправление

        // Остановились снова через минуту (внутри 5-минутного окна).
        tracker.onUpdate(speedKmh = 0.0, nowMs = 100L + minute, positionM = 900.0)
        val state = tracker.state
        assertTrue(state is ArrivalDepartureState.Stopped)
        assertEquals(100L + minute, (state as ArrivalDepartureState.Stopped).arrivalTime)
    }

    @Test
    fun `25-minute stop alarm fires exactly once`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 0.0)

        assertFalse(tracker.onUpdate(speedKmh = 0.0, nowMs = 24 * minute, positionM = 0.0))
        assertTrue("сигнал должен сработать ровно на 25:00", tracker.onUpdate(speedKmh = 0.0, nowMs = 25 * minute, positionM = 0.0))
        assertFalse("не должен повторяться на каждом следующем апдейте", tracker.onUpdate(speedKmh = 0.0, nowMs = 26 * minute, positionM = 0.0))
    }

    @Test
    fun `alarm can fire again for a later, separate stop`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 0.0)
        assertTrue(tracker.onUpdate(speedKmh = 0.0, nowMs = 25 * minute, positionM = 0.0))

        tracker.onUpdate(speedKmh = 40.0, nowMs = 26 * minute, positionM = 100.0) // уехали
        tracker.onUpdate(speedKmh = 0.0, nowMs = 40 * minute, positionM = 5000.0) // новая стоянка

        assertFalse(tracker.onUpdate(speedKmh = 0.0, nowMs = 64 * minute, positionM = 5000.0))
        assertTrue(tracker.onUpdate(speedKmh = 0.0, nowMs = 65 * minute, positionM = 5000.0))
    }

    @Test
    fun `multiple stops are recorded in order`() {
        val tracker = ArrivalDepartureTracker()
        tracker.onUpdate(speedKmh = 0.0, nowMs = 0L, positionM = 100.0)
        tracker.onUpdate(speedKmh = 40.0, nowMs = 5 * minute, positionM = 100.0)
        tracker.onUpdate(speedKmh = 0.0, nowMs = 10 * minute, positionM = 2000.0)
        tracker.onUpdate(speedKmh = 40.0, nowMs = 12 * minute, positionM = 2000.0)

        assertEquals(2, tracker.history.size)
        assertEquals(100.0, tracker.history[0].locationM, 0.001)
        assertEquals(5 * minute, tracker.history[0].durationMs)
        assertEquals(2000.0, tracker.history[1].locationM, 0.001)
        assertEquals(2 * minute, tracker.history[1].durationMs)
    }
}
