package ru.traindriver.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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
class VoiceAnnouncer(private val context: Context) {

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

    // Составные фразы (проба тормозов "со скорости A Б километров в час", временное ограничение
    // "N километров в час") — несколько клипов подряд, БЕЗ наложения. SoundPool.play() играет
    // только один клип за раз и не умеет сказать "начни этот клип ровно когда закончился
    // предыдущий" — для очереди нужен MediaPlayer с [MediaPlayer.setOnCompletionListener],
    // который и запускает следующий клип строго по завершении, а не по таймеру/оценке длины.
    private var sequencePlayer: MediaPlayer? = null
    private val sequenceQueue = ArrayDeque<Int>()

    private fun playSequence(resIds: List<Int>) {
        if (resIds.isEmpty()) return
        stopSequence()
        sequenceQueue.addAll(resIds)
        playNextInSequence()
    }

    private fun playNextInSequence() {
        val resId = sequenceQueue.removeFirstOrNull() ?: return
        sequencePlayer = MediaPlayer.create(context, resId)?.apply {
            setOnCompletionListener {
                it.release()
                playNextInSequence()
            }
            start()
        }
    }

    private fun stopSequence() {
        sequencePlayer?.setOnCompletionListener(null)
        sequencePlayer?.release()
        sequencePlayer = null
        sequenceQueue.clear()
    }

    /** Клип с числом (announce_number_N в res/raw) — null, если для этого N клип не записан
     *  (машинист диктует числа по мере необходимости, не сразу все сто). См. вызывающий код
     *  (playWithSpeed/playWithSpeedRanges) — диапазон/число без клипа просто пропускается. */
    private fun numberResId(n: Int): Int? {
        val id = context.resources.getIdentifier("announce_number_$n", "raw", context.packageName)
        return if (id != 0) id else null
    }

    /** "[announcement] со скорости A Б километров в час" — по одной фразе на каждый диапазон
     *  (машинист: "впереди проба тормозов со скорости 45-50 км/ч", см. doc-комментарий у
     *  BrakeTest.validSpeedRangesKmh), подряд без паузы, если диапазонов несколько. Диапазон,
     *  для которого хотя бы одно число не записано, целиком пропускается — лучше без одного
     *  диапазона, чем оборванная на середине фраза. */
    fun playWithSpeedRanges(announcement: Announcement, ranges: List<IntRange>) {
        val resIds = mutableListOf(announcement.rawResId)
        for (range in ranges) {
            val fromId = numberResId(range.first) ?: continue
            val toId = numberResId(range.last) ?: continue
            resIds += R.raw.announce_from_speed
            resIds += fromId
            resIds += toId
            resIds += R.raw.announce_km_per_hour
        }
        playSequence(resIds)
    }

    /** "[announcement] N километров в час" (временное ограничение — машинист: "впереди
     *  временное ограничение скорости 40 километров в час"). Клипа для N нет — проговаривает
     *  [announcement] без числа, а не молчит совсем. */
    fun playWithSpeed(announcement: Announcement, speedKmh: Int) {
        val numberId = numberResId(speedKmh)
        val resIds = if (numberId != null) {
            listOf(announcement.rawResId, numberId, R.raw.announce_km_per_hour)
        } else {
            listOf(announcement.rawResId)
        }
        playSequence(resIds)
    }

    fun release() {
        soundPool.release()
        stopSequence()
    }
}

/**
 * Все голосовые оповещения ТЗ раздел 6, кроме моста (машинист решил мосты не обозначать вообще
 * нигде в приложении).
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

    /** «Впереди нейтральная вставка!» — 600 м. */
    NEUTRAL(R.raw.announce_neutral),

    /** «Впереди воздушный промежуток!» — 1000 м. */
    AIR_GAP(R.raw.announce_air_gap),

    /** «Осмотр поезда машинистом» — ровно в точке начала осмотра (не заранее, см.
     *  doc-комментарий у TrainInspectionScheduler в MainActivity). */
    TRAIN_INSPECTION_MACHINIST(R.raw.announce_train_inspection_machinist),

    /** «Осмотр поезда помощником машиниста» — ровно в точке начала осмотра. */
    TRAIN_INSPECTION_ASSISTANT(R.raw.announce_train_inspection_assistant),

    /** «Скорость проследования сигнала с жёлтым показанием не более» + число + "километров в
     *  час" (составная фраза, см. [playWithSpeed]) — звучит СРАЗУ по входу в зону ограничения
     *  (машинист: "озвучивать сразу как только зашли на участок... то есть проследовали
     *  предыдущий"), не заранее и не перед самим сигналом с жёлтым показанием (машинист
     *  объяснил всю логику целиком, см. doc-комментарий у
     *  [ru.traindriver.app.route.YellowSignalWarning] и tools/match_yellow_signals.py). Только
     *  часть исходной таблицы (54/157 строк на момент написания) резолвится в км+пк — остальные
     *  пока не озвучиваются, см. там же.*/
    YELLOW_SIGNAL(R.raw.announce_yellow_signal_speed_prefix),
}

// announce_km_per_hour/announce_from_speed/announce_number_N — больше не "заготовки на будущее",
// используются прямо сейчас составными фразами playWithSpeed/playWithSpeedRanges (проба
// тормозов, временное ограничение, сигнал с жёлтым показанием), см. их вызовы в MainActivity.
//
// Остаётся пока НЕ подключено (лежит в res/raw):
// - announce_density_check/_winter (замер плотности, обычный и зимний) — нет данных о местах,
//   где это делается (ни одного файла с км+пк для этого не присылали).
