package com.itsme.amkush.hooks

  import android.content.ComponentName
  import android.content.Context
  import android.content.Intent
  import android.content.ServiceConnection
  import android.net.LocalSocket
  import android.net.LocalSocketAddress
  import android.os.Handler
  import android.os.IBinder
  import android.os.Looper
  import android.view.Surface
  import com.itsme.amkush.AppState
  import com.itsme.amkush.ipc.ISurfaceInjector
  import com.itsme.amkush.utils.Logger
  import java.io.BufferedReader
  import java.io.InputStreamReader
  import java.io.PrintWriter
  import java.util.concurrent.ConcurrentHashMap

  /**
   * InjectionServiceClient — runs inside the hooked target-app process.
   *
   * Primary transport:  Android Binder (ISurfaceInjector AIDL).
   * Fallback transport: Unix domain socket — activates automatically when
   *   bindService() returns false (immediate) OR onServiceConnected() never fires
   *   within 3 seconds (Mochi Cloner / virtual namespace detection).
   *
   * Connection lifecycle:
   *   1. First routeSurfaces() call → connectToInjectionService()
   *   2a. Binder path:  onServiceConnected() fires → cancel 3s timer → drain pending
   *   2b. Socket path:  3s timer fires (or bindService()==false) → connectViaUnixSocket()
   *   3. On camera close → stopSession() / stopAll()
   */
  object InjectionServiceClient {

      private const val TAG             = "InjectionClient"
      private const val MODULE_PKG      = "com.itsme.amkush"
      private const val INJECTOR_ACTION = "com.itsme.amkush.action.SURFACE_INJECTOR"
      private const val CTRL_NAME       = "facegate_vcam_ctrl"
      private const val BIND_TIMEOUT_MS = 3_000L

      // ── Transport state ───────────────────────────────────────────────────────

      private enum class Transport { BINDER, SOCKET, NONE }

      @Volatile private var transport   = Transport.NONE
      @Volatile private var injector: ISurfaceInjector? = null
      @Volatile private var bindPending = false

      // Socket transport state
      @Volatile private var controlSocket: LocalSocket? = null
      @Volatile private var controlWriter: PrintWriter? = null
      @Volatile private var controlReader: BufferedReader? = null
      private val socketReceivers: MutableList<SocketFrameReceiver> = mutableListOf()

      // Binder timeout
      private val timeoutHandler   = Handler(Looper.getMainLooper())
      private val timeoutRunnable  = Runnable {
          if (injector == null && bindPending) {
              bindPending = false
              Logger.w(Logger.INJECTION, "$TAG Binder timeout (${BIND_TIMEOUT_MS}ms) — cloner detected, switching to Unix socket")
              connectViaUnixSocket()
          }
      }

      // Session tracking
      private val deviceSessions: ConcurrentHashMap<Any, String> = ConcurrentHashMap()

      private data class PendingDelivery(
          val surfaces: List<Surface>,
          val widths:   IntArray,
          val heights:  IntArray,
          val formats:  IntArray,
          val fps:      IntArray,
          val sessionId: String
      )
      private val pendingDeliveries: ArrayDeque<PendingDelivery> = ArrayDeque()

      private enum class UrlActionType { HOT_SWAP, START_DECODER }
      private data class PendingUrlAction(val type: UrlActionType, val url: String)
      private val pendingUrlActions: ArrayDeque<PendingUrlAction> = ArrayDeque()

      // ── Binder ServiceConnection ──────────────────────────────────────────────

      private val connection = object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
              timeoutHandler.removeCallbacks(timeoutRunnable)   // cancel cloner-detection timer
              val svc = ISurfaceInjector.Stub.asInterface(binder)
              injector  = svc
              transport = Transport.BINDER
              AppState.injectorService = svc
              bindPending = false
              Logger.i(Logger.INJECTION, "$TAG Binder connected — draining pending")
              drainPendingBinder()
          }
          override fun onServiceDisconnected(name: ComponentName?) {
              injector  = null
              transport = Transport.NONE
              AppState.injectorService = null
              Logger.w(Logger.INJECTION, "$TAG Binder disconnected — will reconnect on next call")
          }
      }

      // ── Public API ────────────────────────────────────────────────────────────

      fun routeSurfaces(
          owner:     Any,
          surfaces:  List<Any>,
          widths:    IntArray,
          heights:   IntArray,
          formats:   IntArray,
          fps:       IntArray,
          sessionId: String
      ) {
          if (surfaces.isEmpty()) return
          deviceSessions[owner] = sessionId
          val filtered = surfaces.filterIsInstance<Surface>()
          if (filtered.isEmpty()) return

          Logger.d(Logger.INJECTION, "$TAG routeSurfaces: session=$sessionId  surfaces=${filtered.size}  w=${widths.toList()}  h=${heights.toList()}")

          when (transport) {
              Transport.BINDER -> {
                  deliverViaBinder(filtered, widths, heights, formats, fps, sessionId)
              }
              Transport.SOCKET -> {
                  deliverViaSocket(filtered, widths, heights, formats, fps, sessionId)
              }
              Transport.NONE   -> {
                  synchronized(pendingDeliveries) {
                      pendingDeliveries.addLast(PendingDelivery(filtered, widths, heights, formats, fps, sessionId))
                  }
                  connectToInjectionService()
              }
          }
      }

      fun stopSession(owner: Any) {
          val sessionId = deviceSessions.remove(owner) ?: return
          when (transport) {
              Transport.BINDER -> {
                  try { injector?.unregisterSession(sessionId) } catch (e: Throwable) { Logger.e("$TAG stopSession Binder", e) }
              }
              Transport.SOCKET -> {
                  synchronized(socketReceivers) {
                      val iter = socketReceivers.iterator()
                      while (iter.hasNext()) { iter.next().stop(); iter.remove() }
                  }
                  try { controlWriter?.println("STOP_ALL") } catch (_: Throwable) {}
              }
              else -> {}
          }
          Logger.d("$TAG stopSession: $sessionId")
      }

      fun stopAll() {
          try { injector?.stopAll() } catch (_: Throwable) {}
          synchronized(socketReceivers) { socketReceivers.forEach { it.stop() }; socketReceivers.clear() }
          runCatching { controlWriter?.println("STOP_ALL") }
          runCatching { controlSocket?.close() }
          deviceSessions.clear()
          transport = Transport.NONE
      }

      fun hotSwap(url: String) {
          Logger.d(Logger.INJECTION, "$TAG hotSwap: url=$url")
          try {
              when (transport) {
                  Transport.BINDER -> injector?.hotSwap(url)
                  Transport.SOCKET -> controlWriter?.println("URL $url")
                  Transport.NONE   -> {
                      synchronized(pendingUrlActions) { pendingUrlActions.addLast(PendingUrlAction(UrlActionType.HOT_SWAP, url)) }
                      connectToInjectionService()
                  }
              }
          } catch (e: Throwable) { Logger.e("$TAG hotSwap failed", e) }
      }

      fun startDecoder(url: String) {
          Logger.d(Logger.INJECTION, "$TAG startDecoder: url=$url")
          try {
              when (transport) {
                  Transport.BINDER -> injector?.startDecoder(url)
                  Transport.SOCKET -> controlWriter?.println("URL $url")
                  Transport.NONE   -> {
                      synchronized(pendingUrlActions) { pendingUrlActions.addLast(PendingUrlAction(UrlActionType.START_DECODER, url)) }
                      connectToInjectionService()
                  }
              }
          } catch (e: Throwable) { Logger.e("$TAG startDecoder failed", e) }
      }

      // ── Binder connection ─────────────────────────────────────────────────────

      private fun connectToInjectionService() {
          if (bindPending || transport != Transport.NONE) return
          val ctx = AppState.context ?: run { Logger.e("$TAG no context"); return }
          synchronized(this) {
              if (bindPending || transport != Transport.NONE) return
              bindPending = true
              try {
                  val intent = Intent(INJECTOR_ACTION).setPackage(MODULE_PKG)
                  val bound  = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                  if (!bound) {
                      bindPending = false
                      Logger.w(Logger.INJECTION, "$TAG bindService returned false — switching to Unix socket immediately")
                      connectViaUnixSocket()
                  } else {
                      Logger.d("$TAG bindService sent — starting ${BIND_TIMEOUT_MS}ms cloner-detection timer")
                      timeoutHandler.postDelayed(timeoutRunnable, BIND_TIMEOUT_MS)
                  }
              } catch (e: Throwable) {
                  bindPending = false
                  Logger.e("$TAG connectToInjectionService failed", e)
                  connectViaUnixSocket()
              }
          }
      }

      // ── Unix socket fallback ──────────────────────────────────────────────────

      private fun connectViaUnixSocket() {
          Thread({
              try {
                  Logger.i(Logger.INJECTION, "$TAG connecting via Unix socket: $CTRL_NAME")
                  val sock = LocalSocket()
                  sock.connect(LocalSocketAddress(CTRL_NAME))
                  try { sock.receiveBufferSize = 8 * 1024 * 1024 } catch (_: Throwable) {}
                  try { sock.sendBufferSize   = 8 * 1024 * 1024 } catch (_: Throwable) {}

                  controlSocket = sock
                  val writer = PrintWriter(sock.outputStream, true)
                  val reader = BufferedReader(InputStreamReader(sock.inputStream))
                  controlWriter = writer
                  controlReader = reader
                  transport     = Transport.SOCKET

                  Logger.i(Logger.INJECTION, "$TAG Unix socket connected — draining pending via socket")
                  drainPendingSocket(writer, reader)
              } catch (e: Throwable) {
                  Logger.e(Logger.INJECTION, "$TAG Unix socket connect failed: ${e.message}", e)
                  transport = Transport.NONE
              }
          }, "FG-SockConnect").also { it.isDaemon = true }.start()
      }

      // ── Delivery helpers ──────────────────────────────────────────────────────

      private fun deliverViaBinder(
          surfaces: List<Surface>, widths: IntArray, heights: IntArray,
          formats: IntArray, fps: IntArray, sessionId: String
      ) {
          try {
              injector?.registerSurfaces(surfaces, widths, heights, formats, fps, sessionId)
              Logger.d("$TAG Binder: delivered ${surfaces.size} surface(s) session=$sessionId")
          } catch (e: Throwable) {
              Logger.e("$TAG deliverViaBinder failed (session=$sessionId)", e)
          }
      }

      private fun deliverViaSocket(
          surfaces: List<Surface>, widths: IntArray, heights: IntArray,
          formats: IntArray, fps: IntArray, sessionId: String
      ) {
          val writer = controlWriter ?: run { Logger.e("$TAG deliverViaSocket: no controlWriter"); return }
          val reader = controlReader ?: run { Logger.e("$TAG deliverViaSocket: no controlReader"); return }
          startSocketReceivers(surfaces, widths, heights, formats, sessionId, writer, reader)
      }

      private fun startSocketReceivers(
          surfaces: List<Surface>, widths: IntArray, heights: IntArray,
          formats: IntArray, sessionId: String,
          writer: PrintWriter, reader: BufferedReader
      ) {
          surfaces.forEachIndexed { i, surface ->
              val w   = widths.getOrElse(i)  { 1280 }
              val h   = heights.getOrElse(i) { 720  }
              val fmt = formats.getOrElse(i) { android.graphics.ImageFormat.YUV_420_888 }

              writer.println("SURFACE $i width=$w height=$h fmt=$fmt")
              Logger.d(Logger.INJECTION, "$TAG socket→ SURFACE $i ${w}x${h} fmt=$fmt")

              // Wait for READY reply before opening data socket
              try {
                  val reply = reader.readLine()
                  Logger.d(Logger.INJECTION, "$TAG socket← $reply")
              } catch (e: Throwable) {
                  Logger.e(Logger.INJECTION, "$TAG waiting for READY $i: ${e.message}")
              }

              val receiver = SocketFrameReceiver(surface, i, w, h, fmt, writer)
              synchronized(socketReceivers) { socketReceivers.add(receiver) }
              receiver.start()
          }
      }

      private fun drainPendingBinder() {
          val inj = injector ?: return
          val deliveries = synchronized(pendingDeliveries) { pendingDeliveries.toList().also { pendingDeliveries.clear() } }
          val urlActions = synchronized(pendingUrlActions) { pendingUrlActions.toList().also { pendingUrlActions.clear() } }
          deliveries.forEach { d -> deliverViaBinder(d.surfaces, d.widths, d.heights, d.formats, d.fps, d.sessionId) }
          urlActions.forEach { action ->
              try {
                  when (action.type) {
                      UrlActionType.HOT_SWAP    -> inj.hotSwap(action.url)
                      UrlActionType.START_DECODER -> inj.startDecoder(action.url)
                  }
              } catch (e: Throwable) { Logger.e("$TAG drainPendingBinder URL action", e) }
          }
      }

      private fun drainPendingSocket(writer: PrintWriter, reader: BufferedReader) {
          val deliveries = synchronized(pendingDeliveries) { pendingDeliveries.toList().also { pendingDeliveries.clear() } }
          deliveries.forEach { d ->
              startSocketReceivers(d.surfaces, d.widths, d.heights, d.formats, d.sessionId, writer, reader)
          }
      }
  }
  