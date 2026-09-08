package ru.traindriver.app.route

/**
 * Окошко прибытия/отправления (ТЗ раздел 10). Три состояния:
 *  - [Hidden] — едем, и не в первые ~5 минут после отправления;
 *  - [Stopped] — поезд стоит: верхняя строка = момент остановки, средняя строка живая
 *    (считает секунды) — сам живой отсчёт делает UI по текущему времени минус [arrivalTime],
 *    здесь только зафиксированный момент начала;
 *  - [JustDeparted] — только отправились: нижняя строка = момент отправления, средняя
 *    заморожена на [stopDurationMs] (итоговая длительность только что закончившейся стоянки).
 *
 * Окно НЕ скрывается сразу по отправлению — держится ~5 минут (ТЗ: "окно держится ~5 минут и
 * исчезает"), а если за эти 5 минут поезд успел остановиться снова — тут же переключается в
 * [Stopped], не дожидаясь скрытия (ТЗ: "появляется снова при остановке").
 */
sealed class ArrivalDepartureState {
    data object Hidden : ArrivalDepartureState()

    data class Stopped(val arrivalTime: Long, val locationM: Double) : ArrivalDepartureState()

    data class JustDeparted(
        val arrivalTime: Long,
        val departureTime: Long,
        val stopDurationMs: Long
    ) : ArrivalDepartureState()
}

/** Одна строка истории остановок (ТЗ раздел 10, панель "История остановок"). */
data class StopRecord(
    val arrivalTime: Long,
    val departureTime: Long,
    val locationM: Double
) {
    val durationMs: Long get() = departureTime - arrivalTime
}

/**
 * [departureSpeedKmh] — порог "поезд поехал" (ТЗ прямо задаёт 1 км/ч). Тот же порог используется
 * и для обнаружения ОСТАНОВКИ (скорость упала ниже него) — другого числа в ТЗ нет, отдельного
 * порога на остановку не описано.
 * [justDepartedWindowMs] — сколько окно держится после отправления (ТЗ: "~5 минут").
 * [stopAlarmMs] — порог звукового сигнала во время стоянки (ТЗ: "на 25 минутах").
 */
class ArrivalDepartureTracker(
    private val departureSpeedKmh: Double = 1.0,
    private val justDepartedWindowMs: Long = 5 * 60 * 1000L,
    private val stopAlarmMs: Long = 25 * 60 * 1000L
) {
    var state: ArrivalDepartureState = ArrivalDepartureState.Hidden
        private set

    private val _history = mutableListOf<StopRecord>()
    val history: List<StopRecord> get() = _history

    private var alarmFiredForCurrentStop = false

    /**
     * Скармливать при каждом обновлении GPS (скорость + позиция) и по таймеру, пока едем
     * (чтобы вовремя скрыть окно через 5 минут, даже если скорость не менялась). Возвращает
     * true ровно в тот момент, когда нужно подать 25-минутный звуковой сигнал (один раз за
     * стоянку — см. [alarmFiredForCurrentStop]).
     */
    fun onUpdate(speedKmh: Double, nowMs: Long, positionM: Double): Boolean {
        val moving = speedKmh >= departureSpeedKmh
        var alarm = false

        when (val s = state) {
            is ArrivalDepartureState.Hidden -> {
                if (!moving) {
                    state = ArrivalDepartureState.Stopped(nowMs, positionM)
                    alarmFiredForCurrentStop = false
                }
            }

            is ArrivalDepartureState.Stopped -> {
                if (moving) {
                    val duration = nowMs - s.arrivalTime
                    _history.add(StopRecord(s.arrivalTime, nowMs, s.locationM))
                    state = ArrivalDepartureState.JustDeparted(s.arrivalTime, nowMs, duration)
                } else if (!alarmFiredForCurrentStop && nowMs - s.arrivalTime >= stopAlarmMs) {
                    alarm = true
                    alarmFiredForCurrentStop = true
                }
            }

            is ArrivalDepartureState.JustDeparted -> {
                state = when {
                    !moving -> {
                        alarmFiredForCurrentStop = false
                        ArrivalDepartureState.Stopped(nowMs, positionM)
                    }
                    nowMs - s.departureTime >= justDepartedWindowMs -> ArrivalDepartureState.Hidden
                    else -> s
                }
            }
        }

        return alarm
    }
}
