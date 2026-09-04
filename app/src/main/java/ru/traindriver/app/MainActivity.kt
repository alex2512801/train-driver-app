package ru.traindriver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.traindriver.app.location.GpsLocationProvider
import ru.traindriver.app.route.ChainageFormatter
import ru.traindriver.app.route.RouteTrack

class MainActivity : AppCompatActivity() {

    private lateinit var coordinateText: TextView
    private lateinit var gpsLocationProvider: GpsLocationProvider
    private val routeTrack: RouteTrack by lazy { RouteTrack.placeholderRoute() }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationUpdates()
            } else {
                coordinateText.text = getString(R.string.location_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        coordinateText = findViewById(R.id.coordinateText)
        gpsLocationProvider = GpsLocationProvider(this)

        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onDestroy() {
        gpsLocationProvider.stop()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // проверяется явно в hasLocationPermission()
    private fun startLocationUpdates() {
        if (!gpsLocationProvider.isGpsProviderEnabled) {
            coordinateText.text = getString(R.string.gps_provider_disabled)
        }

        if (!hasLocationPermission()) return

        gpsLocationProvider.start { location ->
            val chainageM = routeTrack.chainageMetersFor(location.latitude, location.longitude)
            coordinateText.text = ChainageFormatter.format(chainageM)
        }
    }
}
