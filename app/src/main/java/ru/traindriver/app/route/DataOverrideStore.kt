package ru.traindriver.app.route

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Обновление данных (режимная карта/скорости/переезды и т.п.) прямо на устройстве — экран
 * "База данных" (DatabaseActivity), открывается из стартового меню (MenuActivity). Машинист:
 * "чтобы я их мог обновить... без тебя" — не прося каждый раз пересобирать всё приложение
 * через Android Studio/GitHub Actions ради одной новой карты.
 *
 * Обновлённый файл сохраняется в приватном хранилище приложения (filesDir) под именем
 * "override_<имя исходного asset-файла>". Все *AssetLoader читают JSON через [readJson] вместо
 * прямого context.assets.open(...) — он сначала смотрит на такой файл обновления, и только если
 * его нет, берёт "заводской" файл из assets (тот самый, что приехал вместе с установленным
 * приложением и сам по себе никогда не меняется).
 */
object DataOverrideStore {

    /**
     * Категории данных, которые можно обновить с устройства — см. DatabaseActivity. Машинист:
     * "нужно чтобы все данные были там, и ничего не было зашито в саму программу кроме
     * голосовых подсказок и пиктограмм" — поэтому здесь ВСЕ категории с собственным JSON-файлом
     * (не только те шесть, что были раньше), голосовые mp3 (res/raw) и картинки пиктограмм
     * сюда не входят — это не JSON-данные, а медиа-ресурсы приложения, их эта схема
     * (DataOverrideStore.applyOverride принимает только JSON) в принципе не касается.
     */
    enum class Category(val label: String, val assetFileName: String) {
        RELIEF("Режимная карта (профиль пути)", "track_relief.json"),
        SPEED_LIMITS("Скорости (постоянные ограничения)", "speed_limits.json"),
        AIR_GAPS("Воздушные промежутки", "air_gaps.json"),
        CROSSINGS("Переезды", "crossings.json"),
        WASHOUTS("Обрывные места", "washouts.json"),
        NEUTRAL_INSERTS("Нейтральные вставки", "neutral_inserts.json"),
        SIGNALS("Сигналы (светофоры)", "track_signals.json"),
        CONDITIONAL_SIGNALS("Условно разрешающие сигналы", "conditional_signals.json"),
        BLOCK_POSTS("Блок-посты", "block_posts.json"),
        STATIONS("Станции", "track_stations.json"),
        STATION_ANNOUNCEMENTS("Оповещения о станциях (голос)", "station_announcements.json"),
        BRAKE_TESTS("Пробы тормозов", "brake_tests.json"),
        KTSM("КТСМ", "ktsm.json"),
        TRAIN_INSPECTION("Осмотр поезда бригадой", "train_inspection.json"),
        ROUTE("Линия маршрута (GPS)", RouteTrack.MAIN_ROUTE_ASSET),
        ROUTE_ANCHORS("Опорные точки калибровки маршрута", RouteTrack.MAIN_ANCHORS_ASSET),
    }

    private fun overrideFile(context: Context, assetFileName: String): File =
        File(context.filesDir, "override_$assetFileName")

    /** JSON-текст для [assetFileName] — обновлённый файл с устройства, если он есть, иначе
     *  заводской файл из assets. Используется во всех *AssetLoader. */
    fun readJson(context: Context, assetFileName: String): String {
        val override = overrideFile(context, assetFileName)
        if (override.exists()) {
            return override.readText(Charsets.UTF_8)
        }
        return context.assets.open(assetFileName).use { it.reader(Charsets.UTF_8).readText() }
    }

    /** true, если для этой категории сейчас используется обновлённый (не заводской) файл. */
    fun hasOverride(context: Context, assetFileName: String): Boolean =
        overrideFile(context, assetFileName).exists()

    /**
     * Сохраняет новый файл как обновление для этой категории. [content] ДОЛЖЕН быть тем же
     * JSON-массивом, что и исходный asset-файл этой категории (та же структура полей) — экран
     * "База данных" не конвертирует форматы сам, файл должен прийти уже в таком виде.
     * JSONArray(content) здесь — проверка формата СРАЗУ, до сохранения: лучше отказать прямо
     * сейчас и объяснить машинисту, чем молча сохранить мусор, который потом уронит загрузчик
     * при следующем запуске всего приложения.
     */
    fun applyOverride(context: Context, assetFileName: String, content: String) {
        // Большинство категорий — JSON-массив, но "Нейтральные вставки" хранятся как объект
        // {"even": [...], "odd": [...]} — принимаем оба вида, лишь бы это был валидный JSON.
        val parsed = JSONTokener(content).nextValue()
        require(parsed is JSONArray || parsed is JSONObject) { "не JSON-массив и не JSON-объект" }
        overrideFile(context, assetFileName).writeText(content, Charsets.UTF_8)
    }

    /** Возвращает категорию к заводским данным — удаляет файл обновления. */
    fun resetOverride(context: Context, assetFileName: String) {
        overrideFile(context, assetFileName).delete()
    }
}
