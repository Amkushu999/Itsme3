package com.itsme.amkush

import android.app.Application
import android.content.Context
import com.itsme.amkush.utils.FileLoggingTree
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import timber.log.Timber

class FaceGateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SharedPrefs.init(this)

        // ── Timber setup ─────────────────────────────────────────────────────
        // DebugTree:     logs to logcat (colour + clickable links in Android Studio)
        // FileLoggingTree: appends everything (DEBUG+) to amkush_logs.txt with rotation
        //
        // Read logs from device:
        //   adb pull /data/data/com.itsme.amkush/files/amkush_logs.txt
        //   adb shell run-as com.itsme.amkush cat /data/data/com.itsme.amkush/files/amkush_logs.txt
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(FileLoggingTree(
            filesDir      = filesDir,
            minPriority   = android.util.Log.DEBUG,   // capture DEBUG and above to file
            logFileName   = "amkush_logs.txt",
            oldLogFileName= "amkush_logs_old.txt"
        ))

        // Logger uses Timber in the module process (isXposedMode = false).
        // In the Xposed hook process, MainHook calls Logger.init(true) which
        // switches to XposedBridge.log() instead.
        Logger.init(false)
        Logger.i(Logger.APP, "FaceGateApplication: module process started")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}
