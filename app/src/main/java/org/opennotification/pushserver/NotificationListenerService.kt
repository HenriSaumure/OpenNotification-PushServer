package org.opennotification.pushserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotificationListenerService : NotificationListenerService() {

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "NotificationListener"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "opennotification_service"
        private const val CHANNEL_NAME = "OpenNotification Service"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        createNotificationChannel()
        startForegroundService()
        LogManager.addLog("Service started as foreground service")
        Log.d(TAG, "NotificationListenerService created")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OpenNotification service running in background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNotification Active")
            .setContentText("Forwarding notifications to server")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(status: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNotification Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RESTART_SERVICE") {
            LogManager.addLog("Service restarted with new settings")
            updateForegroundNotification("Restarted - Forwarding notifications")
        }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        // Skip our own notifications to avoid loops
        if (sbn.packageName == packageName) {
            return
        }

        // Check if we should ignore system notifications
        val ignoreSystem = prefs.getBoolean(MainActivity.PREF_IGNORE_SYSTEM, true)
        if (ignoreSystem && isSystemNotification(sbn.packageName)) {
            LogManager.addLog("Ignored system notification from ${sbn.packageName}")
            return
        }

        val notification = sbn.notification
        if (notification == null) {
            return
        }

        // Extract notification details
        val title = getNotificationTitle(notification)
        val text = getNotificationText(notification)
        val packageName = sbn.packageName

        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            return // Skip empty notifications
        }

        LogManager.addLog("New notification from $packageName: $title")
        updateForegroundNotification("Last: $packageName")

        // Send to API
        sendNotificationToAPI(title, text, packageName, sbn)
    }

    private fun getNotificationTitle(notification: Notification): String? {
        return notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    private fun getNotificationText(notification: Notification): String? {
        return notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    }

    private fun sendNotificationToAPI(
        title: String?,
        description: String?,
        packageName: String,
        sbn: StatusBarNotification
    ) {
        val serverUrl = prefs.getString(MainActivity.PREF_SERVER_URL, MainActivity.DEFAULT_SERVER_URL)
        val guid = prefs.getString(MainActivity.PREF_GUID, "")

        if (serverUrl.isNullOrBlank() || guid.isNullOrBlank()) {
            LogManager.addLog("Error: Server URL or GUID not configured")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get app name from package name
                val appName = getAppNameFromPackage(packageName)

                // Create formatted title with app name
                val formattedTitle = if (title.isNullOrBlank()) {
                    appName
                } else {
                    "$appName - $title"
                }

                val json = JSONObject().apply {
                    put("guid", guid)
                    put("title", formattedTitle)
                    put("description", description ?: "")
                    put("icon", packageName) // Use package name as icon identifier
                    put("isAlert", false)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$serverUrl/notification")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        LogManager.addLog("Failed to send notification: ${e.message}")
                        Log.e(TAG, "API call failed", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            LogManager.addLog("✓ Sent: $formattedTitle")
                        } else {
                            LogManager.addLog("API error ${response.code}: ${response.message}")
                        }
                        response.close()
                    }
                })

            } catch (e: Exception) {
                LogManager.addLog("Exception sending notification: ${e.message}")
                Log.e(TAG, "Exception in sendNotificationToAPI", e)
            }
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            // Fallback to package name if app name can't be retrieved
            packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
        }
    }

    private fun isSystemNotification(packageName: String): Boolean {
        val systemPackages = setOf(
            // Android system
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.calculator2",
            "com.android.calendar",
            "com.android.deskclock",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.cellbroadcastreceiver",
            "com.android.emergency",

            // Google Play Services and Store
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",

            // Samsung system (common OEM)
            "com.samsung.android.dialer",
            "com.samsung.android.contacts",
            "com.samsung.android.app.settings",
            "com.sec.android.app.launcher",
            "com.samsung.android.messaging",

            // Other common system packages
            "com.miui.securitycenter", // Xiaomi
            "com.huawei.systemmanager", // Huawei
            "com.oneplus.security", // OnePlus
            "com.coloros.safecenter", // Oppo/Realme
            "com.bbk.theme", // Vivo

            // Security and device management
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.managedprovisioning"
        )

        return systemPackages.contains(packageName) ||
                packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.") ||
                packageName.startsWith("android.") ||
                packageName.contains("system") ||
                packageName.contains("settings")
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.addLog("Service stopped")
        Log.d(TAG, "NotificationListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.addLog("Notification listener connected")
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.addLog("Notification listener disconnected")
        Log.d(TAG, "Notification listener disconnected")
    }
}
