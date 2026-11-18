package io.github.gengar93.spendidly.backend

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records the microphone to a 16 kHz mono 16-bit WAV file. WAV is the one format that OpenAI's transcriptions,
 * Gemini and every OpenRouter audio route accept, and speech at 16 kHz is about 1.9 MB a minute.
 * The caller must hold RECORD_AUDIO.
 *
 * With [onPiece], it also cuts the audio into pieces at pauses so they can be transcribed while the speaker goes on.
 * A cut falls in the middle of a quiet stretch, so each side keeps some silence as padding and no word is split.
 * Pieces are WAV files next to [file], handed over in order on the recording thread with whether they hold speech;
 * the last one comes on [stop]. A clip with no pause gives no pieces, only the full file.
 */
class VoiceRecorder internal constructor(private val file: File, private val onPiece: ((File, Boolean) -> Unit)? = null) {
    private val rate = 16_000
    private val record: AudioRecord
    private val thread: Thread
    @Volatile private var running = true
    @Volatile private var bytes = 0L

    /** Loudness of the last chunk, 0 to 1, for a live meter. */
    @Volatile var level = 0f; private set
    /** The loudest sample so far, 0 to 1; near zero means the microphone heard nothing. */
    @Volatile var peak = 0f; private set
    /** How many pieces have been handed over. */
    @Volatile var pieces = 0; private set
    /** Set if a piece couldn't be written; the full file is then the one to use. */
    @Volatile var broken = false; private set

    // The piece being gathered: its audio, and each 100 ms frame's end offset, loudness and whether it sounded like speech.
    private val piece = ByteArrayOutputStream()
    private val frameEnds = ArrayList<Int>()
    private val frameRms = ArrayList<Float>()
    private val frameSpeech = ArrayList<Boolean>()
    private var floor = -1f
    /** Loudness of every speech frame in pieces already handed over, to judge whether the last piece holds speech. */
    private val spokenRms = ArrayList<Float>()

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
                    if (onPiece != null && !broken) {
                        try { gather(chunk, n, (sqrt(energy / n) / 32768).toFloat()) } catch (_: Exception) { broken = true }
                    }
                }
                writeHeader(out)
            }
            // The rest becomes the last piece, but only once a pause has cut one; otherwise the full file is used.
            if (onPiece != null && pieces > 0 && !broken) {
                try { emit(piece.size(), last = true) } catch (_: Exception) { broken = true }
            }
        }, "spendidly-voice").apply { start() }
    }

    val millis: Long get() = bytes * 1000 / (rate * 2)

    /** Adds a frame to the current piece and cuts it at a pause. */
    private fun gather(chunk: ByteArray, samples: Int, rms: Float) {
        // The noise floor follows quiet frames at once and loud ones slowly, so steady noise doesn't count as speech.
        floor = if (floor < 0 || rms < floor) rms else floor + (rms - floor) * 0.002f
        val speech = rms > (floor * 3).coerceIn(SPEECH_RMS, 0.03f)
        piece.write(chunk, 0, samples * 2)
        frameEnds += piece.size(); frameRms += rms; frameSpeech += speech
        val spoken = frameSpeech.count { it }
        if (spoken < MIN_SPEECH_FRAMES) return
        val length = msOf(piece.size())
        if (length < MIN_PIECE_MS) return
        // The quiet run at the end of the piece, in frames.
        var quiet = 0
        while (quiet < frameSpeech.size && !frameSpeech[frameSpeech.size - 1 - quiet]) quiet++
        // After a second of speech a shorter pause will do, so the cut at the end of a question is in flight by stop.
        val pause = when {
            length >= SOFT_LIMIT_MS -> SHORT_PAUSE_FRAMES
            spoken >= SPOKEN_FRAMES -> END_PAUSE_FRAMES
            else -> PAUSE_FRAMES
        }
        when {
            // Cut in the middle of the pause: half stays as this piece's tail, half leads the next.
            quiet >= pause -> {
                val start = frameSpeech.size - quiet
                val begin = if (start == 0) 0 else frameEnds[start - 1]
                emit(begin + (piece.size() - begin) / 2)
            }
            // No pause for too long: cut in the quietest frame of the last two seconds.
            length >= HARD_LIMIT_MS -> {
                val from = frameEnds.indexOfFirst { msOf(it) >= length - 2000 }.coerceAtLeast(1)
                val quietest = (from until frameEnds.size - 1).minByOrNull { frameRms[it] } ?: return
                emit((frameEnds[quietest - 1] + frameEnds[quietest]) / 2)
            }
        }
    }

    /**
     * Writes the first [cut] bytes of the current piece as a WAV file and hands it over; the rest starts the next piece.
     * Pieces cut at a pause always count as speech. The [last] piece counts as silent only when its loudest frame is under
     * the absolute speech level and well under this clip's own speech, so a quiet speaker's words are still sent.
     */
    private fun emit(cut: Int, last: Boolean = false) {
        val at = cut and 1.inv()
        val audio = piece.toByteArray()
        // Frames ending after the cut move to the next piece, the one the cut falls in included.
        val next = frameEnds.indexOfFirst { it > at }.let { if (it < 0) frameEnds.size else it }
        val loudest = frameRms.subList(0, next).maxOrNull() ?: 0f
        val median = spokenRms.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val speech = !last || loudest >= SPEECH_RMS || loudest >= median * TAIL_SHARE
        for (i in 0 until next) if (frameSpeech[i]) spokenRms += frameRms[i]
        val out = File(file.parentFile, file.nameWithoutExtension + "-$pieces.wav")
        RandomAccessFile(out, "rw").use { f -> f.setLength(0); f.write(header(at)); f.write(audio, 0, at) }
        pieces++
        piece.reset(); piece.write(audio, at, audio.size - at)
        frameEnds.subList(0, next).clear(); frameRms.subList(0, next).clear(); frameSpeech.subList(0, next).clear()
        for (i in frameEnds.indices) frameEnds[i] -= at
        onPiece?.invoke(out, speech)
    }

    private fun msOf(byteCount: Int): Long = byteCount * 1000L / (rate * 2)

    /** Stops and returns the finished WAV file. Blocking until the last chunk and piece are written. */
    fun stop(): File {
        finish()
        return file
    }

    /** Stops and deletes the recording and any pieces. */
    fun cancel() {
        finish()
        file.delete()
        file.parentFile?.listFiles { f -> f.name.startsWith(file.nameWithoutExtension + "-") }?.forEach { it.delete() }
    }

    private fun finish() {
        if (!running) return
        running = false
        runCatching { record.stop() }
        thread.join(2000)
        record.release()
    }

    private fun writeHeader(out: RandomAccessFile) {
        out.seek(0)
        out.write(header(bytes.toInt()))
    }

    private fun header(data: Int): ByteArray = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data)
    }.array()

    private companion object {
        /** Loudness (RMS, 0 to 1) below which a frame is never speech. */
        const val SPEECH_RMS = 0.004f
        /** A last piece quieter than this share of the clip's median speech loudness (and [SPEECH_RMS]) is silence. */
        const val TAIL_SHARE = 0.15f
        /** Frames of speech a piece needs before it can be cut. */
        const val MIN_SPEECH_FRAMES = 2
        /** Pieces shorter than this aren't cut, so each keeps some context. */
        const val MIN_PIECE_MS = 1000L
        /** A quiet run this long is a pause (frames of 100 ms); after [SPOKEN_FRAMES] of speech, or past [SOFT_LIMIT_MS], a shorter one will do. */
        const val PAUSE_FRAMES = 6
        const val SPOKEN_FRAMES = 10
        const val END_PAUSE_FRAMES = 4
        const val SHORT_PAUSE_FRAMES = 2
        const val SOFT_LIMIT_MS = 8000L
        /** With no pause by now, the piece is cut at its quietest point. */
        const val HARD_LIMIT_MS = 10_000L
    }
}
