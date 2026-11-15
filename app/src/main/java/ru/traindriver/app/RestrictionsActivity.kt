package ru.traindriver.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.traindriver.app.route.ChainageFormatter
import ru.traindriver.app.route.Direction
import ru.traindriver.app.route.RestrictionField
import ru.traindriver.app.route.RestrictionFormFields
import ru.traindriver.app.route.RestrictionInputParser
import ru.traindriver.app.route.RestrictionInputResult
import ru.traindriver.app.route.RestrictionsStore
import ru.traindriver.app.route.sortedForDirection

/**
 * "Ограничения" (ТЗ раздел 11) — отдельный полноэкранный экран, не диалог поверх главного (см.
 * doc-комментарий в activity_restrictions.xml — почему именно отдельная Activity, а не Dialog).
 * Список ограничений общий с MainActivity через [RestrictionsStore] — MainActivity подхватывает
 * изменения в своём onResume().
 */
class RestrictionsActivity : AppCompatActivity() {

    companion object {
        /** Эффективный датасет (DirectionSelection.effectiveDataset) на момент открытия экрана —
         *  только для сортировки списка в том же порядке, что на главном экране (растущие/
         *  убывающие пикеты), см. MainActivity.showRestrictionsDialog. */
        const val EXTRA_EFFECTIVE_DATASET = "effective_dataset"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Доп. защита от системной клавиатуры на уровне окна — помимо
        // setShowSoftInputOnFocus(false) у каждого поля ниже. Раньше (в старом Dialog-варианте)
        // бывало, что новое окно само показывало системную клавиатуру, игнорируя настройку
        // отдельных полей — это глушит её на уровне всего окна, а не по полям.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_restrictions)
        hideSystemUi()

        val effectiveDataset = intent.getStringExtra(EXTRA_EFFECTIVE_DATASET)
            ?.let { Direction.valueOf(it) } ?: Direction.EVEN

        val startKmInput = findViewById<EditText>(R.id.restrictionStartKmInput)
        val startPkInput = findViewById<EditText>(R.id.restrictionStartPkInput)
        val endKmInput = findViewById<EditText>(R.id.restrictionEndKmInput)
        val endPkInput = findViewById<EditText>(R.id.restrictionEndPkInput)
        val speedInput = findViewById<EditText>(R.id.restrictionSpeedInput)
        val pathInput = findViewById<EditText>(R.id.restrictionPathInput)
        val errorText = findViewById<TextView>(R.id.restrictionErrorText)
        val addButton = findViewById<Button>(R.id.restrictionAddButton)
        val rowsContainer = findViewById<LinearLayout>(R.id.restrictionRowsContainer)

        fun refreshRestrictionsList() {
            rowsContainer.removeAllViews()
            for (r in RestrictionsStore.list.sortedForDirection(effectiveDataset)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val info = TextView(this).apply {
                    val endTxt = if (r.endM == r.startM) "—" else ChainageFormatter.formatDot(r.endM)
                    text = "%-8s %-8s %3d  %s".format(
                        ChainageFormatter.formatDot(r.startM), endTxt, r.speedKmh, r.path?.toString() ?: "—"
                    )
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val delete = TextView(this).apply {
                    text = "✕"
                    setTextColor(0xFFE05A5A.toInt())
                    setPadding(16, 0, 0, 0)
                    setOnClickListener {
                        RestrictionsStore.list.remove(r)
                        refreshRestrictionsList()
                    }
                }
                row.addView(info)
                row.addView(delete)
                rowsContainer.addView(row)
            }
        }

        val fieldInputs = mapOf(
            RestrictionField.START_KM to startKmInput,
            RestrictionField.START_PK to startPkInput,
            RestrictionField.END_KM to endKmInput,
            RestrictionField.END_PK to endPkInput,
            RestrictionField.SPEED to speedInput,
            RestrictionField.PATH to pathInput
        )

        // Своя маленькая клавиатура вместо системной (машинист: системная занимала весь экран
        // и не видно было, что вводишь) — ← → переключают "активное" поле строго по порядку
        // км→пк→км→пк→скорость→путь, а не как получится у системного "Далее" (оно искало
        // следующее поле по расположению на экране, а не по порядку). orderedInputs/fieldOrder —
        // один и тот же порядок, по индексу.
        val orderedInputs = listOf(startKmInput, startPkInput, endKmInput, endPkInput, speedInput, pathInput)
        // android:showSoftInputOnFocus не существует как публичный XML-атрибут (AAPT: "attribute
        // not found"), хоть одноимённый метод и есть — выключаем системную клавиатуру кодом.
        for (input in orderedInputs) input.setShowSoftInputOnFocus(false)
        val fieldOrder = listOf(
            RestrictionField.START_KM, RestrictionField.START_PK,
            RestrictionField.END_KM, RestrictionField.END_PK,
            RestrictionField.SPEED, RestrictionField.PATH
        )
        var activeIndex = 0
        var currentErrorFields: Set<RestrictionField> = emptySet()

        fun refreshFieldBackgrounds() {
            for ((i, field) in fieldOrder.withIndex()) {
                orderedInputs[i].setBackgroundColor(
                    when {
                        field in currentErrorFields -> 0x33E05A5A
                        i == activeIndex -> 0x405AAADC
                        else -> 0x00000000
                    }
                )
            }
        }

        // moveCursorToEnd=false — когда поле активируется тапом по нему самому: обычное
        // поведение EditText уже поставило курсор именно туда, куда машинист ткнул пальцем
        // (это работает даже с выключенной системной клавиатурой — отключён только показ
        // самой клавиатуры, а не позиционирование курсора по касанию). Раньше здесь всегда
        // стоял setSelection(text.length), который сразу же сбрасывал курсор в конец текста,
        // отменяя то место, куда машинист только что поставил его пальцем — поэтому "Стереть"
        // всегда стирало с конца, а не ту цифру, рядом с которой стоял курсор.
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

        // Пишет/стирает именно там, где сейчас курсор (или вместо выделенного участка, если
        // он есть) — а не всегда в конец/с конца строки.
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
            input.setSelection(lo + digit.length)
        }

        val digitButtonIds = listOf(
            R.id.restrictionKey0, R.id.restrictionKey1, R.id.restrictionKey2, R.id.restrictionKey3,
            R.id.restrictionKey4, R.id.restrictionKey5, R.id.restrictionKey6, R.id.restrictionKey7,
            R.id.restrictionKey8, R.id.restrictionKey9
        )
        digitButtonIds.forEachIndexed { digit, id ->
            findViewById<Button>(id).setOnClickListener { appendToActive(digit.toString()) }
        }
        findViewById<Button>(R.id.restrictionKeyPrev).setOnClickListener { setActive(activeIndex - 1) }
        findViewById<Button>(R.id.restrictionKeyNext).setOnClickListener { setActive(activeIndex + 1) }
        findViewById<Button>(R.id.restrictionBackspaceButton).setOnClickListener {
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

        addButton.setOnClickListener {
            val fields = RestrictionFormFields(
                startKm = startKmInput.text.toString(),
                startPk = startPkInput.text.toString(),
                endKm = endKmInput.text.toString(),
                endPk = endPkInput.text.toString(),
                speed = speedInput.text.toString(),
                path = pathInput.text.toString()
            )
            when (val result = RestrictionInputParser.parse(fields)) {
                is RestrictionInputResult.Success -> {
                    RestrictionsStore.list.add(result.restriction)
                    for (input in fieldInputs.values) input.text.clear()
                    errorText.visibility = View.GONE
                    currentErrorFields = emptySet()
                    setActive(0)
                    refreshRestrictionsList()
                }
                is RestrictionInputResult.Error -> {
                    errorText.text = result.message
                    errorText.visibility = View.VISIBLE
                    currentErrorFields = result.fields
                    refreshFieldBackgrounds()
                }
            }
        }

        findViewById<Button>(R.id.restrictionCloseButton).setOnClickListener { finish() }

        refreshRestrictionsList()
        setActive(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Та же логика, что в MainActivity.hideSystemUi — экран должен открываться на весь экран,
    // без системной строки состояния, как и везде в этом приложении.
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
