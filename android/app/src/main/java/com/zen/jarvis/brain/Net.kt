package com.zen.jarvis.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** スキル用の小さなHTTPクライアント (APIキー不要の公開APIだけを使う)。 */
object Net {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "JarvisLink/1.2 (personal voice assistant; Android)")
            .header("Accept-Language", "ja")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty body")
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
