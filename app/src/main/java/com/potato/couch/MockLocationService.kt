package com.potato.couch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class MockLocationService : Service() {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var locationManager: LocationManager? = null
    private var hasReportedError = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setRunningFlag(true)
        startInForeground()
        startMockLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMockLoop()
        removeTestProvider()
        setRunningFlag(false)
        broadcastStatus(getString(R.string.status_idle), "")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "mock_location"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mock Location",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Mocking location is running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
        broadcastStatus(getString(R.string.status_running), "")
    }

    private fun startMockLoop() {
        if (handlerThread != null) return

        ensureTestProvider()

        handlerThread = HandlerThread("mock-location-thread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        handler?.post(object : Runnable {
            override fun run() {
                pushMockLocation(25.0330, 121.5654) // Placeholder: Taipei 101
                handler?.postDelayed(this, 1000L)
            }
        })
    }

    private fun stopMockLoop() {
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    @Suppress("DEPRECATION")
    private fun ensureTestProvider() {
        val provider = LocationManager.GPS_PROVIDER
        val lm = locationManager ?: return
        try {
            lm.addTestProvider(
                provider,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                0,
                5
            )
        } catch (_: IllegalArgumentException) {
            // Provider already exists.
        }

        try {
            lm.setTestProviderEnabled(provider, true)
        } catch (_: SecurityException) {
            // If not set as mock location app, this will fail.
            reportMockAppError()
        }
    }

    @Suppress("DEPRECATION")
    private fun removeTestProvider() {
        val lm = locationManager ?: return
        try {
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
    }

    private fun pushMockLocation(latitude: Double, longitude: Double) {
        val lm = locationManager ?: return
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        } catch (_: SecurityException) {
            // Not authorized as mock location app.
            reportMockAppError()
        }
    }

    private fun reportMockAppError() {
        if (hasReportedError) return
        hasReportedError = true
        broadcastStatus(
            getString(R.string.status_error),
            getString(R.string.error_not_mock_app)
        )
    }

    private fun broadcastStatus(status: String, message: String) {
        val intent = Intent(ACTION_MOCK_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun setRunningFlag(isRunning: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_RUNNING, isRunning).apply()
    }

    companion object {
        const val ACTION_MOCK_STATUS = "com.potato.couch.MOCK_STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_MESSAGE = "extra_message"
        private const val PREFS_NAME = "mock_prefs"
        private const val PREF_KEY_RUNNING = "mock_service_running"
    }
}
