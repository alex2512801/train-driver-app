package ru.traindriver.app.route

import android.content.Context

/**
 * Всё, что машинист вводит на экране «Параметры» (ТЗ раздел 5 / раздел 12.2): серия и номер
 * локомотива, брутто/нетто/число вагонов (для тары — справочно), условная длина состава.
 * Вес/длина локомотива сюда не входят — они не вводятся, а берутся из [LocomotiveTable] по
 * [locomotiveSeries] (см. [composition]).
 */
data class TrainParams(
    val locomotiveSeries: String,
    val locoNumber: String,
    val bruttoT: Double,
    val nettoT: Double,
    val wagonCount: Int,
    val conditionalLengthUslVag: Double
) {
    /** Состав для расчёта физической длины (см. TrainComposition.lengthM). */
    val composition: TrainComposition get() = TrainComposition(locomotiveSeries, conditionalLengthUslVag)

    /** Тара на вагон (авто, ТЗ раздел 5) — справочное значение, в расчёт длины не входит. */
    val tarePerWagonT: Double? get() = if (wagonCount > 0) (bruttoT - nettoT) / wagonCount else null
}

/**
 * Хранит [TrainParams] между запусками приложения — простое SharedPreferences, поскольку
 * отдельного полноценного экрана настроек (ТЗ раздел 12.2) в реальном приложении пока нет,
 * только диалог поверх главного экрана (см. MainActivity.showTrainParamsDialog).
 */
class TrainParamsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TrainParams = TrainParams(
        locomotiveSeries = prefs.getString(KEY_SERIES, DEFAULT.locomotiveSeries) ?: DEFAULT.locomotiveSeries,
        locoNumber = prefs.getString(KEY_NUMBER, DEFAULT.locoNumber) ?: DEFAULT.locoNumber,
        bruttoT = prefs.getFloat(KEY_BRUTTO, DEFAULT.bruttoT.toFloat()).toDouble(),
        nettoT = prefs.getFloat(KEY_NETTO, DEFAULT.nettoT.toFloat()).toDouble(),
        wagonCount = prefs.getInt(KEY_WAGON_COUNT, DEFAULT.wagonCount),
        conditionalLengthUslVag = prefs.getFloat(KEY_COND_LENGTH, DEFAULT.conditionalLengthUslVag.toFloat()).toDouble()
    )

    fun save(params: TrainParams) {
        prefs.edit()
            .putString(KEY_SERIES, params.locomotiveSeries)
            .putString(KEY_NUMBER, params.locoNumber)
            .putFloat(KEY_BRUTTO, params.bruttoT.toFloat())
            .putFloat(KEY_NETTO, params.nettoT.toFloat())
            .putInt(KEY_WAGON_COUNT, params.wagonCount)
            .putFloat(KEY_COND_LENGTH, params.conditionalLengthUslVag.toFloat())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "train_params"
        private const val KEY_SERIES = "locomotive_series"
        private const val KEY_NUMBER = "loco_number"
        private const val KEY_BRUTTO = "brutto_t"
        private const val KEY_NETTO = "netto_t"
        private const val KEY_WAGON_COUNT = "wagon_count"
        private const val KEY_COND_LENGTH = "conditional_length_usl_vag"

        // Те же значения по умолчанию, что были захардкожены в MainActivity и что стоят в
        // HTML-макете (Параметры, ТЗ раздел 5) — только для первого запуска, пока машинист
        // ничего не ввёл сам.
        val DEFAULT = TrainParams(
            locomotiveSeries = "3ЭС5К",
            locoNumber = "0187",
            bruttoT = 4200.0,
            nettoT = 3100.0,
            wagonCount = 40,
            conditionalLengthUslVag = 58.0
        )
    }
}
