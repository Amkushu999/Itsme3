package com.itsme.amkush.utils

import java.io.File
import java.util.Properties

/**
 * ExternalConfig — a dead-simple config store that survives all virtual environments.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * WHY THIS EXISTS (the Mochi Cloner / TT_Xposed problem)
 * ──────────────────────────────────────────────────────────────────────────────
 * Mochi Cloner (real package: com.jy.x.separation.manager) runs a *built-in*
 * Xposed engine called **TT_Xposed** inside its virtual container. TT_Xposed is
 * NOT LSPosed — it is Mochi's own proprietary implementation that:
 *
 *   1. Virtualizes the file system inside the clone.
 *      XSharedPreferences("com.itsme.amkush", ...) reads from Mochi's fake
 *      virtualized prefs, which return garbage values like "mnop.qrst.uvwx.yzab"
 *      instead of our real target package name.
 *
 *   2. Blocks createPackageContext from crossing the clone boundary.
 *      com.itsme.amkush is NOT cloned, so createPackageContext throws inside
 *      the clone even with CONTEXT_IGNORE_SECURITY.
 *
 *   3. May intercept or delay ContentProvider queries.
 *      The hook fires before our ContentProvider process finishes starting, so
 *      queries time out or return empty cursors.
 *
 * SOLUTION:
 *   Write config to /sdcard/Android/media/com.itsme.amkush/facegate_config.properties
 *   Mochi Cloner does NOT virtualize /sdcard — it is real shared external storage.
 *   Reading a file is a bare kernel syscall (open/read/close); TT_Xposed cannot
 *   intercept plain File I/O.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * HOW IT WORKS
 * ──────────────────────────────────────────────────────────────────────────────
 *   Write side (runs on the REAL system, not inside the clone):
 *     SharedPrefs.setTargetPackage()   → ExternalConfig.write("target_package", pkg)
 *     RemoteConfig.write()             → ExternalConfig.write(key, value)
 *
 *   Read side (runs inside the cloned environment via TT_Xposed):
 *     MainHook.resolveModuleString()   → ExternalConfig.read(key)
 *     No Context needed — just plain File I/O.
 *
 * File format: standard Java .properties (key=value, one per line).
 */
object ExternalConfig {

    private const val TAG = "ExternalConfig"

    /** Path on real shared external storage — unchanged inside Mochi's clone. */
    private val CONFIG_DIR  = File("/sdcard/Android/media/com.itsme.amkush")
    private val CONFIG_FILE = File(CONFIG_DIR, "facegate_config.properties")

    /** Synchronization monitor for file reads/writes. */
    private val fileLock = Any()

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist [key]=[value] to the config file.
     * Null or blank [value] removes the key.
     * Safe to call from any thread; silently swallows I/O failures.
     */
    fun write(key: String, value: String?) {
        synchronized(fileLock) {
            try {
                CONFIG_DIR.mkdirs()
                val props = Properties()
                if (CONFIG_FILE.exists()) {
                    CONFIG_FILE.inputStream().buffered().use { props.load(it) }
                }
                if (value.isNullOrEmpty()) {
                    props.remove(key)
                } else {
                    props.setProperty(key, value)
                }
                CONFIG_FILE.outputStream().buffered().use {
                    props.store(it, "FaceGate config - managed automatically, do not edit")
                }
            } catch (e: Throwable) {
                // External storage unavailable (e.g. before mount): silent failure.
                // The ContentProvider / XSharedPreferences layers in MainHook will
                // still be attempted as fallbacks.
                Logger.d("$TAG write($key) failed: ${e.message}")
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Read [key] from the config file.
     * Returns null if the file does not exist, the key is absent, or any I/O error occurs.
     * No Context required — safe to call from an Xposed hook.
     */
    fun read(key: String): String? {
        synchronized(fileLock) {
            return try {
                if (!CONFIG_FILE.exists()) {
                    Logger.d("$TAG read($key): config file not found")
                    return null
                }
                val props = Properties()
                CONFIG_FILE.inputStream().buffered().use { props.load(it) }
                val value = props.getProperty(key)?.takeIf { it.isNotEmpty() }
                if (value != null) {
                    Logger.d("$TAG read($key) → $value")
                }
                value
            } catch (e: Throwable) {
                Logger.d("$TAG read($key) failed: ${e.message}")
                null
            }
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    /** Delete the entire config file (called when injection stops). */
    fun clearAll() {
        synchronized(fileLock) {
            try {
                CONFIG_FILE.delete()
                Logger.d("$TAG clearAll: config file deleted")
            } catch (e: Throwable) {
                Logger.d("$TAG clearAll failed: ${e.message}")
            }
        }
    }
}
