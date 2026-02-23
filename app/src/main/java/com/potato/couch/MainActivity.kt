package com.potato.couch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.potato.couch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mockStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MockLocationService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE).orEmpty()
            binding.textStatus.text = status
            binding.textError.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonDevOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        }

        binding.buttonLocationSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }

        binding.buttonStart.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.buttonStop.setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            binding.textStatus.setText(R.string.status_idle)
            binding.textError.text = ""
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            mockStatusReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_STATUS)
        )
        syncStatusFromPrefs()
        if (!isLocationEnabled()) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_location_disabled)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(mockStatusReceiver)
    }

    private fun ensurePermissionsAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }

        if (needsNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        startMockService()
    }

    private fun startMockService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MockLocationService::class.java)
        )
        binding.textStatus.setText(R.string.status_running)
        binding.textError.text = ""
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensurePermissionsAndStart()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_no_permission)
            }
        }

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMockService()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_notifications)
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun syncStatusFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_RUNNING, false)
        binding.textStatus.setText(if (isRunning) R.string.status_running else R.string.status_idle)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
        private const val PREFS_NAME = "mock_prefs"
        private const val PREF_KEY_RUNNING = "mock_service_running"
    }
}
