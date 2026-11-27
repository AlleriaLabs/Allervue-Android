package com.allerix.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import android.util.Log
import android.os.SystemClock

/**
 * Copies decoded PCM from ExoPlayer's audio sink without changing the audible output.
 * It maintains a six-second, 16 kHz mono ring buffer and emits frequent overlapping windows.
 */
@OptIn(UnstableApi::class)
class LiveSubtitleAudioProcessor(
    private val onDiagnostic: (String) -> Unit = {},
    private val onChunk: (LiveSubtitleAudioChunk) -> Unit = {},
    private val captureWindows: Boolean = true,
    private val onWindow: (LiveSubtitleAudioWindow) -> Unit
) : BaseAudioProcessor() {
    private val windowSamples = 16_000 * 6
    private val hopSamples = 16_000 * 2
    private val ring = if (captureWindows) ShortArray(windowSamples) else ShortArray(0)
    private val streamingChunkSamples = ShortArray(1_600) // 100 ms at 16 kHz
    private var streamingChunkCount = 0
    private var writeIndex = 0
    private var sampleCount = 0
    private var samplesSinceWindow = 0
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var resamplePhase = 0L
    private var resampleSum = 0f
    private var resampleCount = 0
    private var inputBlockCount = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        Log.i("AllervueLiveAudio", "PCM format rate=$sampleRate channels=$channels encoding=$encoding")
        onDiagnostic("LIVE_AUDIO_FORMAT rate=$sampleRate channels=$channels encoding=$encoding")
        return inputAudioFormat
    }

    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        // Snapshot the exact input range first. Never pass ExoPlayer's decoder-owned
        // ByteBuffer directly to another buffer: some device codecs reuse/alias these
        // direct buffers, and ByteBuffer.put(source) can then throw on the audio thread.
        val byteCount = inputBuffer.remaining()
        inputBlockCount++
        if (inputBlockCount == 1L) {
            onDiagnostic("LIVE_AUDIO_INPUT firstBlockBytes=$byteCount rate=$sampleRate channels=$channels encoding=$encoding")
        }
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
                    // Box-filter before downsampling. Picking every third 48 kHz sample
                    // aliases high frequencies and can hurt phoneme recognition.
                    resampleSum += mono
                    resampleCount++
                    resamplePhase += 16_000L
                    if (resamplePhase >= sampleRate) {
                        val filtered = (resampleSum / resampleCount.coerceAtLeast(1)).coerceIn(-1f, 1f)
                        while (resamplePhase >= sampleRate) {
                            append((filtered * 32767f).toInt().toShort())
                            resamplePhase -= sampleRate.toLong()
                        }
                        resampleSum = 0f
                        resampleCount = 0
                    }
                }
            }
        } catch (error: RuntimeException) {
            // Drop this capture block; audio pass-through below remains mandatory.
            if (inputBlockCount == 1L || inputBlockCount % 250L == 0L) {
                onDiagnostic("LIVE_AUDIO_CAPTURE_ERROR block=$inputBlockCount type=${error.javaClass.simpleName} message=${error.message}")
            }
        }

        val output = replaceOutputBuffer(byteCount)
        output.put(passThrough)
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    private fun append(sample: Short) {
        streamingChunkSamples[streamingChunkCount++] = sample
        if (streamingChunkCount == streamingChunkSamples.size) {
            val chunk = streamingChunkSamples.copyOf()
            streamingChunkCount = 0
            // Keep this callback lightweight; the player only posts it to its worker thread.
            onChunk(LiveSubtitleAudioChunk(chunk, SystemClock.elapsedRealtime()))
        }
        if (!captureWindows) return
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
            onDiagnostic("LIVE_AUDIO_WINDOW samples=${snapshot.size} duration=${snapshot.size / 16000.0}s rms=${"%.6f".format(Locale.US, rms)} peak=${"%.6f".format(Locale.US, peak / 32768.0)}")
            // The callback must only enqueue work; it runs on ExoPlayer's audio thread.
            onWindow(LiveSubtitleAudioWindow(snapshot, SystemClock.elapsedRealtime()))
        }
    }

    @Synchronized
    fun clearCapture() {
        writeIndex = 0
        sampleCount = 0
        samplesSinceWindow = 0
        streamingChunkCount = 0
        resamplePhase = 0L
        resampleSum = 0f
        resampleCount = 0
    }
}

/** Writes a captured 16 kHz mono PCM window as the WAV format expected by Whisper.cpp. */
object LiveSubtitleWav {
    /** Read back the actual file header before JNI so malformed WAV writes are visible in diagnostics. */
    fun diagnosticSummary(file: File): String = runCatching {
        val header = ByteArray(44)
        java.io.RandomAccessFile(file, "r").use { input -> input.readFully(header) }
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        val format = data.getShort(20).toInt() and 0xffff
        val channels = data.getShort(22).toInt() and 0xffff
        val rate = data.getInt(24)
        val bits = data.getShort(34).toInt() and 0xffff
        val dataBytes = data.getInt(40)
        "fileBytes=${file.length()} riff=$riff wave=$wave format=$format channels=$channels rate=$rate bits=$bits dataBytes=$dataBytes expectedFileBytes=${44L + dataBytes}"
    }.getOrElse { "wavHeaderReadFailed=${it.javaClass.simpleName}:${it.message}" }

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
