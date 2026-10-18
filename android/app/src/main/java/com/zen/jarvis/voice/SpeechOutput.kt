package com.zen.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.zen.jarvis.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 端末の音声合成で読み上げる。既定は男性の日本語音声を自動で選ぶ。
 * (Googleの音声エンジンなら ja-jp-x-jac / jad、Samsungなら SMTm が男性)
 */
class SpeechOutput(context: Context, private val prefs: Prefs) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile var speaking = false
        private set

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

    data class VoiceInfo(val name: String, val label: String, val male: Boolean, val offline: Boolean)

    /** 端末にある日本語の声の一覧 (男性・オフラインを先頭に)。 */
    suspend fun japaneseVoices(): List<VoiceInfo> {
        if (!ready.await()) return emptyList()
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "ja" }
        return voices.map { v ->
            val male = isMale(v)
            val offline = !v.isNetworkConnectionRequired
            VoiceInfo(v.name, "${if (male) "男性" else "その他"}・${if (offline) "オフライン" else "要ネット"} (${v.name})", male, offline)
        }.sortedWith(compareByDescending<VoiceInfo> { it.male }.thenByDescending { it.offline }.thenBy { it.name })
    }

    private fun isMale(v: Voice): Boolean {
        val n = v.name.lowercase()
        if (Regex("jac|jad|smtm|male|#male|-m0|_m0").containsMatchIn(n)) return true
        return v.features?.any { it.lowercase().contains("male") && !it.lowercase().contains("female") } == true
    }

    private var applied = false

    private suspend fun applyVoice() {
        if (applied) return
        tts.language = Locale.JAPAN
        val want = prefs.voiceName
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { it.locale.language == "ja" }
        val chosen = voices.firstOrNull { it.name == want }
            ?: voices.filter { isMale(it) }.minByOrNull { if (it.isNetworkConnectionRequired) 1 else 0 }
        if (chosen != null) tts.voice = chosen
        // 男性の声が見つからない端末では、声を低めにしてそれらしくする
        val pitch = if (chosen != null && isMale(chosen)) prefs.voicePitch else minOf(prefs.voicePitch, 0.7f)
        tts.setPitch(pitch)
        tts.setSpeechRate(prefs.voiceRate)
        applied = true
    }

    /** 設定を変えた時に呼ぶ */
    fun reloadVoice() {
        applied = false
    }

    /** 読み上げが終わるまで待つ。 */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready.await()) return
        applyVoice()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        speaking = true
        try {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            withTimeoutOrNull(60_000L + text.length * 300L) { done.await() }
        } finally {
            speaking = pending.isNotEmpty()
        }
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        speaking = false
    }

    fun shutdown() {
        tts.shutdown()
    }
}
