package org.opennotification.pushserver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import org.opennotification.pushserver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "OpenNotificationPrefs"
        const val PREF_SERVER_URL = "server_url"
        const val PREF_GUID = "guid"
        const val PREF_IGNORE_SYSTEM = "ignore_system"
        const val DEFAULT_SERVER_URL = "https://api.opennotification.org"
    }

    // Permission launcher for notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            startForegroundServiceIfReady()
        } else {
            Toast.makeText(this, "Notification permission denied - app may not work properly", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupUI()
        loadSettings()
        requestNotificationPermissionIfNeeded()
        updateStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startForegroundServiceIfReady()
            }
        } else {
            startForegroundServiceIfReady()
        }
    }

    private fun startForegroundServiceIfReady() {
        if (isNotificationServiceEnabled()) {
            val serviceIntent = Intent(this, NotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun setupUI() {
        binding.textViewLog.movementMethod = ScrollingMovementMethod()

        binding.buttonSaveSettings.setOnClickListener {
            saveSettings()
        }

        binding.buttonEnableNotifications.setOnClickListener {
            openNotificationSettings()
        }
    }

    private fun loadSettings() {
        val serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL)
        val guid = prefs.getString(PREF_GUID, "")
        val ignoreSystem = prefs.getBoolean(PREF_IGNORE_SYSTEM, true)

        binding.editTextServerUrl.setText(serverUrl)
        binding.editTextGuid.setText(guid)
        binding.switchIgnoreSystem.isChecked = ignoreSystem
    }

    private fun saveSettings() {
        val serverUrl = binding.editTextServerUrl.text.toString().trim()
        val guid = binding.editTextGuid.text.toString().trim()
        val ignoreSystem = binding.switchIgnoreSystem.isChecked

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Server URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (guid.isEmpty()) {
            Toast.makeText(this, "GUID cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit {
            putString(PREF_SERVER_URL, serverUrl)
            putString(PREF_GUID, guid)
            putBoolean(PREF_IGNORE_SYSTEM, ignoreSystem)
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        // Restart the notification service to pick up new settings
        if (isNotificationServiceEnabled()) {
            val intent = Intent(this, NotificationListenerService::class.java).apply {
                action = "RESTART_SERVICE"
            }
            startService(intent)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")

        if (flat != null) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val componentName = ComponentName.unflattenFromString(name)
                if (componentName != null) {
                    if (packageName == componentName.packageName) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun updateStatus() {
        val isEnabled = isNotificationServiceEnabled()
        val hasSettings = prefs.getString(PREF_GUID, "")?.isNotEmpty() == true

        val status = when {
            !isEnabled -> "Status: Notification access not enabled"
            !hasSettings -> "Status: Settings not configured"
            else -> "Status: Ready to forward notifications"
        }

        binding.textViewStatus.text = status
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateLogFromService()
    }

    private fun updateLogFromService() {
        val logContent = LogManager.getLogContent()
        if (logContent.isNotEmpty()) {
            binding.textViewLog.text = logContent
            // Scroll to bottom
            binding.textViewLog.post {
                val scrollView = binding.textViewLog.parent as? android.widget.ScrollView
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
}
