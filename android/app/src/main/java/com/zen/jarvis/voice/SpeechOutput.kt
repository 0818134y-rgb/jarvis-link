package com.zen.jarvis.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 端末標準の音声合成(日本語)で読み上げる。JARVISらしく少し低め・速め。 */
class SpeechOutput(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
    }

    /** 読み上げが終わるまで待つ。 */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready.await()) return
        tts.language = Locale.JAPAN
        tts.setPitch(0.9f)
        tts.setSpeechRate(1.1f)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(60_000L + text.length * 300L) { done.await() }
    }

    fun stop() = tts.stop()

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun shutdown() {
        tts.shutdown()
        tone?.release()
    }
}
