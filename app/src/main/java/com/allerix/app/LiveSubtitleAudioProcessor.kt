package com.allerix.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Copies decoded PCM from ExoPlayer's audio sink without changing the audible output.
 * It maintains an eight-second, 16 kHz mono ring buffer and emits overlapping windows.
 */
@OptIn(UnstableApi::class)
class LiveSubtitleAudioProcessor(
    private val onWindow: (ShortArray) -> Unit
) : BaseAudioProcessor() {
    private val windowSamples = 16_000 * 12
    private val hopSamples = 16_000 * 6
    private val ring = ShortArray(windowSamples)
    private var writeIndex = 0
    private var sampleCount = 0
    private var samplesSinceWindow = 0
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var resamplePhase = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        Log.i("AllervueLiveAudio", "PCM format rate=$sampleRate channels=$channels encoding=$encoding")
        return inputAudioFormat
    }

    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        // Snapshot the exact input range first. Never pass ExoPlayer's decoder-owned
        // ByteBuffer directly to another buffer: some device codecs reuse/alias these
        // direct buffers, and ByteBuffer.put(source) can then throw on the audio thread.
        val byteCount = inputBuffer.remaining()
        val passThrough = ByteArray(byteCount)
        inputBuffer.duplicate().get(passThrough)

        // Subtitle capture is strictly best-effort. A malformed/unsupported PCM block
        // must never interrupt normal playback.
        try {
            val capture = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (sampleRate > 0 &&
                (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)
            ) {
                val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                val frameBytes = bytesPerSample * channels
                while (capture.remaining() >= frameBytes) {
                    var mono = 0f
                    repeat(channels) {
                        mono += if (encoding == C.ENCODING_PCM_FLOAT) {
                            capture.float
                        } else {
                            capture.short / 32768f
                        }
                    }
                    mono /= channels
                    resamplePhase += 16_000L
                    if (resamplePhase >= sampleRate) {
                        resamplePhase -= sampleRate.toLong()
                        append((mono.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Drop this capture block; audio pass-through below remains mandatory.
        }

        val output = replaceOutputBuffer(byteCount)
        output.put(passThrough)
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    private fun append(sample: Short) {
        ring[writeIndex] = sample
        writeIndex = (writeIndex + 1) % ring.size
        sampleCount = (sampleCount + 1).coerceAtMost(ring.size)
        samplesSinceWindow++
        if (sampleCount == ring.size && samplesSinceWindow >= hopSamples) {
            samplesSinceWindow = 0
            val snapshot = ShortArray(ring.size)
            val start = writeIndex
            val first = ring.size - start
            System.arraycopy(ring, start, snapshot, 0, first)
            if (start > 0) System.arraycopy(ring, 0, snapshot, first, start)
            // Record real input levels to distinguish silence/format issues from Whisper failures.
            var sumSquares = 0.0
            var peak = 0
            snapshot.forEach { value ->
                val magnitude = kotlin.math.abs(value.toInt())
                if (magnitude > peak) peak = magnitude
                sumSquares += value.toDouble() * value.toDouble()
            }
            val rms = kotlin.math.sqrt(sumSquares / snapshot.size) / 32768.0
            Log.i("AllervueLiveAudio", "window ready samples=${snapshot.size} rms=${"%.5f".format(rms)} peak=${peak / 32768.0}")
            // The callback must only enqueue work; it runs on ExoPlayer's audio thread.
            onWindow(snapshot)
        }
    }

    @Synchronized
    fun clearCapture() {
        writeIndex = 0
        sampleCount = 0
        samplesSinceWindow = 0
        resamplePhase = 0L
    }
}

/** Writes a captured 16 kHz mono PCM window as the WAV format expected by Whisper.cpp. */
object LiveSubtitleWav {
    fun write(samples: ShortArray, destination: File) {
        destination.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        FileOutputStream(destination).buffered().use { out ->
            fun writeInt(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
                out.write((value ushr 24) and 0xff)
            }
            fun writeShort(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeInt(dataBytes + 36)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeInt(16)
            writeShort(1)
            writeShort(1)
            writeInt(16_000)
            writeInt(32_000)
            writeShort(2)
            writeShort(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeInt(dataBytes)
            samples.forEach { writeShort(it.toInt()) }
        }
    }
}
