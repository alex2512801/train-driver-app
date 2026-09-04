package ru.traindriver.app.route

import kotlin.math.floor

/**
 * Перевод пикетажа (расстояния от начала маршрута в метрах) в формат "км+пк",
 * как в КЛУБ-У (ТЗ, раздел 7):
 *
 *   км = floor(километровый_отрезок) + 1
 *   пк = floor(метры_внутри_км / 100) + 1
 *
 * Примеры из ТЗ: 0000.050 -> 1км 1пк; 6234.670 -> 6235км 7пк.
 */
object ChainageFormatter {

    fun format(totalMeters: Double): String {
        val clamped = totalMeters.coerceAtLeast(0.0)
        val kmIndex = floor(clamped / 1000.0)
        val metersInKm = clamped - kmIndex * 1000.0

        val km = kmIndex.toInt() + 1
        val pk = floor(metersInKm / 100.0).toInt() + 1

        return "${km}км ${pk}пк"
    }
}
