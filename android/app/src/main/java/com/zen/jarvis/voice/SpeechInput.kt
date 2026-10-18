package com.zen.jarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 端末の音声認識 (高精度)。呼びかけの後の指示や、ボタンを押した時に使う。
 * - 開始音・終了音(ビープ)が鳴らないよう、聞いている間だけ通知音などを消音する
 * - 言い終わる前に切れないよう無音の判定を長めにし、確定結果が空でも途中結果を使う
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 一言聞き取って文字列を返す。何も聞こえなければ null。 */
    suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        val muted = muteBeeps()
        try {
            var out = listenInternal()
            val err = out.error
            if (err != null && err in RETRY_WITH_CLOUD && useOnDevice) {
                Log.i(TAG, "on-device recognizer unavailable ($err), falling back")
                useOnDevice = false
                recognizer?.destroy()
                recognizer = null
                out = listenInternal()
            }
            out.text
        } finally {
            delay(350)  // 終了音が鳴り終わるまで待ってから戻す
            unmute(muted)
        }
    }

    // ---------- ビープ音対策 ----------

    private val beepStreams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)

    private fun muteBeeps(): List<Int> {
        val done = mutableListOf<Int>()
        for (s in beepStreams) {
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    done += s
                }
            } catch (_: SecurityException) {
                // マナーモード中などは変更できないことがある
            }
        }
        return done
    }

    private fun unmute(streams: List<Int>) {
        for (s in streams) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
    }

    // ---------- 認識 ----------

    private data class Outcome(val text: String?, val error: Int?)

    private fun obtain(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            useOnDevice = false
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        return r
    }

    private suspend fun listenInternal(): Outcome = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !useOnDevice) {
            cont.resume(Outcome(null, SpeechRecognizer.ERROR_CLIENT))
            return@suspendCancellableCoroutine
        }
        val r = obtain()
        var lastPartial: String? = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartial
                if (cont.isActive) cont.resume(Outcome(text, null))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { lastPartial = it }
            }

            override fun onError(error: Int) {
                // 途中まで聞こえていたなら、それを使う (聞き逃し対策)
                if (cont.isActive) cont.resume(if (lastPartial != null) Outcome(lastPartial, null) else Outcome(null, error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        cont.invokeOnCancellation { runCatching { r.cancel() } }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Outcome(null, SpeechRecognizer.ERROR_CLIENT))
        }
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val TAG = "JarvisSTT"
        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 31)
        private val RETRY_WITH_CLOUD = setOf(12, 13, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER)
    }
}
