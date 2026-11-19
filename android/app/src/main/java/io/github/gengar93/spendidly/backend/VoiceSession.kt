package io.github.gengar93.spendidly.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One spoken question, always transcribed as one whole clip. When the speaker pauses after some speech, the recording
 * so far is sent to [model]'s provider in the background (a head start). If nothing that may be speech follows before
 * stop, that transcript is used; otherwise, or if it failed, the whole clip is sent after stop. Only one head start is
 * in flight at a time: a later pause replaces it. Audio goes only where [transcribe] sends it; the clip and any head-start copy
 * stay in the app's cache and are deleted when the session ends.
 */
class VoiceSession internal constructor(private val model: VoiceModel, private val file: File, setup: suspend () -> Pair<String, List<String>>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = VoiceCalls()
    private val lock = Any()
    private var cancelled = false
    private var head: Head? = null
    private var heads = 0
    /** The API key and spelling hints, fetched once while the speaker starts talking. */
    private val setup = scope.async { setup() }

    val recorder: VoiceRecorder = try {
        VoiceRecorder(file, ::onPause)
    } catch (error: Exception) {
        scope.cancel()
        throw error
    }

    /** How the head start went, for the timing log. */
    var headStart = "no head start"; private set

    /** A transcript of the first [bytes] of audio, under way. */
    private class Head(val bytes: Long, val calls: VoiceCalls, val text: Deferred<String?>) {
        fun drop() { calls.cancel(); text.cancel() }
    }

    /** On the recording thread: drops the previous head start and sends the recording so far. */
    private fun onPause(bytes: Long) {
        synchronized(lock) {
            if (cancelled) return
            head?.drop()
            val copy = File(file.parentFile, file.nameWithoutExtension + "-head-${heads++}.wav")
            val headCalls = VoiceCalls()
            head = Head(bytes, headCalls, scope.async {
                try {
                    copyStart(bytes, copy)
                    val (key, hints) = setup.await()
                    transcribe(model, key, copy, hints, headCalls, allowEmpty = true).ifEmpty { null }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    copy.delete()
                }
            })
        }
    }

    /** Writes the first [bytes] of the clip's audio as a WAV file. That part of the clip no longer changes. */
    private fun copyStart(bytes: Long, to: File) {
        val audio = ByteArray(bytes.toInt())
        RandomAccessFile(file, "r").use { it.seek(44); it.readFully(audio) }
        RandomAccessFile(to, "rw").use { it.setLength(0); it.write(VoiceRecorder.wavHeader(bytes)); it.write(audio) }
    }

    /** Stops recording. Blocking until the last chunk is written. */
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) { recorder.stop() }

    /**
     * The head start's transcript if nothing that may be speech came after it, waiting for it if needed; else the
     * whole clip's. Throws with a short message on failure. The audio is deleted whatever happens.
     */
    suspend fun transcript(): String = withContext(Dispatchers.IO) {
        try {
            val start = synchronized(lock) { head }
            val text = when {
                start == null -> null
                recorder.lastSound > start.bytes -> { start.drop(); headStart = "head start dropped, speech after"; null }
                else -> start.text.await().also { headStart = if (it == null) "head start failed" else "head start used" }
            }
            text ?: setup.await().let { (key, hints) -> transcribe(model, key, file, hints, calls) }
        } finally {
            this@VoiceSession.cancel()
        }
    }

    /** Stops everything: recording and requests in flight. Deletes the clip and any head-start copy. */
    fun cancel() {
        val start = synchronized(lock) { cancelled = true; head }
        start?.drop()
        calls.cancel()
        scope.cancel()
        recorder.cancel()
        file.parentFile?.listFiles { f -> f.name.startsWith(file.nameWithoutExtension + "-head-") }?.forEach { it.delete() }
    }
}
