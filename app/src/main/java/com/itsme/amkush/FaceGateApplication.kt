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

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // FIX: Save logs to public Downloads folder so you can access them without root
        val logDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "com.itsme.amkush"
        )
        
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        Timber.plant(FileLoggingTree(
            filesDir       = logDir,
            minPriority    = android.util.Log.VERBOSE,
            logFileName    = "amkush_logs.txt",
            oldLogFileName = "amkush_logs_old.txt"
        ))

        Logger.init(false)
        Logger.i(Logger.APP, "FaceGateApplication: module process started")
        Logger.i(Logger.APP, "Logs saving to: ${logDir.absolutePath}")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}
