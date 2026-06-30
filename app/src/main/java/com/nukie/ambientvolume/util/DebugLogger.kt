package com.nukie.ambientvolume.util

import android.content.Context
import com.nukie.ambientvolume.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val LOG_FILE_NAME = "debug_engine.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5 MB

    fun log(context: Context, message: String) {
        if (!BuildConfig.DEBUG) return

        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            // Check size and rotate if needed (simple wipe for now as per requirement)
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                logFile.delete()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logLine = "[$timestamp] $message\n"
            
            FileOutputStream(logFile, true).use { 
                it.write(logLine.toByteArray()) 
            }
        } catch (e: Exception) {
            // Silently fail to avoid crashing the engine
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun clearLogs(context: Context) {
        val logFile = File(context.filesDir, LOG_FILE_NAME)
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    fun checkAndPurgeOnUpdate(context: Context, currentVersionCode: Int) {
        val prefs = context.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
        val lastLoggedVersion = prefs.getInt("last_logged_version", -1)
        
        if (currentVersionCode > lastLoggedVersion) {
            clearLogs(context)
            prefs.edit().putInt("last_logged_version", currentVersionCode).apply()
        }
    }
}
