package com.itsme.amkush.ffmpeg

import androidx.annotation.Keep
import java.nio.ByteBuffer

/**
 * JNI bridge to ffmpeg_decoder.so
 *
 * Wraps a native AVFormatContext + AVCodecContext decode loop.
 * Each open() call starts a background native thread that reads/decodes
 * the stream and calls FrameCallback.onFrameAvailable() for every I420 frame
 * and FrameCallback.onAudioFrame() for every decoded audio packet.
 *
 * CRITICAL FIX: The JNI layer now resolves method IDs from the FrameCallback
 * INTERFACE class (via FindClass), NOT from anonymous implementation classes.
 * This means ANY object implementing FrameCallback works — no more JNI class
 * mismatch crashes when using different callback implementations across your app.
 *
 * Threading:
 *   - open() / close() / hotSwap() — caller's thread (Binder or main thread)
 *   - FrameCallback methods        — native decode thread (NOT the UI thread)
 */
@Keep
object FFmpegDecoder {

    /**
     * Callback fired on the native decode thread for each decoded video frame
     * and each resampled audio chunk.
     *
     * IMPORTANT: Do NOT retain references to ByteBuffers beyond the callback
     * call — they are DirectByteBuffers backed by native AVFrame memory that
     * is reused immediately after the callback returns. Copy bytes immediately
     * if you need to hold them.
     *
     * Video (onFrameAvailable):
     *   Format: tightly-packed I420 (no padding between rows).
     *     Y plane: width x height bytes,       stride = width
     *     U plane: uvWidth x uvHeight bytes,   stride = uvWidth  (uvWidth = (width+1)/2)
     *     V plane: uvWidth x uvHeight bytes,   stride = uvWidth
     *
     * Audio (onAudioFrame):
     *   Format: PCM S16LE (signed 16-bit little-endian), interleaved channels.
     *     sampleRate: samples per second (typically 44100 or 48000)
     *     channels:   number of channels (1=mono, 2=stereo)
     *     samples:    number of samples per channel in this buffer
     *   Buffer size = samples x channels x 2 bytes (S16 = 2 bytes/sample)
     */
    @Keep
    interface FrameCallback {
        fun onFrameAvailable(
            yBuf: ByteBuffer,
            uBuf: ByteBuffer,
            vBuf: ByteBuffer,
            width: Int,
            height: Int,
            ptsUs: Long
        )

        /**
         * Called when decoded audio PCM data is available.
         * Data format: interleaved S16 PCM (16-bit signed little-endian).
         * Sample rate matches the source stream (typically 44100 or 48000 Hz).
         */
        fun onAudioFrame(
            pcmBuf: ByteBuffer,
            sampleRate: Int,
            channels: Int,
            samples: Int
        ) {
            // Default empty implementation so existing callers don't break
        }

        /**
         * Called by C++ with the decoded audio PTS in microseconds.
         * Used by StreamPreviewDialog as an A/V sync master clock.
         *
         * Default implementation delegates to [onAudioFrame] so that all other
         * FrameCallback implementations (InjectionService, etc.) continue to work
         * without any changes — they simply never override this method.
         *
         * IMPORTANT: Do NOT change the JVM signature — C++ resolves this method by
         * name + descriptor "(Ljava/nio/ByteBuffer;IIIJ)V" in JNI_OnLoad.
         */
        fun onAudioFrameWithPts(
            pcmBuf: ByteBuffer,
            sampleRate: Int,
            channels: Int,
            samples: Int,
            ptsUs: Long
        ) {
            onAudioFrame(pcmBuf, sampleRate, channels, samples)
        }

        fun onError(code: Int, msg: String)
        fun onEof()
    }

    init {
        System.loadLibrary("ffmpeg_decoder")
        android.util.Log.d("DECODER", "FFmpegDecoder: native lib loaded (v2 with audio)")
    }

    /**
     * Open a stream and start the native decode loop.
     *
     * @param url   RTSP / RTSPS / HLS / HTTP(S) / RTMP / SRT / local file URL
     * @param cb    Frame callback — ANY implementation of FrameCallback works
     *              thanks to interface-based JNI method resolution.
     * @return Opaque native handle (pointer to DecoderCtx), or 0 on failure.
     */
    @JvmStatic
    external fun open(url: String, cb: FrameCallback): Long

    /**
     * Stop the decode loop, join the decode thread, and free all native resources.
     * The handle must not be used after this call.
     */
    @JvmStatic
    external fun close(handle: Long)

    /**
     * Signal the decode thread to close the current stream and reopen with [url].
     * Returns true if the signal was posted; false if the handle is invalid.
     * The actual swap happens asynchronously on the decode thread (~200 ms).
     */
    @JvmStatic
    external fun hotSwap(handle: Long, url: String): Boolean

    /** Returns the decoded stream width (0 if handle invalid or stream not opened). */
    @JvmStatic
    external fun getWidth(handle: Long): Int

    /** Returns the decoded stream height (0 if handle invalid or stream not opened). */
    @JvmStatic
    external fun getHeight(handle: Long): Int

    /**
     * Check if the decoder is currently using hardware acceleration (MediaCodec).
     * Returns true if using hardware decoder, false if software or handle invalid.
     */
    @JvmStatic
    external fun isUsingHardwareDecoder(handle: Long): Boolean
}
