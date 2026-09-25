package com.thomaswcode.dictationapp.platform

import android.util.Log
import com.thomaswcode.dictationapp.core.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logcat plus a daily log file in `files/logs/dictation-YYYYMMDD.log`, kept for 14 days (the Windows app's
 * Serilog setup). Writes happen on one background thread so logging never blocks the caller.
 */
class AppLog(private val dir: File) : Logger {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    init {
        writer.execute {
            dir.mkdirs()
            val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    val currentFile: File get() = File(dir, "dictation-${dayFormat.format(Date())}.log")

    override fun debug(message: String) = write(Log.DEBUG, "DBG", message, null)

    override fun info(message: String) = write(Log.INFO, "INF", message, null)

    override fun warn(message: String, error: Throwable?) = write(Log.WARN, "WRN", message, error)

    override fun error(message: String, error: Throwable?) = write(Log.ERROR, "ERR", message, error)

    private fun write(priority: Int, level: String, message: String, error: Throwable?) {
        val full = if (error != null) message + "\n" + stackTrace(error) else message
        Log.println(priority, TAG, full)
        val now = Date()
        writer.execute {
            runCatching {
                dir.mkdirs()
                File(dir, "dictation-${dayFormat.format(now)}.log").appendText("${timeFormat.format(now)} [$level] $full\n")
            }
        }
    }

    private fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    companion object {
        const val TAG = "DictationApp"
    }
}
