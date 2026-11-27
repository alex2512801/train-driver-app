package ru.traindriver.app.route

import android.content.Context

/**
 * Настраиваемые пороги голосовых оповещений (ТЗ раздел 6) + дневной/ночной режим — экран
 * "Настройки" (SettingsActivity), машинист: "для каждого голосового оповещения... кроме
 * сигнала с желтым показанием... то, что сейчас есть у нас, они стоят, эти расстояния, по
 * умолчанию. Но можно их изменить... и если закрываешь программу, чтобы эти настройки
 * оставались". Жёлтый сигнал сюда не входит — там переменная фраза, см. doc-комментарий у
 * Announcement в VoiceAnnouncer.kt, сам порог для него пока даже не существует.
 *
 * Значения по умолчанию — те же, что были захардкожены в MainActivity (PointAnnouncementScheduler
 * у каждого оповещения) до появления этого экрана.
 */
data class VoiceSettings(
    val dayMode: Boolean,
    val crossingFarM: Double,
    val crossingNear800M: Double,
    val crossingNear300M: Double,
    val ktsmM: Double,
    val stationM: Double,
    val tempLimitM: Double,
    val washoutM: Double,
    val brakeTestM: Double,
    val neutralM: Double,
    val airGapM: Double
) {
    companion object {
        val DEFAULT = VoiceSettings(
            dayMode = false,
            crossingFarM = 1500.0,
            crossingNear800M = 800.0,
            crossingNear300M = 300.0,
            ktsmM = 2000.0,
            stationM = 1500.0,
            tempLimitM = 2000.0,
            washoutM = 2000.0,
            brakeTestM = 2000.0,
            neutralM = 600.0,
            airGapM = 1000.0
        )
    }
}

/** Хранит [VoiceSettings] между запусками приложения — то же простое SharedPreferences, что и
 *  у [TrainParamsStore]. */
class VoiceSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): VoiceSettings = VoiceSettings(
        dayMode = prefs.getBoolean(KEY_DAY_MODE, VoiceSettings.DEFAULT.dayMode),
        crossingFarM = prefs.getFloat(KEY_CROSSING_FAR, VoiceSettings.DEFAULT.crossingFarM.toFloat()).toDouble(),
        crossingNear800M = prefs.getFloat(KEY_CROSSING_NEAR_800, VoiceSettings.DEFAULT.crossingNear800M.toFloat()).toDouble(),
        crossingNear300M = prefs.getFloat(KEY_CROSSING_NEAR_300, VoiceSettings.DEFAULT.crossingNear300M.toFloat()).toDouble(),
        ktsmM = prefs.getFloat(KEY_KTSM, VoiceSettings.DEFAULT.ktsmM.toFloat()).toDouble(),
        stationM = prefs.getFloat(KEY_STATION, VoiceSettings.DEFAULT.stationM.toFloat()).toDouble(),
        tempLimitM = prefs.getFloat(KEY_TEMP_LIMIT, VoiceSettings.DEFAULT.tempLimitM.toFloat()).toDouble(),
        washoutM = prefs.getFloat(KEY_WASHOUT, VoiceSettings.DEFAULT.washoutM.toFloat()).toDouble(),
        brakeTestM = prefs.getFloat(KEY_BRAKE_TEST, VoiceSettings.DEFAULT.brakeTestM.toFloat()).toDouble(),
        neutralM = prefs.getFloat(KEY_NEUTRAL, VoiceSettings.DEFAULT.neutralM.toFloat()).toDouble(),
        airGapM = prefs.getFloat(KEY_AIR_GAP, VoiceSettings.DEFAULT.airGapM.toFloat()).toDouble()
    )

    fun save(settings: VoiceSettings) {
        prefs.edit()
            .putBoolean(KEY_DAY_MODE, settings.dayMode)
            .putFloat(KEY_CROSSING_FAR, settings.crossingFarM.toFloat())
            .putFloat(KEY_CROSSING_NEAR_800, settings.crossingNear800M.toFloat())
            .putFloat(KEY_CROSSING_NEAR_300, settings.crossingNear300M.toFloat())
            .putFloat(KEY_KTSM, settings.ktsmM.toFloat())
            .putFloat(KEY_STATION, settings.stationM.toFloat())
            .putFloat(KEY_TEMP_LIMIT, settings.tempLimitM.toFloat())
            .putFloat(KEY_WASHOUT, settings.washoutM.toFloat())
            .putFloat(KEY_BRAKE_TEST, settings.brakeTestM.toFloat())
            .putFloat(KEY_NEUTRAL, settings.neutralM.toFloat())
            .putFloat(KEY_AIR_GAP, settings.airGapM.toFloat())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_settings"
        private const val KEY_DAY_MODE = "day_mode"
        private const val KEY_CROSSING_FAR = "crossing_far_m"
        private const val KEY_CROSSING_NEAR_800 = "crossing_near_800_m"
        private const val KEY_CROSSING_NEAR_300 = "crossing_near_300_m"
        private const val KEY_KTSM = "ktsm_m"
        private const val KEY_STATION = "station_m"
        private const val KEY_TEMP_LIMIT = "temp_limit_m"
        private const val KEY_WASHOUT = "washout_m"
        private const val KEY_BRAKE_TEST = "brake_test_m"
        private const val KEY_NEUTRAL = "neutral_m"
        private const val KEY_AIR_GAP = "air_gap_m"
    }
}
