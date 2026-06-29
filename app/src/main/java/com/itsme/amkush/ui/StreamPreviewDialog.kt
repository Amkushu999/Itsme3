package com.itsme.amkush.ui

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itsme.amkush.ffmpeg.FFmpegDecoder
import com.itsme.amkush.libyuv.LibYuv
import java.nio.ByteBuffer

private val Violet  = Color(0xFF6C63FF)
private val BgDark  = Color(0xFF0D0D18)
private val Border  = Color(0x1AFFFFFF)
private val TextMid = Color(0x88FFFFFF)

@Composable
fun StreamPreviewDialog(url: String, onDismiss: () -> Unit) {
    var buffering by remember { mutableStateOf(true) }
    var error     by remember { mutableStateOf<String?>(null) }
    var liveTag   by remember { mutableStateOf(false) }

    val holderRef = remember { mutableStateOf<SurfaceHolder?>(null) }
    val decoderHandle = remember { mutableStateOf(0L) }

    DisposableEffect(url) {
        val frameCallback = object : FFmpegDecoder.FrameCallback {
            override fun onFrameAvailable(
                yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
                width: Int, height: Int, ptsUs: Long
            ) {
                // 1. Allocate Direct ByteBuffer for RGBA output
                val rgbaSize = width * height * 4
                val rgbaBuf = ByteBuffer.allocateDirect(rgbaSize)

                // 2. Convert I420 to RGBA using your custom LibYuv JNI wrapper (Extremely fast)
                val ret = LibYuv.convertInto(
                    srcY = yBuf, srcU = uBuf, srcV = vBuf,
                    srcW = width, srcH = height,
                    srcStrideY = width, srcStrideU = (width + 1) / 2, srcStrideV = (width + 1) / 2,
                    dstW = width, dstH = height,
                    dstFmt = android.graphics.PixelFormat.RGBA_8888,
                    dst = rgbaBuf
                )

                if (ret != 0) {
                    error = "LibYuv conversion failed: $ret"
                    return
                }

                // 3. Create Bitmap and copy pixels
                rgbaBuf.rewind()
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(rgbaBuf)

                // 4. Draw to SurfaceView
                val holder = holderRef.value
                if (holder != null && holder.surface.isValid) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            val dstRect = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                            canvas.drawBitmap(bmp, null, dstRect, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                bmp.recycle()

                if (!liveTag) {
                    buffering = false
                    liveTag = true
                }
            }

            override fun onError(code: Int, msg: String) {
                error = "Decoder error $code: $msg"
            }

            override fun onEof() {
                // Stream ended
            }
        }

        // Start custom FFmpeg decoder
        decoderHandle.value = FFmpegDecoder.open(url, frameCallback)
        if (decoderHandle.value == 0L) {
            error = "Failed to open stream"
        }

        onDispose {
            if (decoderHandle.value != 0L) {
                FFmpegDecoder.close(decoderHandle.value)
                decoderHandle.value = 0L
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp))
                .background(BgDark)
                .border(1.dp, Border, RoundedCornerShape(20.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Stream Preview", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(url.take(40) + if (url.length > 40) "…" else "", color = TextMid, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0x1AFFFFFF)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("✕", color = TextMid, fontSize = 12.sp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder)  { holderRef.value = h }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { holderRef.value = h }
                                    override fun surfaceDestroyed(h: SurfaceHolder) { holderRef.value = null }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (buffering && error == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = Violet, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                            Text("Connecting via Custom FFmpeg…", color = TextMid, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⚠", fontSize = 28.sp)
                            Text(error!!, color = Color(0xFFFF4D6D), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFF4D6D)).clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("Close", color = Color(0xFFFF4D6D), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x1AFFFFFF)).border(1.dp, Border, RoundedCornerShape(10.dp)).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Stop & Close", color = TextMid, fontSize = 12.sp)
                    }

                    val statusColor = when {
                        error != null -> Color(0xFFFF4D6D)
                        buffering     -> Color(0xFFFACC15)
                        else          -> Color(0xFF4ADE80)
                    }
                    val statusText = when {
                        error != null -> "ERROR"
                        buffering     -> "BUFFERING"
                        else          -> "LIVE"
                    }
                    Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(statusColor.copy(0.15f)).border(1.dp, statusColor.copy(0.3f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(statusText, color = statusColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
