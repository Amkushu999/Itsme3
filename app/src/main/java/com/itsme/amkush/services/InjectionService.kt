package com.itsme.amkush.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.itsme.amkush.AppState
import com.itsme.amkush.R
import com.itsme.amkush.ffmpeg.FFmpegDecoder
import com.itsme.amkush.hooks.ConfigUpdateReceiver
import com.itsme.amkush.ipc.ISurfaceInjector
import com.itsme.amkush.ipc.UnixSocketServer
import com.itsme.amkush.ipc.RemoteConfig
import com.itsme.amkush.router.SurfaceRouter
import android.net.Uri
import android.util.Log
import com.itsme.amkush.utils.Logger
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors // FIX: Added for background threading

/**
 * InjectionService — module-process owner of the FFmpeg pipeline.
 *
 * Responsibilities:
 *   1. Run as a foreground service (camera + mediaPlayback type).
 *   2. Expose [ISurfaceInjector] Binder so Xposed hooks inside any hooked
 *      target-app process can deliver Surface objects for frame injection.
 *   3. Own and manage the FFmpeg decode context (via [FFmpegDecoder] JNI).
 *   4. Forward decoded I420 frames to [SurfaceRouter] which scales via LibYuv
 *      and pushes to each registered Surface via ImageWriter.
 *   5. Broadcast config changes to running hooked processes for live URL swaps.
 *
 * Threading:
 *   - Binder calls → Binder thread pool → forward to SurfaceRouter (thread-safe).
 *   - FFmpeg frames → native decode thread → SurfaceRouter.onFrameAvailable()
 *     → per-surface push threads (each with their own ImageWriter).
 *   - FIX: All FFmpeg open/close/hotSwap operations run on [decoderExecutor]
 *     to prevent ANR (Application Not Responding) errors caused by blocking
 *     network I/O in avformat_open_input().
 */
class InjectionService : Service() {

    companion object {
        private const val TAG             = "InjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "facegate_injection_channel"
        private const val CHANNEL_NAME    = "FaceGate Injection Service"

        @Volatile var isRunning = false
            private set

        fun start(
            context: Context,
            targetPackage: String,
            streamUrl: String? = null,
            mediaUri: String?  = null
        ) {
            if (!isRunning) {
                val intent = Intent(context, InjectionService::class.java).apply {
                    putExtra("target_package", targetPackage)
                    putExtra("stream_url",     streamUrl)
                    putExtra("media_uri",      mediaUri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, InjectionService::class.java))
                isRunning = false
            }
        }
    }

    // Native decoder handle; 0 = not running
    @Volatile private var decoderHandle: Long = 0L

    // FIX: Dedicated background thread for all FFmpeg operations.
    // FFmpeg network calls (avformat_open_input) block the calling thread.
    // If we call them on the main thread or Binder thread, it causes an ANR.
    // Using a single-thread executor ensures operations are serialized.
    private val decoderExecutor = Executors.newSingleThreadExecutor()

    // ── ISurfaceInjector Binder stub ─────────────────────────────────────────

    private val binderImpl = object : ISurfaceInjector.Stub() {

        override fun registerSurfaces(
            surfaces: List<Surface>,
            widths: IntArray,
            heights: IntArray,
            formats: IntArray,
            fps: IntArray,
            sessionId: String
        ) {
            Logger.d(Logger.INJECTION, "$TAG registerSurfaces: ${surfaces.size} surface(s)  session=$sessionId  widths=${widths.toList()}  heights=${heights.toList()}  formats=${formats.toList()}  fps=${fps.toList()}")
            SurfaceRouter.registerSession(sessionId, surfaces, widths, heights, formats, fps)
            ensureDecoderRunning()
        }

        override fun unregisterSession(sessionId: String) {
            Logger.d(Logger.INJECTION, "$TAG unregisterSession: $sessionId")
            SurfaceRouter.unregisterSession(sessionId)
        }

        override fun startDecoder(url: String) {
            Logger.d(Logger.INJECTION, "$TAG startDecoder: url=$url")
            if (url.isNotBlank()) {
                // FIX: Run on background thread to prevent ANR
                decoderExecutor.execute { startOrRestartDecoder(url) }
            }
        }

        override fun hotSwap(url: String) {
            Logger.d(Logger.INJECTION, "$TAG hotSwap: url=$url")
            Log.d("FACEGATE", "InjectionService: hotSwap -> $url")
            
            // FIX: Run on background thread to prevent ANR
            decoderExecutor.execute {
                if (url.isBlank()) {
                    Logger.d(Logger.INJECTION, "$TAG hotSwap blank — stopping decoder")
                    stopDecoder()
                    return@execute
                }
                
                // Resolve URL outside the lock (can involve disk I/O for content:// URIs)
                val resolved = resolveUrl(url) ?: run {
                    Logger.e(Logger.INJECTION, "$TAG hotSwap: URL resolution failed for $url")
                    return@execute
                }
                
                // BUG FIX: Read decoderHandle and call hotSwap inside the same lock used by
                // startOrRestartDecoder/stopDecoder. Without this, stopDecoder() can set
                // decoderHandle=0 and free the native handle between our read and our call,
                // resulting in FFmpegDecoder.hotSwap() being invoked on a closed/freed handle.
                synchronized(this@InjectionService) {
                    val h = decoderHandle
                    if (h != 0L) {
                        Logger.d(Logger.INJECTION, "$TAG hotSwap: signalling native handle=$h  new=$resolved")
                        FFmpegDecoder.hotSwap(h, resolved)
                    } else {
                        Logger.d(Logger.INJECTION, "$TAG hotSwap: no running decoder — starting fresh")
                        startOrRestartDecoder(url)
                    }
                }
            }
        }

        override fun stopAll() {
            Logger.d(Logger.INJECTION, "$TAG stopAll — tearing down all sessions and decoder")
            SurfaceRouter.unregisterAll()
            // FIX: Run on background thread to prevent ANR
            decoderExecutor.execute { stopDecoder() }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppState.context = applicationContext
          createNotificationChannel()
          startForeground(NOTIFICATION_ID, createNotification())

          // Create shared socket directory for Mochi Cloner fallback transport
          try { File("/sdcard/Android/media/com.itsme.amkush").mkdirs() } catch (_: Throwable) {}

          // Start Unix socket server (low-cost — only active when hook connects via socket)
          UnixSocketServer.start()

          Logger.i(Logger.INJECTION, "$TAG created — FFmpeg decoder + Unix socket server ready")
          Log.d("FACEGATE", "InjectionService: onCreate — foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package")
        val streamUrl     = intent?.getStringExtra("stream_url")
        val mediaUri      = intent?.getStringExtra("media_uri")

        if (!targetPackage.isNullOrEmpty()) {
            Logger.d("$TAG config → pkg=$targetPackage stream=$streamUrl media=$mediaUri")
            RemoteConfig.setTargetPackage(this, targetPackage)
            RemoteConfig.setStreamUrl(this, streamUrl)
            RemoteConfig.setMediaUri(this, mediaUri)
            RemoteConfig.setInjectionActive(this, true)
            sendConfigBroadcast(streamUrl = streamUrl, mediaUri = mediaUri, active = true)
            
            val url = streamUrl ?: mediaUri
            if (!url.isNullOrEmpty()) {
                // FIX: Run on background thread to prevent ANR
                decoderExecutor.execute { startOrRestartDecoder(url) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binderImpl

    override fun onDestroy() {
        Logger.i(Logger.INJECTION, "$TAG onDestroy — tearing down decoder and all sessions")
        SurfaceRouter.unregisterAll()
        
        // FIX: Run on background thread to prevent ANR, then shutdown executor
        decoderExecutor.execute { 
            stopDecoder() 
            decoderExecutor.shutdown() 
        }
        
        UnixSocketServer.stop()
          RemoteConfig.clearAll(this)
          sendConfigBroadcast(streamUrl = null, mediaUri = null, active = false)
          isRunning = false
          Log.d("FACEGATE", "InjectionService: onDestroy — service stopped")
          super.onDestroy()
    }

    // ── Decoder management ────────────────────────────────────────────────────

    private val frameCallback = object : FFmpegDecoder.FrameCallback {
        override fun onFrameAvailable(
            yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
            width: Int, height: Int, ptsUs: Long
        ) {
            // Forward to SurfaceRouter — it copies the planes before this returns
            SurfaceRouter.onFrameAvailable(yBuf, uBuf, vBuf, width, height, ptsUs)
        }
        override fun onError(code: Int, msg: String) {
            Logger.e("$TAG decoder error $code: $msg")
        }
        override fun onEof() {
            Logger.d("$TAG decoder EOF (file will loop)")
        }
    }

    private fun ensureDecoderRunning() {
        if (decoderHandle != 0L) return
        val url = RemoteConfig.getStreamUrl(this) ?: RemoteConfig.getMediaUri(this)
        if (!url.isNullOrEmpty()) {
            // FIX: Run on background thread to prevent ANR
            decoderExecutor.execute { startOrRestartDecoder(url) }
        } else {
            Logger.d("$TAG no URL configured yet — decoder deferred until URL is set")
        }
    }

    // BUG FIX: @Synchronized prevents two concurrent Binder threads from both calling
    // FFmpegDecoder.open() simultaneously, which would leak the first handle.
    @Synchronized
    private fun startOrRestartDecoder(rawUrl: String) {
        stopDecoder()
        Logger.d(Logger.INJECTION, "$TAG startOrRestartDecoder raw=$rawUrl")
        Log.d("FACEGATE", "InjectionService: startOrRestartDecoder raw=$rawUrl")
        
        val url = resolveUrl(rawUrl) ?: run {
            Logger.e(Logger.INJECTION, "$TAG URL resolution failed, aborting: $rawUrl")
            Log.e("FACEGATE", "InjectionService: URL resolution failed: $rawUrl")
            return
        }
        
        if (url != rawUrl) {
            Logger.d(Logger.INJECTION, "$TAG URL normalized: $rawUrl → $url")
            Log.d("FACEGATE", "InjectionService: URL normalized: $rawUrl → $url")
        }
        
        if (url.startsWith("rtmp://") || url.startsWith("rtmps://"))
            Logger.i(Logger.INJECTION, "$TAG RTMP stream — ensure port 1935 is reachable")
        
        Logger.d(Logger.INJECTION, "$TAG FFmpegDecoder.open url=$url")
        val handle = FFmpegDecoder.open(url, frameCallback)
        
        if (handle == 0L) {
            Logger.e(Logger.INJECTION, "$TAG FFmpegDecoder.open FAILED for: $url")
            Log.e("FACEGATE", "InjectionService: FFmpegDecoder.open FAILED url=$url")
        } else {
            decoderHandle = handle
            Logger.d(Logger.INJECTION, "$TAG decoder running handle=$handle  hw=${FFmpegDecoder.isUsingHardwareDecoder(handle)}  url=$url")
            Log.d("FACEGATE", "InjectionService: decoder running handle=$handle url=$url")
        }
    }

    // Add scheme if missing. Defaults to http:// (some FFmpeg builds lack TLS).
    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("http://")    -> t
            t.startsWith("https://")   -> t
            t.startsWith("rtmp://")    -> t
            t.startsWith("rtmps://")   -> t
            t.startsWith("rtsp://")    -> t
            t.startsWith("rtsps://")   -> t
            t.startsWith("udp://")     -> t
            t.startsWith("rtp://")     -> t
            t.startsWith("srt://")     -> t
            t.startsWith("file://")    -> t
            t.startsWith("content://") -> t
            t.startsWith("/")          -> "file://" + t
            else                       -> "http://" + t
        }
    }

    // Resolve URL for native FFmpeg. Copies content:// URIs to a temp file
    // because Android content URIs are not understood by native FFmpeg.
    private fun resolveUrl(raw: String): String? {
        val normalized = normalizeUrl(raw)
        if (!normalized.startsWith("content://")) {
            Logger.d(Logger.INJECTION, "$TAG resolveUrl: passthrough url=$normalized")
            return normalized
        }
        Logger.d(Logger.INJECTION, "$TAG resolveUrl: content:// URI — copying to temp file")
        Log.d("FACEGATE", "InjectionService: resolving content:// URI -> temp file")
        
        // BUG FIX (original): Create temp file reference before try-block so we can delete it on failure.
        val tmp = File(cacheDir, "fg_media_${System.currentTimeMillis()}.tmp")
        return try {
            clearMediaCache()
            val uri = Uri.parse(normalized)
            // BUG FIX: openInputStream() can return null (e.g. provider returns no stream).
            // Previously the null case fell through the ?.use block silently, then returned
            // tmp.absolutePath pointing at an empty/non-existent file — FFmpeg would open it
            // and immediately hit EOF with no useful error.
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                tmp.delete()
                Logger.e(Logger.INJECTION, "$TAG resolveUrl: openInputStream returned null for $uri")
                Log.e("FACEGATE", "InjectionService: openInputStream null for $uri")
                return null
            }
            inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            Logger.d(Logger.INJECTION, "$TAG content:// resolved → ${tmp.absolutePath}  (${tmp.length()} bytes)")
            Log.d("FACEGATE", "InjectionService: content:// resolved -> ${tmp.absolutePath} (${tmp.length()} bytes)")
            tmp.absolutePath
        } catch (e: Exception) {
            // Delete partial/corrupt temp file so FFmpeg never gets it
            tmp.delete()
            Logger.e(Logger.INJECTION, "$TAG failed to resolve content URI: ${e.message}", e)
            Log.e("FACEGATE", "InjectionService: failed to resolve content URI: ${e.message}")
            null
        }
    }

    // Delete stale temp media files left from a previous session.
    private fun clearMediaCache() {
        try {
            cacheDir.listFiles()?.filter { it.name.startsWith("fg_media_") }?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    // BUG FIX: @Synchronized pairs with startOrRestartDecoder to prevent handle leaks
    @Synchronized
    private fun stopDecoder() {
        val h = decoderHandle
        if (h != 0L) {
            Logger.d(Logger.INJECTION, "$TAG stopDecoder: closing handle=$h")
            decoderHandle = 0L
            try { 
                FFmpegDecoder.close(h) 
            } catch (e: Throwable) { 
                Logger.e(Logger.INJECTION, "$TAG stopDecoder error: ${e.message}", e) 
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendConfigBroadcast(streamUrl: String?, mediaUri: String?, active: Boolean) {
        try {
            sendBroadcast(Intent(ConfigUpdateReceiver.ACTION).apply {
                putExtra(ConfigUpdateReceiver.EXTRA_STREAM_URL, streamUrl)
                putExtra(ConfigUpdateReceiver.EXTRA_MEDIA_URI,  mediaUri)
                putExtra(ConfigUpdateReceiver.EXTRA_ACTIVE,     active)
            })
        } catch (e: Throwable) {
            Logger.e("$TAG sendConfigBroadcast failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FaceGate FFmpeg injection service"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceGate")
            .setContentText("FFmpeg camera injection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
}
