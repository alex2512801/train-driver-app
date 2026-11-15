package ru.traindriver.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.traindriver.app.route.LocomotiveTable
import ru.traindriver.app.route.TrainComposition
import ru.traindriver.app.route.TrainParams
import ru.traindriver.app.route.TrainParamsStore

/**
 * "Параметры" (ТЗ раздел 5 / раздел 12.2) — отдельный полноэкранный экран, не диалог поверх
 * главного (см. doc-комментарий в activity_train_params.xml — та же причина, что и у
 * "Ограничений"). MainActivity подхватывает сохранённые параметры в своём onResume().
 */
class TrainParamsActivity : AppCompatActivity() {

    private val trainParamsStore by lazy { TrainParamsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // См. doc-комментарий в RestrictionsActivity.onCreate — та же подстраховка от
        // системной клавиатуры на уровне всего окна.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_train_params)
        hideSystemUi()

        val seriesSpinner = findViewById<Spinner>(R.id.locoSeriesSpinner)
        val weightAutoText = findViewById<TextView>(R.id.weightAutoText)
        val lengthAutoText = findViewById<TextView>(R.id.lengthAutoText)
        val bruttoInput = findViewById<EditText>(R.id.bruttoInput)
        val nettoInput = findViewById<EditText>(R.id.nettoInput)
        val wagonCountInput = findViewById<EditText>(R.id.wagonCountInput)
        val tareAutoText = findViewById<TextView>(R.id.tareAutoText)
        val condLengthInput = findViewById<EditText>(R.id.condLengthInput)
        val composedLengthAutoText = findViewById<TextView>(R.id.composedLengthAutoText)

        val current = trainParamsStore.load()

        val seriesNames = LocomotiveTable.SERIES.keys.toList()
        seriesSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, seriesNames)
        seriesSpinner.setSelection(seriesNames.indexOf(current.locomotiveSeries).coerceAtLeast(0))
        bruttoInput.setText(formatNumber(current.bruttoT))
        nettoInput.setText(formatNumber(current.nettoT))
        wagonCountInput.setText(current.wagonCount.toString())
        condLengthInput.setText(formatNumber(current.conditionalLengthUslVag))

        fun refreshAuto() {
            val series = seriesNames.getOrNull(seriesSpinner.selectedItemPosition) ?: current.locomotiveSeries
            val spec = LocomotiveTable.SERIES.getValue(series)
            weightAutoText.text = "Вес (авто): ${formatNumber(spec.weightT)} т"
            lengthAutoText.text = "Длина (авто): ${formatNumber(spec.lengthM)} м"

            val brutto = bruttoInput.text.toString().toDoubleOrNull() ?: 0.0
            val netto = nettoInput.text.toString().toDoubleOrNull() ?: 0.0
            val count = wagonCountInput.text.toString().toIntOrNull() ?: 0
            tareAutoText.text = if (count > 0) {
                "Тара/вагон (авто): ${"%.1f".format((brutto - netto) / count)} т"
            } else {
                "Тара/вагон (авто): —"
            }

            val condLength = condLengthInput.text.toString().toDoubleOrNull() ?: 0.0
            val totalLength = condLength * TrainComposition.CONDITIONAL_WAGON_LENGTH_M + spec.lengthM
            composedLengthAutoText.text = "Полная длина (авто): ${"%.1f".format(totalLength)} м"
        }

        // Поля ввода — своя цифровая клавиатура справа, та же логика курсора/выделения, что и
        // в "Ограничениях" (RestrictionsActivity) — пишет/стирает именно там, где курсор, а не
        // всегда в конец/с конца строки.
        val orderedInputs = listOf(bruttoInput, nettoInput, wagonCountInput, condLengthInput)
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
                input.setSelection(input.text.length)
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
            // coerceAtMost — ОБЯЗАТЕЛЬНО: если поле уже на пределе maxLength и курсор у самого
            // конца, системный фильтр длины молча НЕ вставляет цифру (текст не меняется), а
            // setSelection(lo + digit.length) без этой защиты попытался бы поставить курсор
            // ЗА пределами настоящей (не увеличившейся) длины текста — Android бросает
            // исключение, которое крашит всю Activity (машинист: "вылетает на главный экран",
            // а несохранённое вводимое пропадает, т.к. это настоящий краш, а не закрытие).
            input.setSelection((lo + digit.length).coerceAtMost(input.text.length))
        }

        val digitButtonIds = listOf(
            R.id.tpKey0, R.id.tpKey1, R.id.tpKey2, R.id.tpKey3, R.id.tpKey4,
            R.id.tpKey5, R.id.tpKey6, R.id.tpKey7, R.id.tpKey8, R.id.tpKey9
        )
        digitButtonIds.forEachIndexed { digit, id ->
            findViewById<Button>(id).setOnClickListener { appendToActive(digit.toString()) }
        }
        // ↑/↓, а не ←/→ (машинист: поля здесь стоят друг под другом, а не в ряд).
        findViewById<Button>(R.id.tpKeyUp).setOnClickListener { setActive(activeIndex - 1) }
        findViewById<Button>(R.id.tpKeyDown).setOnClickListener { setActive(activeIndex + 1) }
        findViewById<Button>(R.id.tpBackspaceButton).setOnClickListener {
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

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshAuto()
        }
        for (input in orderedInputs) input.addTextChangedListener(watcher)
        seriesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshAuto()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshAuto()
        setActive(0)

        findViewById<Button>(R.id.tpSaveButton).setOnClickListener {
            val newParams = TrainParams(
                locomotiveSeries = seriesNames.getOrNull(seriesSpinner.selectedItemPosition)
                    ?: current.locomotiveSeries,
                bruttoT = bruttoInput.text.toString().toDoubleOrNull() ?: current.bruttoT,
                nettoT = nettoInput.text.toString().toDoubleOrNull() ?: current.nettoT,
                wagonCount = wagonCountInput.text.toString().toIntOrNull() ?: current.wagonCount,
                conditionalLengthUslVag = (condLengthInput.text.toString().toDoubleOrNull()
                    ?: current.conditionalLengthUslVag).coerceAtLeast(0.0)
            )
            trainParamsStore.save(newParams)
            finish()
        }

        findViewById<Button>(R.id.tpCloseButton).setOnClickListener { finish() }
    }

    /** "4200" вместо "4200.0" для целых значений — как в HTML-макете (та же, что и в MainActivity). */
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
