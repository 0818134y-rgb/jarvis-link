package com.zen.jarvis.agent

import com.zen.jarvis.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Claude Messages API の最小クライアント。 */
class ClaudeClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = prefs.claudeApiKey.isNotBlank()
    val model: String get() = prefs.claudeModel

    suspend fun create(system: String, messages: JSONArray, tools: JSONArray, maxTokens: Int = 1024): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", prefs.claudeModel)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("tools", tools)
                .put("messages", messages)
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", prefs.claudeApiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ClaudeException(resp.code, errorMessage(resp.code, text))
                JSONObject(text)
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrDefault("")
        return when {
            code == 401 -> "Claude APIキーが正しくありません。設定を確認してください。"
            code == 403 -> "このAPIキーでは利用が許可されていません。"
            detail.contains("credit balance", ignoreCase = true) ->
                "Claude APIのクレジット残高が不足しています。コンソールでチャージしてください。"
            code == 404 -> "モデル ${prefs.claudeModel} が見つかりません。設定でモデルを変えてください。"
            code == 429 -> "Claude APIの利用上限に達しました。少し待ってからもう一度どうぞ。"
            code == 529 || code >= 500 -> "Claudeのサーバーが混み合っています。少し待ってからもう一度どうぞ。"
            else -> "Claude APIエラー($code): $detail"
        }
    }
}

class ClaudeException(val code: Int, message: String) : Exception(message)
