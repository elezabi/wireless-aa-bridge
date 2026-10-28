package com.ezabi.wirelessaabridge.logging

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized logging system with timestamps, in-memory buffer, and file export.
 * All log entries are timestamped with millisecond precision.
 */
class LogManager private constructor(context: Context) {

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val maxEntries = 5000
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val contextRef = java.lang.ref.WeakReference(context)
    private val isExporting = AtomicBoolean(false)

    /**
     * Crash-proof session log: every entry is appended and flushed immediately.
     * Memory-only logging loses the evidence exactly when it matters (app killed on
     * screen-off in a car, process crash, USB re-plug), so the file is the record of
     * truth and the in-memory queue is only for the on-screen viewer.
     */
    private val sessionFile: File? by lazy {
        try {
            val ctx = contextRef.get()
            val dir = ctx?.getExternalFilesDir("logs") ?: ctx?.filesDir
            if (dir == null) null else {
                dir.mkdirs()
                File(dir, "session_${fileDateFormat.format(Date())}.log")
            }
        } catch (e: Exception) {
            null
        }
    }
    private val fileLock = Any()

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        override fun toString(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val exc = if (throwable != null) "\n    ${throwable!!.toString()}" else ""
            return "[$time] [$level] [$tag] $message$exc"
        }
    }

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    companion object {
        @Volatile private var INSTANCE: LogManager? = null
        fun getInstance(context: Context): LogManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message, throwable)
        logQueue.add(entry)
        while (logQueue.size > maxEntries) {
            logQueue.poll()
        }

        // Mirror to logcat so `adb logcat -s WAAB:*` works even if the app dies
        val priority = when (level) {
            Level.VERBOSE -> android.util.Log.VERBOSE
            Level.DEBUG -> android.util.Log.DEBUG
            Level.INFO -> android.util.Log.INFO
            Level.WARN -> android.util.Log.WARN
            Level.ERROR -> android.util.Log.ERROR
        }
        android.util.Log.println(priority, "WAAB/$tag", message + (throwable?.let { "\n$it" } ?: ""))

        // Append-and-flush: survive a kill at any moment
        val f = sessionFile ?: return
        try {
            synchronized(fileLock) {
                f.appendText(entry.toString() + "\n")
            }
        } catch (e: Exception) {
            // Never let logging take down the bridge
        }
    }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun w(tag: String, msg: String, throwable: Throwable) = log(Level.WARN, tag, msg, throwable)
    fun e(tag: String, msg: String, throwable: Throwable? = null) = log(Level.ERROR, tag, msg, throwable)

    fun getLogs(): List<LogEntry> = logQueue.toList()

    fun clear() = logQueue.clear()

    fun exportToFile(): File? {
        val context = contextRef.get() ?: return null
        if (isExporting.getAndSet(true)) return null

        return try {
            val dir = getExportDirectory(context)
            val filename = "wireless_aa_bridge_logs_${fileDateFormat.format(Date())}.txt"
            val file = File(dir, filename)

            PrintWriter(FileWriter(file)).use { writer ->
                writer.println("Wireless AA Bridge - Log Export")
                writer.println("Generated: ${dateFormat.format(Date())}")
                writer.println("Total entries: ${logQueue.size}")
                writer.println("============================================")
                for (entry in logQueue) {
                    writer.println(entry.toString())
                }
            }
            log(Level.INFO, "LogManager", "Logs exported to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            log(Level.ERROR, "LogManager", "Export failed: ${e.message}", e)
            null
        } finally {
            isExporting.set(false)
        }
    }

    private fun getExportDirectory(context: Context): File {
        // Try external storage first (Documents folder)
        val extDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(extDir, "WirelessAABridge/logs")
        if (appDir.mkdirs() || appDir.exists()) {
            log(Level.INFO, "LogManager", "Export dir: ${appDir.absolutePath}")
            return appDir
        }
        // Fallback to app-specific external files dir (always writable, adb-pullable)
        val fallback = context.getExternalFilesDir("logs") ?: context.filesDir
        log(Level.WARN, "LogManager", "Documents not writable, falling back to: ${fallback.absolutePath}")
        return fallback
    }
}