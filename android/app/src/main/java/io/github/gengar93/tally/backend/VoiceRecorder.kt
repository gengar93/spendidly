package io.github.gengar93.tally.backend

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Records the microphone to a 16 kHz mono 16-bit WAV file. WAV is the one format that OpenAI's transcriptions,
 * Gemini and every OpenRouter audio route accept, and speech at 16 kHz is about 1.9 MB a minute.
 * The caller must hold RECORD_AUDIO.
 */
class VoiceRecorder internal constructor(private val file: File) {
    private val rate = 16_000
    private val record: AudioRecord
    private val thread: Thread
    @Volatile private var running = true
    @Volatile private var bytes = 0L

    /** Loudness of the last chunk, 0 to 1, for a live meter. */
    @Volatile var level = 0f; private set
    /** The loudest sample so far, 0 to 1; near zero means the microphone heard nothing. */
    @Volatile var peak = 0f; private set

    init {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("Microphone unavailable")
        @SuppressLint("MissingPermission")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Microphone unavailable") }
        record = r
        val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("Microphone busy")
        } catch (error: Exception) {
            out.close(); record.release(); file.delete()
            throw error
        }
        thread = Thread({
            val buffer = ShortArray(rate / 10)
            val chunk = ByteArray(buffer.size * 2)
            out.use {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var loudest = 0
                    for (i in 0 until n) {
                        val s = buffer[i].toInt()
                        loudest = maxOf(loudest, abs(s))
                        chunk[i * 2] = (s and 0xff).toByte()
                        chunk[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                    }
                    out.write(chunk, 0, n * 2)
                    bytes += n * 2
                    level = loudest / 32768f
                    peak = maxOf(peak, level)
                }
                writeHeader(out)
            }
        }, "tally-voice").apply { start() }
    }

    val millis: Long get() = bytes * 1000 / (rate * 2)

    /** Stops and returns the finished WAV file. Blocking until the last chunk is written. */
    fun stop(): File {
        finish()
        return file
    }

    /** Stops and deletes the recording. */
    fun cancel() {
        finish()
        file.delete()
    }

    private fun finish() {
        if (!running) return
        running = false
        runCatching { record.stop() }
        thread.join(2000)
        record.release()
    }

    private fun writeHeader(out: RandomAccessFile) {
        val data = bytes.toInt()
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }
        out.seek(0)
        out.write(header.array())
    }
}
