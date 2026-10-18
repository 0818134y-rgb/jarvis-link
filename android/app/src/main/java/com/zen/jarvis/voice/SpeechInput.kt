package com.zen.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 音声認識。Android 12+ で端末内認識(オフライン)が使えればそれを優先し、
 * 日本語パックが無い等で失敗したら通常の認識に切り替える。
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 一言聞き取って文字列を返す。何も聞こえなければ null。 */
    suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        val first = listenInternal()
        val err = first.error
        if (err != null && err in RETRY_WITH_CLOUD && useOnDevice) {
            Log.i(TAG, "on-device recognizer unavailable (${first.error}), falling back")
            useOnDevice = false
            recognizer?.destroy()
            recognizer = null
            listenInternal().text
        } else {
            first.text
        }
    }

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
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (cont.isActive) cont.resume(Outcome(list?.firstOrNull()?.takeIf { it.isNotBlank() }, null))
            }

            override fun onError(error: Int) {
                if (cont.isActive) cont.resume(Outcome(null, error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
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
