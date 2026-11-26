package com.allerix.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes a selected media URI with Android's platform codec and writes
 * 16 kHz, mono, signed 16-bit PCM WAV for Whisper.
 */
object MediaAudioDecoder {
    fun decodeToWhisperWav(context: Context, uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val writer = Pcm16WavWriter(destination)
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = format
                    break
                }
            }
            require(audioTrack >= 0 && audioFormat != null) { "No audio track was found in this video." }
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Unsupported audio format.")
            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            var inputDone = false
            var outputDone = false
            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingMonoResampler(sampleRate, writer)

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Audio decoder input buffer is unavailable.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val info = MediaCodec.BufferInfo()
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        resampler = StreamingMonoResampler(sampleRate, writer)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            consumePcm(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding, resampler)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            writer.finish()
            require(writer.sampleCount > 0L) { "The selected video did not contain decodable audio." }
        } catch (error: Throwable) {
            writer.closeAndDelete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            writer.close()
        }
    }

    private fun consumePcm(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        encoding: Int,
        resampler: StreamingMonoResampler
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> error("Unsupported decoded PCM format: $encoding")
        }
        val frameBytes = bytesPerSample * channels.coerceAtLeast(1)
        while (buffer.remaining() >= frameBytes) {
            var sum = 0f
            repeat(channels.coerceAtLeast(1)) {
                val value = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    else -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                }
                sum += value
            }
            resampler.accept(sum / channels.coerceAtLeast(1))
        }
    }
}

private class StreamingMonoResampler(
    private val inputRate: Int,
    private val writer: Pcm16WavWriter
) {
    private val step = inputRate.toDouble() / 16_000.0
    private var inputIndex = 0L
    private var nextOutput = 0.0
    private var previous = 0f
    private var hasPrevious = false

    fun accept(sample: Float) {
        if (!hasPrevious) {
            writer.writeSample(sample)
            previous = sample
            hasPrevious = true
            return
        }
        inputIndex++
        while (nextOutput <= inputIndex.toDouble()) {
            val fraction = (nextOutput - (inputIndex - 1).toDouble()).coerceIn(0.0, 1.0).toFloat()
            writer.writeSample(previous + (sample - previous) * fraction)
            nextOutput += step
        }
        previous = sample
    }
}

private class Pcm16WavWriter(private val file: File) {
    private val output = RandomAccessFile(file, "rw")
    var sampleCount: Long = 0L
        private set
    private var finished = false

    init {
        output.setLength(0)
        output.write(ByteArray(44))
    }

    fun writeSample(value: Float) {
        val clipped = value.coerceIn(-1f, 1f)
        val pcm = (clipped * if (clipped < 0f) 32768f else 32767f).roundToInt()
        output.write(pcm and 0xff)
        output.write((pcm shr 8) and 0xff)
        sampleCount++
    }

    fun finish() {
        if (finished) return
        val dataBytes = (sampleCount * 2L).coerceAtMost(Int.MAX_VALUE.toLong() - 36L).toInt()
        output.seek(0)
        output.writeBytes("RIFF")
        writeInt(dataBytes + 36)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(16_000)
        writeInt(32_000)
        writeShort(2)
        writeShort(16)
        output.writeBytes("data")
        writeInt(dataBytes)
        finished = true
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
    }

    private fun writeInt(value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
        output.write((value shr 16) and 0xff)
        output.write((value shr 24) and 0xff)
    }

    fun closeAndDelete() {
        runCatching { output.close() }
        file.delete()
    }

    fun close() {
        runCatching { output.close() }
    }
}
