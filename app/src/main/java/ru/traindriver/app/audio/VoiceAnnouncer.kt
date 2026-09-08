package ru.traindriver.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import ru.traindriver.app.R

/**
 * Голосовые оповещения (женский голос, ТЗ раздел 6) — короткие mp3, записанные машинистом,
 * лежат в res/raw/announce_*. Через [SoundPool], а не MediaPlayer — это отдельные короткие
 * (1.5-4 сек) реплики, которые должны звучать без задержки в момент пересечения порога
 * расстояния (см. [PointAnnouncementScheduler] в route), а не одно длинное аудио.
 *
 * Что именно играть и когда — снаружи (см. MainActivity): этот класс только грузит клипы и
 * умеет проиграть любой из них по запросу.
 */
class VoiceAnnouncer(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val soundIds: Map<Announcement, Int> =
        Announcement.entries.associateWith { announcement -> soundPool.load(context, announcement.rawResId, 1) }

    fun play(announcement: Announcement) {
        val id = soundIds[announcement] ?: return
        soundPool.play(id, 1f, 1f, /* priority = */ 1, /* loop = */ 0, /* rate = */ 1f)
    }

    fun release() = soundPool.release()
}

/**
 * Все голосовые оповещения ТЗ раздел 6, кроме: моста (машинист решил мосты не обозначать
 * вообще нигде в приложении) и условного жёлтого сигнала (там переменная фраза — литера
 * сигнала + скорость, сотни сочетаний, целиком не записать; это отдельная задача на будущее,
 * см. README).
 */
enum class Announcement(val rawResId: Int) {
    /** «Внимание! Впереди переезд!» — 1500 м. */
    CROSSING_FAR(R.raw.announce_crossing_far),

    /** «Впереди переезд!» — 800 м и 300 м (тот же клип на обоих порогах). */
    CROSSING_NEAR(R.raw.announce_crossing_near),

    /** «Впереди КТСМ!» — 1500 м. */
    KTSM(R.raw.announce_ktsm),

    /** «Внимание! Впереди станция!» (предвходной) — 1500 м. */
    STATION(R.raw.announce_station),

    /** «Внимание! Впереди временное ограничение скорости!» — 2000 м. */
    TEMP_LIMIT(R.raw.announce_temp_limit),

    /** «Внимание! Впереди обрывное место!» — 2000 м. */
    WASHOUT(R.raw.announce_washout),

    /** «Впереди проба тормозов!» — 2000 м. */
    BRAKE_TEST(R.raw.announce_brake_test),

    /** «Впереди проба тормозов сдвоенного поезда!» — 2000 м. */
    BRAKE_TEST_DOUBLE(R.raw.announce_brake_test_double),
}
