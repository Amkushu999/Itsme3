package com.itsme.amkush

import android.app.Application
import android.content.Context
import android.os.Environment
import com.itsme.amkush.utils.FileLoggingTree
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import timber.log.Timber
import java.io.File

class FaceGateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SharedPrefs.init(this)

        // ── Timber setup ─────────────────────────────────────────────────────
        // DebugTree:     logs to logcat (colour + clickable links in Android Studio)
        // FileLoggingTree: appends everything (DEBUG+) to amkush_logs.txt with rotation
        //
        // NEW: Logs now save to Downloads folder for easy access without root
        // Location: /storage/emulated/0/Download/com.itsme.amkush/amkush_logs.txt
        //
        // Read logs from device:
        //   Option 1: Use any file manager to navigate to Downloads/com.itsme.amkush/
        //   Option 2: adb pull /sdcard/Download/com.itsme.amkush/amkush_logs.txt
        //   Option 3: adb shell cat /sdcard/Download/com.itsme.amkush/amkush_logs.txt
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // Create log directory in Downloads folder
        val logDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "com.itsme.amkush"
        )
        
        // Ensure directory exists
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        Timber.plant(FileLoggingTree(
            filesDir      = logDir,  // Changed from filesDir to Downloads folder
            minPriority   = android.util.Log.DEBUG,
            logFileName   = "amkush_logs.txt",
            oldLogFileName= "amkush_logs_old.txt"
        ))

        // Logger uses Timber in the module process (isXposedMode = false).
        // In the Xposed hook process, MainHook calls Logger.init(true) which
        // switches to XposedBridge.log() instead.
        Logger.init(false)
        Logger.i(Logger.APP, "FaceGateApplication: module process started")
        Logger.i(Logger.APP, "Logs saving to: ${logDir.absolutePath}")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}
