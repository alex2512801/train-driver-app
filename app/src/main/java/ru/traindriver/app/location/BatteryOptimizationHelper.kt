package ru.traindriver.app.location

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Запрос на отключение батарейной оптимизации + подсказка про автозапуск (машинист прислал
 * разбор похожего приложения "Блокнот машиниста" — там этого тоже нет, но попросил всё равно
 * добавить "на будущее", раз уж разбираемся с причинами сбоев GPS в фоне). Автозапуск у
 * Xiaomi/Huawei/Samsung у каждого производителя свой недокументированный экран настроек без
 * единого системного Intent — вместо хрупких попыток открыть именно его показываем диалог
 * с текстовой подсказкой и ведём на общий системный экран отключения батарейной оптимизации
 * (этот Intent одинаковый на всех прошивках).
 *
 * Спрашиваем ОДИН раз за всё время (не при каждом запуске) — см. [PREFS_NAME]/[KEY_ASKED].
 */
object BatteryOptimizationHelper {

    private const val PREFS_NAME = "battery_optimization"
    private const val KEY_ASKED = "asked"

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Показать диалог-подсказку, если ещё не спрашивали и оптимизация ещё не отключена. */
    fun maybeAsk(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        if (isIgnoringBatteryOptimizations(activity)) return

        AlertDialog.Builder(activity)
            .setTitle("Чтобы GPS не отключался в фоне")
            .setMessage(
                "Разрешите приложению работать без ограничений батареи — иначе телефон может " +
                    "приостанавливать GPS, пока экран выключен.\n\n" +
                    "На телефонах Xiaomi, Huawei и Samsung дополнительно включите автозапуск для " +
                    "этого приложения в настройках батареи (обычно: Настройки → Батарея → " +
                    "Автозапуск/Расход батареи приложениями)."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                openBatterySettings(activity)
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
            }
            .setNegativeButton("Понятно") { _, _ ->
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun openBatterySettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: Throwable) {
            // Нет этого экрана настроек на прошивке — не критично, машинист уже увидел
            // текстовую подсказку в диалоге.
        }
    }
}
