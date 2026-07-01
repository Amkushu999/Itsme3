package com.itsme.amkush

  import android.content.Context
  import android.content.SharedPreferences
  import android.net.Uri
  import de.robv.android.xposed.IXposedHookLoadPackage
  import de.robv.android.xposed.XC_MethodHook
  import de.robv.android.xposed.XSharedPreferences
  import de.robv.android.xposed.XposedHelpers
  import de.robv.android.xposed.callbacks.XC_LoadPackage
  import com.itsme.amkush.hooks.*
  import android.util.Log
import com.itsme.amkush.utils.ExternalConfig
import com.itsme.amkush.utils.Logger

  class MainHook : IXposedHookLoadPackage {

      override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
          // FIX: Filter out Firefox sub-processes IMMEDIATELY before doing anything.
          //
          // When Firefox (or any multi-process app) boots inside Mochi Cloner it spawns
          // 10+ child processes simultaneously (GPU, tab renderers, crash-helper, …).
          // Every child process has a ":"-separated processName such as
          //   org.mozilla.firefox:gpu_disable_art_image_
          //   org.mozilla.firefox:tab_disable_art_image_37
          // Each of those triggers handleLoadPackage, which previously tried to start
          // InjectionService communication from every child at the same millisecond.
          // That race caused the ThreadPoolExecutor to terminate and then receive new
          // tasks → RejectedExecutionException crash.
          //
          // By returning here for any sub-process (colon in processName) or isolated
          // process, only the MAIN Firefox process proceeds — killing the flood.
          if (lpparam.processName.contains(":") || lpparam.processName.contains("isolated")) return

          if (lpparam.packageName == "android" || lpparam.packageName == "system") return

          Logger.init(true)
          Logger.d(Logger.MAIN, "handleLoadPackage: pkg=${lpparam.packageName}  processName=${lpparam.processName}")
          Log.d("FACEGATE", "handleLoadPackage: " + lpparam.packageName)

          // Only hook Application.onCreate here.  All camera / anti-detection hooks are
          // installed inside hookApplication()'s afterHookedMethod callback, AFTER we
          // confirm this is the target package and set isHookingActive = true.
          hookApplication(lpparam)
      }

      // ── hookApplication ───────────────────────────────────────────────────────
      //
      // Determines if this process is the target app.  If so:
      //   1. Sets AppState.isHookingActive = true.
      //   2. Registers ConfigUpdateReceiver for live URL swaps.
      //   3. Does NOT start a decoder — decoding runs in the MODULE process
      //      (InjectionService + FFmpegDecoder JNI).  The camera hooks will
      //      trigger the Binder connection to InjectionService automatically
      //      on the first createCaptureSession / setPreviewDisplay call.
      // ─────────────────────────────────────────────────────────────────────────
      private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              val applicationClass = lpparam.classLoader.loadClass("android.app.Application")
              XposedHelpers.findAndHookMethod(
                  applicationClass,
                  "onCreate",
                  object : XC_MethodHook() {
                      override fun afterHookedMethod(param: MethodHookParam) {
                          val ctx = param.thisObject as Context
                          AppState.context = ctx

                          val targetPackage = resolveModuleString(ctx, "target_package")
                          AppState.targetPackage = targetPackage

                          if (targetPackage.isNullOrEmpty()) {
                              Logger.d("No target configured — skipping ${lpparam.packageName}")
                              return
                          }
                          if (targetPackage != lpparam.packageName) {
                              Logger.d("Not target (target=$targetPackage) — skipping ${lpparam.packageName}")
                              return
                          }

                          val denyList: Set<String> = try {
                              openModulePrefs(ctx, "facegate_prefs")
                                  ?.getStringSet("deny_list", emptySet()) ?: emptySet()
                          } catch (_: Throwable) { emptySet() }

                          if (denyList.contains(lpparam.packageName)) {
                              Logger.d("App in deny list — skipping ${lpparam.packageName}")
                              return
                          }

                          Logger.i(Logger.MAIN, "TARGET MATCHED: ${lpparam.packageName} — installing all hooks now")
                          Log.d("FACEGATE", "TARGET MATCHED: " + lpparam.packageName + " — installing all hooks now")
                          AppState.isHookingActive = true

                          // Register live-update receiver — URL/config changes from the module
                          // UI take effect immediately without restarting the target app.
                          try {
                              ConfigUpdateReceiver.register(ctx)
                          } catch (e: Throwable) {
                              Logger.e("ConfigUpdateReceiver registration failed", e)
                          }

                          // ── Install all feature hooks now, only for the target process ──
                          // Xposed allows findAndHookMethod to be called at any time after
                          // handleLoadPackage; hooks installed here are still effective for all
                          // future calls since Camera APIs are always called after Application.onCreate.
                          //
                          // Camera hooks (FFmpeg native architecture):
                          try { Camera1Hooks.hookAll(lpparam); Logger.d("Camera1 hooks installed"); Log.d("FACEGATE", "Camera1 hooks installed OK") }
                          catch (e: Throwable) { Logger.e("Camera1 hooks failed", e); Log.e("FACEGATE", "Camera1 hooks FAILED: " + e.message) }

                          try { Camera2Hooks.hookAll(lpparam); Logger.d("Camera2 hooks installed"); Log.d("FACEGATE", "Camera2 hooks installed OK") }
                          catch (e: Throwable) { Logger.e("Camera2 hooks failed", e); Log.e("FACEGATE", "Camera2 hooks FAILED: " + e.message) }

                          try { CameraXHooks.hookAll(lpparam); Logger.d("CameraX hooks installed"); Log.d("FACEGATE", "CameraX hooks installed OK") }
                          catch (e: Throwable) { Logger.e("CameraX hooks failed", e); Log.e("FACEGATE", "CameraX hooks FAILED: " + e.message) }

                          // EXIF spoofing:
                          try { ExifSpoofHooks.hookAll(lpparam); Logger.d("EXIF spoof hooks installed") }
                          catch (e: Throwable) { Logger.e("EXIF spoof hooks failed", e) }

                          // Intent capture replacement:
                          try { IntentCaptureHooks.hookAll(lpparam); Logger.d("Intent capture hooks installed") }
                          catch (e: Throwable) { Logger.e("Intent capture hooks failed", e) }

                          // Device spoofing:
                          try { DeviceSpoofHooks.hookAll(lpparam); Logger.d("Device spoof hooks installed") }
                          catch (e: Throwable) { Logger.e("Device spoof hooks failed", e) }

                          // Deny list:
                          try { DenyListHooks.hookAll(lpparam); Logger.d("Deny list hooks installed") }
                          catch (e: Throwable) { Logger.e("Deny list hooks failed", e) }

                          // Anti-detection:
                          try { EmulatorBypassHooks.hookAll(lpparam); Logger.d("Emulator bypass hooks installed") }
                          catch (e: Throwable) { Logger.e("Emulator bypass hooks failed", e) }

                          try { RootBypassHooks.hookAll(lpparam); Logger.d(Logger.HOOK, "Root bypass hooks installed") }
                          catch (e: Throwable) { Logger.e(Logger.HOOK, "Root bypass hooks failed: ${e.message}", e) }

                          try { AntiXposedHooks.hookAll(lpparam); Logger.d(Logger.HOOK, "Anti-Xposed hooks installed") }
                          catch (e: Throwable) { Logger.e(Logger.HOOK, "Anti-Xposed hooks failed: ${e.message}", e) }

                          try { SELinuxBypassHooks.hookAll(lpparam); Logger.d(Logger.HOOK, "SELinux bypass hooks installed") }
                          catch (e: Throwable) { Logger.e(Logger.HOOK, "SELinux bypass hooks failed: ${e.message}", e) }

                          try { ClonerBypassHooks.hookAll(lpparam); Logger.d(Logger.HOOK, "Cloner bypass hooks installed") }
                          catch (e: Throwable) { Logger.e(Logger.HOOK, "Cloner bypass hooks failed: ${e.message}", e) }

                          // NOTE: No decoder is started here.
                          // The FFmpeg pipeline runs in the MODULE PROCESS (InjectionService).
                          // InjectionServiceClient connects to InjectionService via Binder the first
                          // time a camera session is created (Camera2Hooks → handleSessionCreation).
                      }
                  }
              )
          } catch (e: Throwable) {
              Logger.e("Failed to hook Application", e)
          }
      }

      // ── Module config resolution helpers ──────────────────────────────────────

      private fun openModulePrefs(ctx: Context, prefsName: String): SharedPreferences? = try {
          ctx.createPackageContext("com.itsme.amkush", Context.CONTEXT_IGNORE_SECURITY)
              .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      } catch (_: Throwable) { null }

      /**
       * Resolve a config string from the module using a four-layer fallback strategy.
       *
       * Layer 0 — ExternalConfig (sdcard file — primary fix for Mochi Cloner / TT_Xposed):
       *   Reads a plain .properties file from /sdcard/Android/media/com.itsme.amkush/.
       *
       *   WHY: Mochi Cloner (com.jy.x.separation.manager) runs its own built-in Xposed
       *   engine called TT_Xposed. TT_Xposed virtualizes the file system inside the clone,
       *   so XSharedPreferences reads from Mochi's fake virtualized prefs and returns
       *   garbage like "mnop.qrst.uvwx.yzab" instead of our real target package name.
       *   ContentProvider and createPackageContext are similarly broken inside TT_Xposed.
       *
       *   Mochi does NOT virtualize /sdcard — it is real shared external storage.
       *   A plain File.readText() is a kernel syscall that TT_Xposed cannot intercept.
       *   The module UI writes to this file when the user saves config. The hook reads it.
       *   No IPC, no timing dependency, no virtualization issues.
       *
       * Layer 1 — XSharedPreferences:
       *   Works with real LSPosed on standard rooted phones. Skipped inside TT_Xposed
       *   (returns virtualized garbage). Kept as fallback for non-Mochi environments.
       *
       * Layer 2 — ContentProvider:
       *   Fast when InjectionService is running on a standard rooted device.
       *
       * Layer 3 — createPackageContext SharedPreferences:
       *   Last resort for standard rooted phones and emulators without cloners.
       */
      private fun resolveModuleString(ctx: Context, key: String): String? {
          // Layer 0: ExternalConfig — plain file on /sdcard, survives all virtual environments.
          // Written by SharedPrefs.setTargetPackage() and RemoteConfig.write() on the real system.
          // Read here with plain File I/O — no XSharedPreferences, no ContentProvider, no IPC.
          val extValue = ExternalConfig.read(key)
          if (!extValue.isNullOrEmpty()) {
              Logger.d("resolveModuleString: ExternalConfig → $key=$extValue")
              return extValue
          }

          // Layer 1: XSharedPreferences — works with real LSPosed, fails inside TT_Xposed/Mochi.
          // makeWorldReadable() is a no-op on LSPosed (SELinux policy handles access) but kept
          // for compatibility with older Xposed frameworks on rooted phones/emulators.
          for (prefsName in listOf("facegate_ipc", "facegate_prefs", "saved_settings")) {
              try {
                  val xprefs = XSharedPreferences("com.itsme.amkush", prefsName)
                  @Suppress("DEPRECATION")
                  xprefs.makeWorldReadable()
                  val value = xprefs.getString(key, null)
                  if (!value.isNullOrEmpty()) {
                      Logger.d("resolveModuleString: XSharedPreferences[$prefsName] → $key=$value")
                      return value
                  }
              } catch (_: Throwable) {
                  // Not available in this environment — continue to next layer
              }
          }

          // Layer 2: ContentProvider — fast on standard rooted devices with InjectionService running.
          try {
              val uri = Uri.parse("content://com.itsme.amkush.ipc/config/$key")
              ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                  if (c.moveToFirst()) {
                      val idx = c.getColumnIndex("value")
                      if (idx >= 0) return c.getString(idx)
                  }
              }
          } catch (_: Throwable) {
              Logger.d("resolveModuleString: ContentProvider unavailable for $key")
          }

          // Layer 3: Direct SharedPreferences via createPackageContext (standard rooted / emulator).
          return openModulePrefs(ctx, "facegate_ipc")?.getString(key, null)
              ?: openModulePrefs(ctx, "facegate_prefs")?.getString(key, null)
      }
  }
