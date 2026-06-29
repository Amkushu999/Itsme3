package com.itsme.amkush.utils

import android.util.Log
import de.robv.android.xposed.XposedBridge
import timber.log.Timber

/**
 * Deep-logging utility with per-category logcat tags + Timber file sink.
 *
 * In the MODULE PROCESS (InjectionService, UI, Application):
 *   Logger.init(false) — routes through Timber.
 *   Plant FileLoggingTree in FaceGateApplication to capture to amkush_logs.txt.
 *
 * In the HOOK PROCESS (Xposed, target app):
 *   Logger.init(true)  — routes through XposedBridge.log() (LSPosed log viewer).
 *
 * ── Logcat filter commands ────────────────────────────────────────────────────
 *   adb logcat -s HOOK:D          All Xposed hook interceptions
 *   adb logcat -s DECODER:D       FFmpeg + LibYuv C++ + JNI decoder events
 *   adb logcat -s INJECTION:D     InjectionService + InjectionServiceClient
 *   adb logcat -s ROUTER:D        SurfaceRouter + FpsPacer + ImageLoopSource
 *   adb logcat -s IPC:D           FaceGateIpcProvider + RemoteConfig
 *   adb logcat -s UI:D            Fragments, screens, ViewModel, FFmpegKitHelper
 *   adb logcat -s MAIN:D          MainHook entry point
 *   adb logcat -s APP:D           Application + AppState + CameraState
 *   adb logcat -s FFmpegDecoder:D Native C++ per-frame events (unchanged)
 *   adb logcat -s LibYuvWrapper:D Native YUV conversion events (unchanged)
 *   adb logcat -s HOOK:D DECODER:D INJECTION:D ROUTER:D   — multi-tag
 *
 * ── File log ─────────────────────────────────────────────────────────────────
 *   adb pull /data/data/com.itsme.amkush/files/amkush_logs.txt
 *   adb shell run-as com.itsme.amkush cat /data/data/com.itsme.amkush/files/amkush_logs.txt
 */
object Logger {

    // ── Category tag constants ───────────────────────────────────────────────
    const val HOOK      = "HOOK"
    const val DECODER   = "DECODER"
    const val UI        = "UI"
    const val INJECTION = "INJECTION"
    const val ROUTER    = "ROUTER"
    const val IPC       = "IPC"
    const val MAIN      = "MAIN"
    const val APP       = "APP"

    /** Legacy single tag kept for code not yet migrated to category tags. */
    private const val LEGACY_TAG = "FaceGate"

    @Volatile private var isXposedMode = false

    fun init(xposedMode: Boolean) {
        isXposedMode = xposedMode
    }

    // ── Tagged methods (preferred) ───────────────────────────────────────────

    /** Verbose — very detailed trace; use for entry/exit, parameter dumps. */
    fun v(tag: String, message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$tag/V] $message")
        } else {
            Timber.tag(tag).v(message)
        }
    }

    /** Debug — normal diagnostic messages. */
    fun d(tag: String, message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$tag/D] $message")
        } else {
            Timber.tag(tag).d(message)
        }
    }

    /** Info — notable lifecycle events. */
    fun i(tag: String, message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$tag/I] $message")
        } else {
            Timber.tag(tag).i(message)
        }
    }

    /** Warning — unexpected but recoverable situations. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isXposedMode) {
            XposedBridge.log("[$tag/W] $message")
            throwable?.let { XposedBridge.log("[$tag/W] ${it.stackTraceToString()}") }
        } else {
            if (throwable != null) Timber.tag(tag).w(throwable, message)
            else Timber.tag(tag).w(message)
        }
    }

    /** Error — failures that need attention; always include the Throwable when available. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isXposedMode) {
            XposedBridge.log("[$tag/E] $message")
            throwable?.let { XposedBridge.log("[$tag/E] ${it.stackTraceToString()}") }
        } else {
            if (throwable != null) Timber.tag(tag).e(throwable, message)
            else Timber.tag(tag).e(message)
        }
    }

    // ── Legacy untagged helpers (backward compat) ────────────────────────────

    fun d(message: String) = d(LEGACY_TAG, message)
    fun i(message: String) = i(LEGACY_TAG, message)
    fun w(message: String) = w(LEGACY_TAG, message)

    fun e(message: String, throwable: Throwable? = null) = e(LEGACY_TAG, message, throwable)

    fun logException(tag: String, throwable: Throwable) {
        e(tag, "Exception: ${throwable.message}", throwable)
    }
}
