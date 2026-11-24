package ru.traindriver.app

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ru.traindriver.app.route.DataOverrideStore

/**
 * "База данных" (ТЗ раздел 12, пункт стартового меню, открывается из MenuActivity) — машинист
 * сам обновляет часть данных прямо с устройства, без участия Claude и без пересборки всего
 * приложения (см. doc-комментарий в DataOverrideStore.kt и activity_database.xml — файл должен
 * прийти уже в том же формате, что и заводской файл этой категории, экран сам не конвертирует).
 */
class DatabaseActivity : AppCompatActivity() {

    private data class Row(
        val category: DataOverrideStore.Category,
        val statusText: TextView,
        val updateButton: Button,
        val resetButton: Button
    )

    private lateinit var rows: List<Row>

    // Пока ждём результат от системного выбора файла (между нажатием "Обновить" и тем, что
    // машинист реально что-то выбрал) — нужно помнить, к какой именно категории относится этот
    // выбор, когда придёт результат (один общий launcher на все пять категорий, см. ниже).
    private var pendingCategory: DataOverrideStore.Category? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val category = pendingCategory
        pendingCategory = null
        if (uri != null && category != null) applyPickedFile(category, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_database)
        hideSystemUi()

        rows = listOf(
            Row(
                DataOverrideStore.Category.RELIEF,
                findViewById(R.id.reliefStatusText),
                findViewById(R.id.reliefUpdateButton),
                findViewById(R.id.reliefResetButton)
            ),
            Row(
                DataOverrideStore.Category.SPEED_LIMITS,
                findViewById(R.id.speedStatusText),
                findViewById(R.id.speedUpdateButton),
                findViewById(R.id.speedResetButton)
            ),
            Row(
                DataOverrideStore.Category.AIR_GAPS,
                findViewById(R.id.airGapStatusText),
                findViewById(R.id.airGapUpdateButton),
                findViewById(R.id.airGapResetButton)
            ),
            Row(
                DataOverrideStore.Category.CROSSINGS,
                findViewById(R.id.crossingStatusText),
                findViewById(R.id.crossingUpdateButton),
                findViewById(R.id.crossingResetButton)
            ),
            Row(
                DataOverrideStore.Category.WASHOUTS,
                findViewById(R.id.washoutStatusText),
                findViewById(R.id.washoutUpdateButton),
                findViewById(R.id.washoutResetButton)
            ),
            Row(
                DataOverrideStore.Category.NEUTRAL_INSERTS,
                findViewById(R.id.neutralStatusText),
                findViewById(R.id.neutralUpdateButton),
                findViewById(R.id.neutralResetButton)
            ),
            Row(
                DataOverrideStore.Category.SIGNALS,
                findViewById(R.id.trackSignalsStatusText),
                findViewById(R.id.trackSignalsUpdateButton),
                findViewById(R.id.trackSignalsResetButton)
            ),
            Row(
                DataOverrideStore.Category.CONDITIONAL_SIGNALS,
                findViewById(R.id.conditionalSignalsStatusText),
                findViewById(R.id.conditionalSignalsUpdateButton),
                findViewById(R.id.conditionalSignalsResetButton)
            ),
            Row(
                DataOverrideStore.Category.BLOCK_POSTS,
                findViewById(R.id.blockPostsStatusText),
                findViewById(R.id.blockPostsUpdateButton),
                findViewById(R.id.blockPostsResetButton)
            ),
            Row(
                DataOverrideStore.Category.STATIONS,
                findViewById(R.id.stationsStatusText),
                findViewById(R.id.stationsUpdateButton),
                findViewById(R.id.stationsResetButton)
            ),
            Row(
                DataOverrideStore.Category.STATION_ANNOUNCEMENTS,
                findViewById(R.id.stationAnnouncementsStatusText),
                findViewById(R.id.stationAnnouncementsUpdateButton),
                findViewById(R.id.stationAnnouncementsResetButton)
            ),
            Row(
                DataOverrideStore.Category.BRAKE_TESTS,
                findViewById(R.id.brakeTestsStatusText),
                findViewById(R.id.brakeTestsUpdateButton),
                findViewById(R.id.brakeTestsResetButton)
            ),
            Row(
                DataOverrideStore.Category.KTSM,
                findViewById(R.id.ktsmStatusText),
                findViewById(R.id.ktsmUpdateButton),
                findViewById(R.id.ktsmResetButton)
            ),
            Row(
                DataOverrideStore.Category.TRAIN_INSPECTION,
                findViewById(R.id.trainInspectionStatusText),
                findViewById(R.id.trainInspectionUpdateButton),
                findViewById(R.id.trainInspectionResetButton)
            ),
            Row(
                DataOverrideStore.Category.ROUTE,
                findViewById(R.id.routeStatusText),
                findViewById(R.id.routeUpdateButton),
                findViewById(R.id.routeResetButton)
            )
        )

        for (row in rows) {
            row.updateButton.setOnClickListener {
                pendingCategory = row.category
                pickFileLauncher.launch("*/*")
            }
            row.resetButton.setOnClickListener {
                DataOverrideStore.resetOverride(this, row.category.assetFileName)
                Toast.makeText(this, "${row.category.label}: вернули заводские данные", Toast.LENGTH_SHORT).show()
                refreshStatuses()
            }
        }
        refreshStatuses()

        findViewById<Button>(R.id.databaseCloseButton).setOnClickListener { finish() }
    }

    private fun applyPickedFile(category: DataOverrideStore.Category, uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "${category.label}: не получилось прочитать файл", Toast.LENGTH_LONG).show()
            return
        }
        try {
            DataOverrideStore.applyOverride(this, category.assetFileName, text)
            Toast.makeText(this, "${category.label}: обновлено", Toast.LENGTH_SHORT).show()
            refreshStatuses()
        } catch (e: Exception) {
            // Чаще всего — файл не в том формате (не JSON-массив нужной структуры). Отказываем
            // сразу, а не сохраняем — иначе эта же ошибка всплыла бы при следующем запуске
            // главного экрана, когда её уже не видно и не очевидно, откуда она взялась.
            Toast.makeText(
                this,
                "${category.label}: файл не подходит по формату, данные не изменены",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshStatuses() {
        for (row in rows) {
            val updated = DataOverrideStore.hasOverride(this, row.category.assetFileName)
            row.statusText.text = if (updated) "обновлено" else "заводские"
            row.statusText.setTextColor(Color.parseColor(if (updated) "#4CAF50" else "#888888"))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }
}
