package io.github.gengar93.spendidly.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One spoken question. While it records, each piece a pause cuts off is sent to [model]'s provider in the background,
 * so on stop only the last short piece is left. A piece that fails twice, or a clip with no pause, falls back to
 * sending the whole clip after stop. Audio goes only to that provider; the clip and its pieces stay in the app's
 * cache and are deleted when the session ends.
 */
class VoiceSession internal constructor(private val model: VoiceModel, private val file: File, setup: suspend () -> Pair<String, List<String>>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = VoiceCalls()
    private val pieces = mutableListOf<Deferred<String?>>()
    private var cancelled = false
    /** The API key and spelling hints, fetched once while the speaker starts talking. */
    private val setup = scope.async { setup() }

    val recorder: VoiceRecorder = try {
        VoiceRecorder(file, ::onPiece)
    } catch (error: Exception) {
        scope.cancel()
        throw error
    }

    /** Whether the transcript came from the whole clip rather than from pieces; for timing. */
    var whole = true; private set

    /**
     * On the recording thread: starts transcribing a finished piece. A last piece with no speech, or too short to hold
     * a word, is dropped, so stop only waits for pieces already in flight. Pieces cut mid-recording always hold speech.
     */
    private fun onPiece(piece: File, speech: Boolean) {
        synchronized(pieces) {
            if (cancelled || !speech || piece.length() < 44 + MIN_PIECE_BYTES) piece.delete()
            else pieces.add(scope.async { try { attempt(piece) } finally { piece.delete() } })
        }
    }

    /** Transcribes a piece, retrying once; null if both tries fail. */
    private suspend fun attempt(piece: File): String? {
        repeat(2) {
            try {
                val (key, hints) = setup.await()
                return transcribe(model, key, piece, hints, calls, allowEmpty = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Stops recording. Blocking until the last piece is handed over. */
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) { recorder.stop() }

    /**
     * Joins the pieces' transcripts in order, after transcribing whatever is left; else transcribes the whole clip.
     * Throws with a short message on failure. The audio is deleted whatever happens.
     */
    suspend fun transcript(): String = withContext(Dispatchers.IO) {
        try {
            val parts = synchronized(pieces) { pieces.toList() }
            val texts = if (parts.isEmpty() || recorder.broken) null else parts.awaitAll()
            if (texts != null && texts.all { it != null }) {
                whole = false
                texts.joinToString(" ").trim().ifEmpty { noWords() }
            } else {
                val (key, hints) = setup.await()
                transcribe(model, key, file, hints, calls)
            }
        } finally {
            this@VoiceSession.cancel()
        }
    }

    /** Stops everything: recording, requests in flight and pieces to come. Deletes the clip and its pieces. */
    fun cancel() {
        synchronized(pieces) { cancelled = true }
        calls.cancel()
        scope.cancel()
        recorder.cancel()
    }

    private companion object {
        /** 300 ms of 16 kHz 16-bit audio. */
        const val MIN_PIECE_BYTES = 9600
    }
}
