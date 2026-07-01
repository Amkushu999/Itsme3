package com.itsme.amkush.utils

import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FileLoggingTree — Timber tree that writes deep logs to disk.
 *
 * Output file: <filesDir>/amkush_logs.txt
 * Rotation: when the file exceeds 5 MB it is renamed to amkush_logs_old.txt
 *           and a new amkush_logs.txt is started.
 *
 * Filter: only records INFO and above by default (configurable).
 *         Full stack traces are appended for every Throwable.
 *
 * Thread-safety: all disk I/O is dispatched to a single background thread.
 *
 * Usage (plant in FaceGateApplication.onCreate):
 *   Timber.plant(FileLoggingTree(filesDir))
 *
 * Read on device:
 *   adb shell run-as com.itsme.amkush cat /data/data/com.itsme.amkush/files/amkush_logs.txt
 *   adb pull /data/data/com.itsme.amkush/files/amkush_logs.txt
 */
class FileLoggingTree(
    filesDir: File,
    /** Minimum Android log priority to write. Default: Log.DEBUG to capture everything. */
    private val minPriority: Int = Log.VERBOSE,
    /** File name inside filesDir */
    logFileName: String = "amkush_logs.txt",
    oldLogFileName: String = "amkush_logs_old.txt"
) : Timber.Tree() {

    private val logFile = File(filesDir, logFileName)
    private val oldFile = File(filesDir, oldLogFileName)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Single background thread keeps file I/O off the calling thread */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AmkushFileLogger").apply { isDaemon = true }
    }

    companion object {
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024  // 20 MB
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG   -> "D"
            Log.INFO    -> "I"
            Log.WARN    -> "W"
            Log.ERROR   -> "E"
            Log.ASSERT  -> "A"
            else        -> "?"
        }
        val timestamp = dateFmt.format(Date())
        val thread = Thread.currentThread().name
        val line = "$timestamp $level/$tag [thread=$thread]: $message"
        val stackTrace = t?.stackTraceToString()

        ioExecutor.execute {
            try {
                rotateIfNeeded()
                FileWriter(logFile, /* append= */ true).use { fw ->
                    PrintWriter(fw).use { pw ->
                        pw.println(line)
                        if (stackTrace != null) {
                            pw.println(stackTrace)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Swallow — logging errors must never crash the app.
                // We intentionally use _ here since we cannot log the error
                // (that would recurse into the same failing file write).
            }
        }
    }

    /**
     * Rotate: if amkush_logs.txt exceeds 5 MB, rename it to amkush_logs_old.txt
     * (overwriting any previous old file) and start fresh.
     */
    private fun rotateIfNeeded() {
        if (logFile.length() >= MAX_FILE_BYTES) {
            oldFile.delete()
            logFile.renameTo(oldFile)
        }
    }

    /**
     * Flush and shut down the background executor gracefully.
     * Call from Application.onTerminate() if desired.
     */
    fun shutdown() {
        ioExecutor.shutdown()
        ioExecutor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
