package ru.traindriver.app.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import android.Manifest

/** Откуда пришла координата — вызывающий код сам решает, что можно показывать/использовать
 *  при [NETWORK] (см. doc-комментарий [GpsLocationProvider]), а что нет. */
enum class LocationSource { GPS, NETWORK }

/**
 * Тонкая обёртка над стандартным LocationManager (без Google Play Services —
 * приложение должно работать полностью автономно, без интернета, см. ТЗ раздел 2).
 *
 * Резервный источник — NETWORK_PROVIDER (по сотовым вышкам/Wi-Fi, без интернета тоже работает
 * частично, хуже) — машинист попросил: GPS иногда пропадает (возможно, глушат в отдельных
 * местах — тут программно ничего не сделать, это особенность приёма сигнала, а не телефона
 * или программы), а совсем без координаты хуже, чем с грубой. NETWORK_PROVIDER НАМНОГО менее
 * точный (сотни метров — километр, а не единицы метров, как у GPS) — поэтому он размечен
 * отдельным [LocationSource.NETWORK], а не просто подмешан в тот же поток: вызывающий код
 * решает, что при нём безопасно показывать (см. MainActivity.startLocationUpdates — координата
 * показывается с явной пометкой неточности, а положение на графике/объявления/спидометр по
 * нему НЕ обновляются, чтобы не ввести машиниста в заблуждение грубой оценкой).
 */
class GpsLocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null

    val isGpsProviderEnabled: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start(minTimeMs: Long = 1000L, minDistanceM: Float = 1f, onLocation: (Location, LocationSource) -> Unit) {
        stop()

        // ВАЖНО: тут НЕ проверяем isProviderEnabled(GPS_PROVIDER) заранее (машинист поправил —
        // GPS на его планшете реальный и рабочий, просто мог быть в моьмент запуска выключен
        // в настройках геолокации). requestLocationUpdates можно вызывать и пока провайдер
        // выключен — Android сам пришлёт обновления, как только геолокацию включат, подписка
        // просто ждёт. Падает (IllegalArgumentException) только если такого провайдера у
        // устройства вообще НЕТ в системе — именно этот, более редкий случай ловим здесь, чтобы
        // не крашить всё приложение прямо при запуске.
        try {
            val gps = LocationListener { location -> onLocation(location, LocationSource.GPS) }
            gpsListener = gps
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceM,
                gps,
                Looper.getMainLooper()
            )
        } catch (e: Throwable) {
            // Throwable, не Exception — реальный Logcat машиниста (другой планшет, см.
            // MainActivity.pollSimSignal) показал, что телефонные/системные API на отдельных
            // прошивках бросают и NoSuchMethodError (Error, не Exception) — ловим на всякий
            // случай и здесь той же защитой. Такого провайдера у устройства нет вообще —
            // работаем дальше без GPS (останется резервный NETWORK_PROVIDER, если он есть).
        }

        // Не у всех устройств/прошивок NETWORK_PROVIDER вообще включён или доступен — это
        // необязательный резерв, поэтому молча пропускаем, если его нет или он откажет.
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            try {
                val network = LocationListener { location -> onLocation(location, LocationSource.NETWORK) }
                networkListener = network
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    network,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                // ACCESS_FINE_LOCATION (проверяется до вызова start) обычно покрывает и
                // NETWORK_PROVIDER, но на некоторых прошивках бывает иначе — не критично.
            }
        }
    }

    fun stop() {
        gpsListener?.let { locationManager.removeUpdates(it) }
        networkListener?.let { locationManager.removeUpdates(it) }
        gpsListener = null
        networkListener = null
    }
}
