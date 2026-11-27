package ru.traindriver.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Меню из главного экрана — открывается кнопкой "Меню" в нижней статусной строке MainActivity
 * (раньше там была прямая кнопка "Параметры", см. doc-комментарий в activity_app_menu.xml).
 * "Параметры" остаётся как было, "База данных" переехала внутрь нового пункта "Настройки"
 * (машинист: "кнопка «Параметры» также остается, как по-старому, и кнопка «Настройки»... база
 * данных... чтобы она теперь была в настройках в самом низу") — см. SettingsActivity.
 */
class AppMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_menu)
        hideSystemUi()

        findViewById<Button>(R.id.appMenuParamsButton).setOnClickListener {
            startActivity(Intent(this, TrainParamsActivity::class.java))
        }
        findViewById<Button>(R.id.appMenuSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.appMenuCloseButton).setOnClickListener { finish() }
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
