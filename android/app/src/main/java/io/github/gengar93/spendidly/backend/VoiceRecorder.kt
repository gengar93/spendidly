package io.github.gengar93.spendidly.backend

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records the microphone to a 16 kHz mono 16-bit WAV file. WAV is the one format that OpenAI's transcriptions,
 * Gemini and every OpenRouter audio route accept, and speech at 16 kHz is about 1.9 MB a minute.
 * The caller must hold RECORD_AUDIO.
 *
 * With [onPause], it listens for a pause after speech: once there has been about a second of speech and then
 * [PAUSE_FRAMES] of quiet, it calls [onPause] on the recording thread with how many audio bytes the file holds so far,
 * so the caller can start transcribing that much. It calls again only after new sound that may be speech.
 * [lastSound] says where the last such sound ended, so the caller can tell whether anything was said since.
 */
class VoiceRecorder internal constructor(private val file: File, private val onPause: ((Long) -> Unit)? = null) {
    private val record: AudioRecord
    private val thread: Thread
    @Volatile private var running = true
    @Volatile private var bytes = 0L

    /** Loudness of the last chunk, 0 to 1, for a live meter. */
    @Volatile var level = 0f; private set
    /** The loudest sample so far, 0 to 1; near zero means the microphone heard nothing. */
    @Volatile var peak = 0f; private set
    /** Audio bytes up to the end of the last 100 ms frame that may have held speech. */
    @Volatile var lastSound = 0L; private set

    // Speech detection, on the recording thread only.
    private var floor = -1f
    private var spokenFrames = 0
    private var quietFrames = 0
    private var pausedAt = -1L

    init {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("Microphone unavailable")
        @SuppressLint("MissingPermission")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
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
            val buffer = ShortArray(RATE / 10)
            val chunk = ByteArray(buffer.size * 2)
            out.use {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var loudest = 0
                    var energy = 0.0
                    for (i in 0 until n) {
                        val s = buffer[i].toInt()
                        loudest = maxOf(loudest, abs(s))
                        energy += s.toDouble() * s
                        chunk[i * 2] = (s and 0xff).toByte()
                        chunk[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                    }
                    out.write(chunk, 0, n * 2)
                    bytes += n * 2
                    level = loudest / 32768f
                    peak = maxOf(peak, level)
                    listen((sqrt(energy / n) / 32768).toFloat())
                }
                out.seek(0)
                out.write(wavHeader(bytes))
            }
        }, "spendidly-voice").apply { start() }
    }

    val millis: Long get() = bytes * 1000 / (RATE * 2)

    /**
     * Judges one frame against an adaptive noise floor. The floor drops quickly in quiet frames and rises slowly,
     * so steady room noise doesn't count as speech while quiet speech still does. A frame "may be speech" at a lower
     * bar than it needs to count toward the second of speech, so a doubtful sound after a pause means resending the clip.
     */
    private fun listen(rms: Float) {
        // Digital silence while the microphone starts would pin the floor at zero.
        if (rms > 0.00001f) floor = when {
            floor < 0 -> rms
            rms < floor -> floor + (rms - floor) * 0.3f
            else -> floor + (rms - floor) * 0.002f
        }
        val base = floor.coerceAtLeast(0f)
        if (rms > (base * 3).coerceIn(SPEECH_RMS, 0.03f)) spokenFrames++
        if (rms > (base * 2).coerceIn(SOUND_RMS, 0.02f)) { lastSound = bytes; quietFrames = 0 } else quietFrames++
        val pause = onPause ?: return
        if (spokenFrames >= SPOKEN_FRAMES && quietFrames >= PAUSE_FRAMES && lastSound > pausedAt) {
            pausedAt = bytes
            runCatching { pause(bytes) }
        }
    }

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

    internal companion object {
        const val RATE = 16_000
        /** Loudness (RMS, 0 to 1) below which a frame never counts toward the second of speech. */
        const val SPEECH_RMS = 0.004f
        /** Loudness below which a frame is never taken for possible speech. */
        const val SOUND_RMS = 0.003f
        /** Frames (100 ms each) of speech before a pause is worth acting on. */
        const val SPOKEN_FRAMES = 10
        /** Quiet frames in a row that make a pause. */
        const val PAUSE_FRAMES = 7

        /** A 44-byte WAV header for [data] bytes of 16 kHz mono 16-bit audio. */
        fun wavHeader(data: Long): ByteArray = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
    }
}
