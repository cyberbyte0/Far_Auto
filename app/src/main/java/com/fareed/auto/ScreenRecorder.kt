package com.fareed.auto

import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer

/**
 * Records the screen (and optionally internal/playback audio) into a single MP4.
 *
 * MediaRecorder can't accept an external audio track, so this builds the pipeline
 * by hand:
 *   video: VirtualDisplay (shared) -> MediaCodec input Surface (H264) -> MediaMuxer
 *   audio: AudioRecord (AudioPlaybackCapture) -> PCM -> MediaCodec (AAC) -> MediaMuxer
 *
 * Android 14+ forbids calling MediaProjection.createVirtualDisplay more than once
 * per consent, so the [VirtualDisplay] is created once by the service and shared;
 * this class only attaches/detaches its encoder surface via setSurface().
 *
 * Two drain threads feed one [MediaMuxer]; the muxer only starts once every track
 * has reported its output format. Writes are serialized on [muxerLock].
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val virtualDisplay: VirtualDisplay,
    private val width: Int,
    private val height: Int,
    private val outputPath: String,
    private val withAudio: Boolean,
) {
    companion object {
        private const val TAG = "FarAuto"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val VIDEO_BITRATE = 8_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 128_000
        private const val TIMEOUT_US = 10_000L
    }

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    private val muxerLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var expectedTracks = 1
    private var addedTracks = 0

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** Sets up the pipeline and begins recording. Returns false on failure. */
    fun start(): Boolean {
        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Video encoder + virtual display ---
            val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val vEnc = MediaCodec.createEncoderByType(VIDEO_MIME)
            vEnc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            vEnc.start()
            videoEncoder = vEnc

            // Attach our encoder surface to the shared VirtualDisplay (created once at consent).
            virtualDisplay.surface = inputSurface

            // --- Audio encoder + playback capture (best-effort) ---
            if (withAudio && setupAudio()) {
                expectedTracks = 2
            }

            running = true
            videoThread = Thread { drainVideo() }.apply { start() }
            if (expectedTracks == 2) {
                audioThread = Thread { drainAudio() }.apply { start() }
            }
            Log.i(TAG, "ScreenRecorder started (audio=${expectedTracks == 2}) -> $outputPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "ScreenRecorder start failed", e)
            release()
            return false
        }
    }

    private fun setupAudio(): Boolean {
        return try {
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            }
            val aEnc = MediaCodec.createEncoderByType(AUDIO_MIME)
            aEnc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEnc.start()
            audioEncoder = aEnc

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord?.startRecording()
            true
        } catch (e: Exception) {
            // Audio is best-effort: fall back to video-only rather than failing the whole recording.
            Log.e(TAG, "Audio capture setup failed, recording video only", e)
            try { audioEncoder?.stop() } catch (_: Exception) {}
            try { audioEncoder?.release() } catch (_: Exception) {}
            audioEncoder = null
            audioRecord = null
            false
        }
    }

    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { enc.dequeueOutputBuffer(info, TIMEOUT_US) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        videoTrack = muxer!!.addTrack(enc.outputFormat)
                        addedTracks++
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(videoTrack, buf, info)
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun drainAudio() {
        val enc = audioEncoder ?: return
        val record = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        while (true) {
            // Feed PCM into the encoder.
            if (!sawInputEos) {
                val inIdx = try { enc.dequeueInputBuffer(TIMEOUT_US) } catch (e: Exception) { -1 }
                if (inIdx >= 0) {
                    val inBuf: ByteBuffer? = enc.getInputBuffer(inIdx)
                    inBuf?.clear()
                    val read = if (inBuf != null) record.read(inBuf, inBuf.capacity()) else 0
                    val ptsUs = System.nanoTime() / 1000
                    if (!running) {
                        enc.queueInputBuffer(inIdx, 0, if (read > 0) read else 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else if (read > 0) {
                        enc.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    }
                }
            }
            // Drain encoded AAC.
            val idx = try { enc.dequeueOutputBuffer(info, TIMEOUT_US) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrack = muxer!!.addTrack(enc.outputFormat)
                        addedTracks++
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(audioTrack, buf, info)
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun maybeStartMuxer() {
        if (!muxerStarted && addedTracks >= expectedTracks) {
            muxer!!.start()
            muxerStarted = true
        }
    }

    /** Stops recording and finalizes the MP4. */
    fun stop() {
        running = false
        // Video: end the input stream so its encoder flushes an EOS buffer.
        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { audioThread?.join(2000) } catch (_: Exception) {}
        try { videoThread?.join(2000) } catch (_: Exception) {}
        release()
    }

    private fun release() {
        // Detach (don't release) the shared VirtualDisplay before tearing down the
        // encoder whose surface it points at — the service reuses the display.
        try { virtualDisplay.surface = null } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop failed", e)
        }
        audioRecord = null
        videoEncoder = null
        audioEncoder = null
        muxer = null
    }
}
