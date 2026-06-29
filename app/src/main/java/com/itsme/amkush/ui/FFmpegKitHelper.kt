package com.itsme.amkush.ui

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Session
import com.itsme.amkush.utils.Logger
import java.io.File

/**
 * FFmpegKitHelper
 *
 * Utilities for managing FFmpegKit pipe lifecycle.
 *
 * Problem: FFmpegKit registers named pipes in <cacheDir>/pipes/ for its
 * piped FFmpeg sessions.  If a session is stopped without cleanup (dialog
 * dismissed, crash, rotation), those pipes persist and the next session
 * fails with "pipe already exists" or "resource busy".
 *
 * Usage in StreamPreviewDialog:
 *
 *   DisposableEffect(url) {
 *       FFmpegKitHelper.cleanPipes(context)
 *       val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
 *       FFmpegKitHelper.executeWithCleanup(context, "-i $url ...") { session ->
 *           // handle session result
 *       }
 *       onDispose {
 *           FFmpegKitHelper.cancelAll(context)
 *       }
 *   }
 *
 * Filter with: adb logcat -s UI:D
 */
object FFmpegKitHelper {

    private const val PIPES_DIR = "pipes"

    /**
     * Delete all leftover FFmpegKit pipes from the cache directory.
     * Call before registering a new pipe to avoid "already exists" errors.
     */
    fun cleanPipes(context: Context) {
        try {
            val pipesDir = File(context.cacheDir, PIPES_DIR)
            if (!pipesDir.exists()) return
            val files = pipesDir.listFiles() ?: return
            var deleted = 0
            files.forEach { file ->
                if (file.delete()) deleted++
            }
            if (deleted > 0) {
                Logger.d(Logger.UI, "FFmpegKitHelper: cleaned $deleted stale pipe(s) from ${pipesDir.path}")
            }
        } catch (e: Throwable) {
            Logger.w(Logger.UI, "FFmpegKitHelper: cleanPipes error: ${e.message}")
        }
    }

    /**
     * Execute an FFmpegKit command with automatic pipe cleanup before and after.
     *
     * @param context  Context for resolving cache directory.
     * @param command  Full FFmpegKit command string (without "ffmpeg" prefix).
     * @param callback Invoked on the session when FFmpegKit finishes.
     */
    fun executeWithCleanup(
        context: Context,
        command: String,
        callback: (Session) -> Unit
    ) {
        Logger.d(Logger.UI, "FFmpegKitHelper: executeWithCleanup cmd=$command")
        cleanPipes(context)
        FFmpegKit.executeAsync(command) { session ->
            try {
                callback(session)
            } finally {
                cleanPipes(context)
                Logger.d(Logger.UI, "FFmpegKitHelper: session complete  returnCode=${session.returnCode}")
            }
        }
    }

    /**
     * Cancel all active FFmpegKit sessions and clean up pipes.
     * Call from DisposableEffect.onDispose or onStop/onDestroy.
     */
    fun cancelAll(context: Context) {
        Logger.d(Logger.UI, "FFmpegKitHelper: cancelAll — cancelling active sessions")
        FFmpegKit.cancel()
        cleanPipes(context)
    }

    /**
     * Register a fresh pipe, cleaning stale ones first.
     * Returns the pipe path string to pass to FFmpegKit commands as input.
     */
    fun registerCleanPipe(context: Context): String {
        cleanPipes(context)
        val path = FFmpegKitConfig.registerNewFFmpegPipe(context)
        Logger.d(Logger.UI, "FFmpegKitHelper: registered pipe=$path")
        return path
    }
}
