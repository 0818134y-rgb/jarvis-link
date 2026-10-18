package com.zen.jarvis.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * 常時の呼びかけ待ち受け。端末内の音声認識 (Vosk・オフライン) でマイクを聞き続ける。
 * システムの音声認識と違って開始音(ビープ)が鳴らず、途切れなく聞ける。
 *
 * - 「ジャーヴィス、ライトつけて」→ 呼びかけの後ろを指示として渡す
 * - 「ジャーヴィス」だけ → 空の指示を渡す (呼び出し側で続きを聞く)
 * - 返答の直後 (会話モード) は呼びかけなしの発話も指示として渡す
 */
class VoiceLoop(
    private val context: Context,
    private val extraWakeWords: () -> List<String>,
    private val onHeard: (String) -> Unit,
    private val onWake: () -> Unit,
    private val onUtterance: (command: String, viaWake: Boolean) -> Unit,
) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var followUntil = 0L
    @Volatile private var wakeSeen = false
    @Volatile var running = false
        private set

    val isReady: Boolean get() = model != null

    /** 同梱の音声モデルを展開して読み込む (初回だけ数秒かかる)。 */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (model != null) return@withContext true
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val dir = File(context.filesDir, "vosk-ja")
            val marker = File(dir, "version.txt")
            val assetVersion = runCatching { context.assets.open("vosk-ja/version.txt").bufferedReader().readText() }.getOrNull()
                ?: return@withContext false  // モデルが同梱されていない
            if (!marker.exists() || marker.readText() != assetVersion) {
                dir.deleteRecursively()
                copyAssets("vosk-ja", dir)
            }
            model = Model(dir.absolutePath)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "vosk prepare failed", e)
            false
        }
    }

    private fun copyAssets(path: String, dest: File) {
        val list = context.assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssets("$path/$name", File(dest, name))
    }

    fun start() {
        val m = model ?: return
        if (service != null) return
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(listener) }
            running = true
            wakeSeen = false
        } catch (e: Exception) {
            Log.w(TAG, "vosk start failed", e)
            running = false
        }
    }

    /** マイクを解放する (システムの音声認識に譲る時・停止時)。 */
    fun stop() {
        service?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        service = null
        running = false
    }

    /** 返答の直後、しばらく呼びかけなしで話しかけられるようにする。 */
    fun openFollowUp(ms: Long) {
        followUntil = System.currentTimeMillis() + ms
    }

    fun closeFollowUp() {
        followUntil = 0
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = parse(hypothesis, "partial")
            if (text.isEmpty()) return
            onHeard(text)
            if (!wakeSeen && WakeWord.detect(text, extraWakeWords()) != null) {
                wakeSeen = true
                onWake()
            }
        }

        override fun onResult(hypothesis: String?) = handleFinal(parse(hypothesis, "text"))
        override fun onFinalResult(hypothesis: String?) = handleFinal(parse(hypothesis, "text"))

        override fun onError(exception: Exception?) {
            Log.w(TAG, "vosk error", exception)
            stop()
            handler.postDelayed({ if (model != null) start() }, 1500)
        }

        override fun onTimeout() {
            stop()
            start()
        }
    }

    private fun handleFinal(raw: String) {
        wakeSeen = false
        val text = raw.replace(Regex("\\s+"), "")
        if (text.isEmpty()) return
        onHeard(text)
        val hit = WakeWord.detect(raw, extraWakeWords())
        when {
            hit != null -> {
                followUntil = 0
                onUtterance(hit.command, true)
            }
            System.currentTimeMillis() < followUntil && text.length >= 2 -> {
                followUntil = 0
                onUtterance(text, false)
            }
        }
    }

    private fun parse(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("").trim()

    companion object {
        private const val TAG = "JarvisVoiceLoop"
        private const val SAMPLE_RATE = 16000f
    }
}
