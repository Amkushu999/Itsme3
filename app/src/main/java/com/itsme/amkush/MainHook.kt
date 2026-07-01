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
       * Resolve a config string from the module using a three-layer fallback strategy.
       *
       * Layer 0 — XSharedPreferences (NEW — primary fix for Mochi Cloner):
       *   XSharedPreferences reads the module's preference file DIRECTLY from disk
       *   using the Xposed framework's privileged file access. It bypasses Android's
       *   normal IPC entirely, so it works even when the hook is running inside
       *   Mochi Cloner's virtual environment where the module app (com.itsme.amkush)
       *   is NOT cloned and therefore cannot be reached via createPackageContext or
       *   ContentProvider from inside the clone.
       *
       *   This is why the logs showed `target=mnop.qrst.uvwx.yzab` (a garbage/null
       *   read) — the other two layers were both failing silently inside Mochi.
       *
       * Layer 1 — ContentProvider:
       *   Fast when InjectionService is running on a standard (non-cloned) system.
       *
       * Layer 2 — createPackageContext SharedPreferences:
       *   Fallback for standard rooted phones and emulators without cloners.
       */
      private fun resolveModuleString(ctx: Context, key: String): String? {
          // Layer 0: XSharedPreferences — works across the Mochi Cloner boundary.
          // The hook code injected into Firefox (inside Mochi) can read the module's
          // real preference files on the host system via Xposed's privileged disk access.
          // makeWorldReadable() is called defensively; LSPosed handles permissions via
          // SELinux policy so this is safe and does not expose data to other apps.
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
                  // XSharedPreferences not available in this environment — continue
              }
          }

          // Layer 1: ContentProvider (fastest on standard rooted phones when InjectionService is running)
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

          // Layer 2: Direct SharedPreferences via createPackageContext (standard rooted / emulator)
          return openModulePrefs(ctx, "facegate_ipc")?.getString(key, null)
              ?: openModulePrefs(ctx, "facegate_prefs")?.getString(key, null)
      }
  }
