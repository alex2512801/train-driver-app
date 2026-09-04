package ru.traindriver.app.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import android.Manifest

/**
 * Тонкая обёртка над стандартным LocationManager (без Google Play Services —
 * приложение должно работать полностью автономно, без интернета, см. ТЗ раздел 2).
 */
class GpsLocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null

    val isGpsProviderEnabled: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start(minTimeMs: Long = 1000L, minDistanceM: Float = 1f, onLocation: (Location) -> Unit) {
        stop()
        val newListener = LocationListener { location -> onLocation(location) }
        listener = newListener
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            minTimeMs,
            minDistanceM,
            newListener,
            Looper.getMainLooper()
        )
    }

    fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }
}
