package com.itsme.amkush.hooks

  import android.graphics.ImageFormat
  import android.media.Image
  import android.media.ImageWriter
  import android.net.LocalSocket
  import android.net.LocalSocketAddress
  import android.view.Surface
  import com.itsme.amkush.utils.Logger
  import java.io.DataInputStream
  import java.io.PrintWriter

  /**
   * SocketFrameReceiver — hook-side Unix socket frame consumer.
   *
   * Connects to one data socket per intercepted Surface (opened by
   * UnixSocketServer on the module side), reads [4-byte int len][raw bytes]
   * frames, and writes them into the Surface via ImageWriter.
   *
   * Pure Java/Kotlin — no NDK, no native code. Safe in Xposed hook context.
   */
  class SocketFrameReceiver(
      private val surface: Surface,
      val index: Int,
      private val width: Int,
      private val height: Int,
      private val format: Int,
      private val controlWriter: PrintWriter
  ) {
      companion object {
          private const val TAG           = "SocketFrameReceiver"
          const val CTRL_NAME             = "facegate_vcam_ctrl"
          const val DATA_NAME_FMT         = "facegate_vcam_%d"
      }

      @Volatile private var running = false
      private var thread: Thread?      = null
      private var imageWriter: ImageWriter? = null

      fun start() {
          running = true
          thread = Thread({
              try { receiveLoop() }
              catch (e: Throwable) { if (running) Logger.e(TAG, "receiveLoop[$index] crash: ${e.message}", e) }
          }, "SockFrame-$index").also {
              it.isDaemon  = true
              it.priority  = Thread.MAX_PRIORITY - 1
              it.start()
          }
      }

      fun stop() {
          running = false
          thread?.interrupt()
          runCatching { imageWriter?.close() }
          try { controlWriter.println("STOP $index") } catch (_: Throwable) {}
      }

      private fun receiveLoop() {
          imageWriter = try {
              ImageWriter.newInstance(surface, 2, format)
          } catch (e: Throwable) {
              Logger.e(TAG, "ImageWriter[$index] init failed: ${e.message}"); return
          }

          val sock = LocalSocket()
          try {
              val dataName = DATA_NAME_FMT.format(index)
              sock.connect(LocalSocketAddress(dataName))
              try { sock.receiveBufferSize = 8 * 1024 * 1024 } catch (_: Throwable) {}
              try { sock.sendBufferSize   = 8 * 1024 * 1024 } catch (_: Throwable) {}

              Logger.d(TAG, "Data socket[$index] connected")
              val stream = DataInputStream(sock.inputStream.buffered(512 * 1024))
              val iw     = imageWriter!!

              while (running) {
                  val len = try { stream.readInt() } catch (_: Throwable) { break }
                  if (len <= 0 || len > 8 * 1024 * 1024) { Logger.w(TAG, "Bad frame len=$len"); break }

                  val buf = ByteArray(len)
                  try { stream.readFully(buf) } catch (_: Throwable) { break }

                  val img: Image = try { iw.dequeueInputImage() }
                      catch (_: IllegalStateException) { continue }

                  try {
                      fillImagePlanes(img, buf)
                      iw.queueInputImage(img)
                  } catch (e: Throwable) {
                      runCatching { img.close() }
                      Logger.e(TAG, "queueInputImage[$index]: ${e.message}")
                  }
              }
          } catch (e: Throwable) {
              if (running) Logger.e(TAG, "receiveLoop[$index]: ${e.message}")
          } finally {
              runCatching { sock.close() }
              runCatching { imageWriter?.close() }
              Logger.d(TAG, "Data socket[$index] disconnected")
          }
      }

      private fun fillImagePlanes(image: Image, buf: ByteArray) {
          val planes = image.planes
          when (format) {
              ImageFormat.YUV_420_888 -> {
                  val ySize  = width * height
                  val uvW    = (width + 1) / 2
                  val uvH    = (height + 1) / 2
                  val uvSize = uvW * uvH
                  fillPlane(planes[0], buf, 0,              width, height)
                  fillPlane(planes[1], buf, ySize,          uvW,   uvH)
                  fillPlane(planes[2], buf, ySize + uvSize, uvW,   uvH)
              }
              ImageFormat.NV21, 0x15 /* NV12 */ -> {
                  val ySize    = width * height
                  val uvW      = (width + 1) / 2
                  val uvH      = (height + 1) / 2
                  val uvPlane  = planes[1]
                  val uvBuf    = uvPlane.buffer
                  val uvStride = uvPlane.rowStride
                  val uvPixStr = uvPlane.pixelStride
                  fillPlane(planes[0], buf, 0, width, height)
                  for (row in 0 until uvH) {
                      for (col in 0 until uvW) {
                          val si = ySize + row * uvW * 2 + col * 2
                          val di = row * uvStride + col * uvPixStr
                          if (si + 1 < buf.size && di + 1 < uvBuf.capacity()) {
                              uvBuf.put(di,     buf[si])
                              uvBuf.put(di + 1, buf[si + 1])
                          }
                      }
                  }
              }
              else -> planes[0].buffer.put(buf)  // RGBA, RGB565, JPEG
          }
      }

      private fun fillPlane(plane: Image.Plane, src: ByteArray, srcOffset: Int, bw: Int, bh: Int) {
          val dest      = plane.buffer
          val rowStride = plane.rowStride
          if (rowStride == bw) {
              dest.put(src, srcOffset, bw * bh)
          } else {
              val row = ByteArray(bw)
              for (r in 0 until bh) {
                  System.arraycopy(src, srcOffset + r * bw, row, 0, bw)
                  dest.position(r * rowStride)
                  dest.put(row)
              }
          }
      }
  }
  