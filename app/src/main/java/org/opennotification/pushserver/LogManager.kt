package org.opennotification.pushserver

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object LogManager {
    private val logs = ConcurrentLinkedQueue<String>()
    private val maxLogs = 100

    fun addLog(message: String) {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"

        logs.offer(logEntry)

        // Keep only the last maxLogs entries
        while (logs.size > maxLogs) {
            logs.poll()
        }
    }

    fun getLogContent(): String {
        return logs.joinToString("\n")
    }

    fun clearLogs() {
        logs.clear()
    }
}
