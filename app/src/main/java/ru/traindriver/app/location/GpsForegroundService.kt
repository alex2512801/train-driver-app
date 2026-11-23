package ru.traindriver.app.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ru.traindriver.app.R

/**
 * Foreground-сервис с подпиской на GPS (машинист прислал разбор похожего приложения "Блокнот
 * машиниста", у которого почти не бывает сбоев GPS, и попросил взять оттуда лучшее: раньше
 * подписка на LocationManager жила прямо в MainActivity — обычная Activity, без foreground-
 * сервиса и постоянного уведомления, Android (особенно Xiaomi/Huawei/Samsung) может
 * приостановить/убить такой процесс в фоне или когда гаснет экран, и GPS перестаёт обновляться
 * не из-за помех, а потому что систему в этот момент даже не спрашивают. Сам [GpsLocationProvider]
 * (провайдер, резерв по сети, обработка отсутствующего провайдера) не меняется — просто теперь
 * живёт здесь, а не в Activity.
 *
 * Сервис и Activity — один процесс (отдельный android:process в манифесте не объявлен),
 * поэтому координаты передаются не через Binder/Messenger/broadcast, а напрямую через
 * статический колбэк [listener] — MainActivity подставляет его СТРОГО до запуска сервиса.
 */
class GpsForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gps_tracking"
        private const val NOTIFICATION_ID = 1

        /** MainActivity.startLocationUpdates подставляет сюда свою обработку координаты
         *  (та же, что раньше шла прямо в колбэк GpsLocationProvider.start) ДО вызова
         *  ContextCompat.startForegroundService — если сервис стартует без подписанного
         *  слушателя, он просто получает координаты в никуда. */
        var listener: ((Location, LocationSource) -> Unit)? = null
    }

    private lateinit var gpsLocationProvider: GpsLocationProvider
    private var wakeLock: PowerManager.WakeLock? = null
    private var satelliteCount = 0
    private var gnssCallback: GnssStatus.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Разрешение ACCESS_FINE_LOCATION к этому моменту уже проверено и получено в MainActivity
    // (startLocationUpdates вызывает startForegroundService только после hasLocationPermission()) —
    // сам сервис попросить разрешение не может, это задача экрана, который его запускает.
    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        // PARTIAL_WAKE_LOCK — держит включённым только CPU (не экран), ровно для того, чтобы
        // обработка GPS-колбэков продолжалась, пока экран выключен (foreground-сервис сам по
        // себе не гарантирует, что CPU не уйдёт в сон между редкими колбэками).
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ru.traindriver.app:gps")
            .apply { acquire() }

        gpsLocationProvider = GpsLocationProvider(this)
        gpsLocationProvider.start { location, source -> listener?.invoke(location, source) }

        registerGnssCallback()
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssCallback() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usedInFix = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedInFix++
                }
                satelliteCount = usedInFix
                updateNotification()
            }
        }
        gnssCallback = callback
        try {
            locationManager.registerGnssStatusCallback(callback, null)
        } catch (e: Throwable) {
            // Нет поддержки GNSS-статуса на этой прошивке/устройстве — не критично, просто
            // уведомление останется без числа спутников (см. buildNotification).
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        gnssCallback?.let {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).unregisterGnssStatusCallback(it)
        }
        if (::gpsLocationProvider.isInitialized) gpsLocationProvider.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Слежение GPS",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Поехали")
            .setContentText(if (satelliteCount > 0) "Спутников: $satelliteCount" else "Поиск спутников…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
