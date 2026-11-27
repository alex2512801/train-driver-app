package ru.traindriver.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import ru.traindriver.app.route.VoiceSettings
import ru.traindriver.app.route.VoiceSettingsStore

/**
 * "Настройки" (см. doc-комментарий в activity_settings.xml) — отдельный полноэкранный экран,
 * та же причина/решение, что у "Параметров"/"Ограничений" (без системной клавиатуры, свой
 * цифровой номеронабор справа — см. TrainParamsActivity, откуда скопирована логика курсора/
 * перехода между полями).
 */
class SettingsActivity : AppCompatActivity() {

    private val settingsStore by lazy { VoiceSettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_settings)
        hideSystemUi()

        val dayNightButton = findViewById<Button>(R.id.settingsDayNightButton)
        val crossingFarInput = findViewById<EditText>(R.id.crossingFarInput)
        val crossingNear800Input = findViewById<EditText>(R.id.crossingNear800Input)
        val crossingNear300Input = findViewById<EditText>(R.id.crossingNear300Input)
        val ktsmInput = findViewById<EditText>(R.id.ktsmInput)
        val stationInput = findViewById<EditText>(R.id.stationInput)
        val tempLimitInput = findViewById<EditText>(R.id.tempLimitInput)
        val washoutInput = findViewById<EditText>(R.id.washoutInput)
        val brakeTestInput = findViewById<EditText>(R.id.brakeTestInput)
        val neutralInput = findViewById<EditText>(R.id.neutralInput)
        val airGapInput = findViewById<EditText>(R.id.airGapInput)

        val current = settingsStore.load()
        var dayMode = current.dayMode
        dayNightButton.text = if (dayMode) "☀️" else "🌙"
        dayNightButton.setOnClickListener {
            dayMode = !dayMode
            dayNightButton.text = if (dayMode) "☀️" else "🌙"
        }

        crossingFarInput.setText(formatNumber(current.crossingFarM))
        crossingNear800Input.setText(formatNumber(current.crossingNear800M))
        crossingNear300Input.setText(formatNumber(current.crossingNear300M))
        ktsmInput.setText(formatNumber(current.ktsmM))
        stationInput.setText(formatNumber(current.stationM))
        tempLimitInput.setText(formatNumber(current.tempLimitM))
        washoutInput.setText(formatNumber(current.washoutM))
        brakeTestInput.setText(formatNumber(current.brakeTestM))
        neutralInput.setText(formatNumber(current.neutralM))
        airGapInput.setText(formatNumber(current.airGapM))

        // Та же логика ввода/курсора/перехода между полями, что в TrainParamsActivity — см.
        // doc-комментарий там же про post{}/coerceAtMost.
        val orderedInputs = listOf(
            crossingFarInput, crossingNear800Input, crossingNear300Input, ktsmInput, stationInput,
            tempLimitInput, washoutInput, brakeTestInput, neutralInput, airGapInput
        )
        for (input in orderedInputs) input.setShowSoftInputOnFocus(false)
        var activeIndex = 0

        fun refreshFieldBackgrounds() {
            orderedInputs.forEachIndexed { i, input ->
                input.setBackgroundColor(if (i == activeIndex) 0x405AAADC else 0x00000000)
            }
        }

        fun setActive(index: Int, moveCursorToEnd: Boolean = true) {
            activeIndex = index.coerceIn(0, orderedInputs.lastIndex)
            val input = orderedInputs[activeIndex]
            input.requestFocus()
            if (moveCursorToEnd) {
                input.post { input.setSelection(input.text.length) }
            }
            refreshFieldBackgrounds()
        }

        orderedInputs.forEachIndexed { index, input ->
            input.setOnClickListener { setActive(index, moveCursorToEnd = false) }
        }

        fun selectionRange(input: EditText): Pair<Int, Int> {
            val len = input.text.length
            val start = input.selectionStart.coerceIn(0, len)
            val end = input.selectionEnd.coerceIn(0, len)
            return minOf(start, end) to maxOf(start, end)
        }

        fun appendToActive(digit: String) {
            val input = orderedInputs[activeIndex]
            val (lo, hi) = selectionRange(input)
            input.text.replace(lo, hi, digit)
            input.setSelection((lo + digit.length).coerceAtMost(input.text.length))
        }

        val digitButtonIds = listOf(
            R.id.setKey0, R.id.setKey1, R.id.setKey2, R.id.setKey3, R.id.setKey4,
            R.id.setKey5, R.id.setKey6, R.id.setKey7, R.id.setKey8, R.id.setKey9
        )
        digitButtonIds.forEachIndexed { digit, id ->
            findViewById<Button>(id).setOnClickListener { appendToActive(digit.toString()) }
        }
        findViewById<Button>(R.id.setKeyUp).setOnClickListener { setActive(activeIndex - 1) }
        findViewById<Button>(R.id.setKeyDown).setOnClickListener { setActive(activeIndex + 1) }
        findViewById<Button>(R.id.setBackspaceButton).setOnClickListener {
            val input = orderedInputs[activeIndex]
            val (lo, hi) = selectionRange(input)
            if (hi > lo) {
                input.text.delete(lo, hi)
                input.setSelection(lo)
            } else if (lo > 0) {
                input.text.delete(lo - 1, lo)
                input.setSelection(lo - 1)
            }
        }
        findViewById<Button>(R.id.setClearButton).setOnClickListener {
            orderedInputs[activeIndex].text.clear()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        }
        for (input in orderedInputs) input.addTextChangedListener(watcher)
        setActive(0)

        findViewById<Button>(R.id.settingsDatabaseButton).setOnClickListener {
            startActivity(Intent(this, DatabaseActivity::class.java))
        }

        findViewById<Button>(R.id.setSaveButton).setOnClickListener {
            val newSettings = VoiceSettings(
                dayMode = dayMode,
                crossingFarM = numberOrDefault(crossingFarInput, current.crossingFarM),
                crossingNear800M = numberOrDefault(crossingNear800Input, current.crossingNear800M),
                crossingNear300M = numberOrDefault(crossingNear300Input, current.crossingNear300M),
                ktsmM = numberOrDefault(ktsmInput, current.ktsmM),
                stationM = numberOrDefault(stationInput, current.stationM),
                tempLimitM = numberOrDefault(tempLimitInput, current.tempLimitM),
                washoutM = numberOrDefault(washoutInput, current.washoutM),
                brakeTestM = numberOrDefault(brakeTestInput, current.brakeTestM),
                neutralM = numberOrDefault(neutralInput, current.neutralM),
                airGapM = numberOrDefault(airGapInput, current.airGapM)
            )
            settingsStore.save(newSettings)
            finish()
        }

        findViewById<Button>(R.id.setCloseButton).setOnClickListener { finish() }
    }

    private fun numberOrDefault(input: EditText, default: Double): Double =
        input.text.toString().toDoubleOrNull() ?: default

    /** "1500" вместо "1500.0" — как в TrainParamsActivity. */
    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

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
