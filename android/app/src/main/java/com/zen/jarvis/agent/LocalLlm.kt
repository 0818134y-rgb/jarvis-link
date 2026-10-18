package com.zen.jarvis.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 端末内で動く言語モデル (MediaPipe LLM Inference)。
 * Gemma 3n E2B / Gemma 3 1B などの .task ファイルを読み込む。ネット接続不要。
 */
class LocalLlm(private val context: Context) {
    @Volatile
    private var engine: LlmInference? = null
    private val mutex = Mutex()

    val isReady: Boolean get() = engine != null
    var modelName: String = ""
        private set

    suspend fun load(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(File(path).exists()) { "モデルファイルがありません: $path" }
                engine?.close()
                engine = null
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    // 入力+出力の合計トークン。モデルファイルの ekv サイズ(多くは2048)を超えないこと
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                engine = LlmInference.createFromOptions(context, options)
                modelName = File(path).name
            }
        }
    }

    /** Gemma の会話テンプレートで1回分の応答を生成する。 */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val e = engine ?: error("モデルが読み込まれていません")
            e.generateResponse(prompt).trim()
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        const val MAX_TOKENS = 2048
    }
}
