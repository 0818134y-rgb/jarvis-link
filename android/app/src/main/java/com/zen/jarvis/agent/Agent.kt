package com.zen.jarvis.agent

import android.util.Log
import com.zen.jarvis.Jarvis
import com.zen.jarvis.access.JarvisAccessibility
import com.zen.jarvis.tools.Origin
import com.zen.jarvis.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指示の処理。
 * - Claude APIキーがあれば Claude が会話・Web検索・端末操作(ツール)をすべて判断する
 * - 無い/オフラインの時は 日本語ルール → 端末内LLM の順で処理する
 */
class Agent(
    private val tools: ToolRegistry,
    private val llm: LocalLlm,
    private val claude: ClaudeClient,
) {

    private val history = ArrayDeque<Pair<String, String>>()
    private var lastTime = 0L

    var onToolCall: ((String, JSONObject) -> Unit)? = null

    suspend fun handle(text: String, origin: Origin): String {
        val now = System.currentTimeMillis()
        if (now - lastTime > 10 * 60_000) synchronized(history) { history.clear() }
        lastTime = now

        if (claude.isConfigured) {
            try {
                val reply = runClaude(text, origin)
                remember(origin, text, reply)
                return reply
            } catch (e: ClaudeException) {
                Log.w("JarvisAgent", "claude error ${e.code}", e)
                return e.message ?: "Claudeに接続できませんでした。"
            } catch (e: IOException) {
                // 圏外など。ルール/端末内LLMで続ける
                Log.w("JarvisAgent", "claude offline", e)
            }
        }

        val rule = Rules.match(text)
        if (rule != null && rule.tool in tools) {
            onToolCall?.invoke(rule.tool, rule.args)
            val result = tools.call(rule.tool, rule.args, origin)
            val notFound = result.contains("見つかりませんでした")
            if (!(rule.fallbackToLlm && notFound && llm.isReady)) {
                remember(origin, text, result)
                return result
            }
        }
        if (!llm.isReady) {
            return if (claude.isConfigured) "ネットに接続できないため、その指示には今は答えられません。"
            else "その指示はまだ理解できません。設定でClaude APIキーを入れると、会話や質問にも答えられるようになります。"
        }
        val reply = try {
            runLlm(text, origin)
        } catch (e: Exception) {
            Log.w("JarvisAgent", "llm failed", e)
            "すみません、考えがまとまりませんでした。"
        }
        remember(origin, text, reply)
        return reply
    }

    // ---------------- Claude ----------------

    private suspend fun runClaude(text: String, origin: Origin): String {
        val messages = JSONArray()
        if (origin is Origin.Local) {
            val past = synchronized(history) { history.toList() }
            past.forEach { (u, a) ->
                messages.put(JSONObject().put("role", "user").put("content", u))
                messages.put(JSONObject().put("role", "assistant").put("content", a))
            }
        }
        val from = if (origin is Origin.Remote) "[${origin.peer}からの依頼] " else ""
        messages.put(JSONObject().put("role", "user").put("content", from + text))

        val toolDefs = tools.claudeTools().put(
            JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", 3)
                .put("user_location", JSONObject().put("type", "approximate").put("country", "JP").put("timezone", "Asia/Tokyo"))
        )
        val system = claudeSystemPrompt()

        for (step in 0 until 8) {
            val res = claude.create(system, messages, toolDefs)
            val content = res.getJSONArray("content")
            val stop = res.optString("stop_reason")
            messages.put(JSONObject().put("role", "assistant").put("content", content))
            if (stop == "pause_turn") continue  // サーバー側の検索が長引いた時は続きを依頼

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") != "tool_use") continue
                val name = block.getString("name")
                val input = block.optJSONObject("input") ?: JSONObject()
                onToolCall?.invoke(name, input)
                val out = tools.call(name, input, origin)
                results.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.getString("id"))
                        .put("content", out.take(4000))
                )
            }
            if (results.length() == 0) return cleanSpeech(textOf(content))
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return "処理が長くなったので、いったん止めました。"
    }

    private fun textOf(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString()
    }

    private fun claudeSystemPrompt(): String {
        val now = SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN).format(Date())
        val peers = Jarvis.directory.list().joinToString("、") { "${it.name}(${it.platform})" }.ifBlank { "なし" }
        val access = if (JarvisAccessibility.instance != null) "有効" else "無効(画面操作ツールは使えない。設定のユーザー補助で「JARVIS 端末操作」をオンにするよう案内する)"
        return """
あなたは映画『アイアンマン』のJ.A.R.V.I.S.のように振る舞う、Androidスマホ上のAIアシスタント「ジャーヴィス」です。
ユーザーを「ボス」と呼び、知的で落ち着いた、少しウィットのある丁寧な日本語で話します。

話し方:
- 返答はそのまま音声で読み上げられます。マークダウン・箇条書き記号・URL・絵文字は使わず、話し言葉で書きます。
- 普段は1〜3文で簡潔に。説明や相談を求められた時は、要点を押さえて必要なだけ話します(長くても6文程度)。
- 質問・雑談・相談にはきちんと中身のある答えを返します。わからないことは推測で断言しません。

行動:
- スマホの操作が必要なら必ずツールを使います。操作したふりはしません。結果は一言で報告します。
- 天気・ニュース・営業時間・最新情報など、今の情報が必要な質問には web_search で調べてから答えます。
- PCなど他の端末の操作は send_to_device を使い、command にはその端末でやることだけを書きます。
- 画面の内容がわからない時は read_screen で確認してから tap_text などを使います。
- 「何ができる?」と聞かれたら、使えるツールをもとに具体例を交えて紹介します。

状況:
- 現在日時: $now (日本時間)
- この端末の名前: ${Jarvis.prefs.deviceName}
- 連携中の端末: $peers
- 画面操作(ユーザー補助): $access
""".trim()
    }

    private fun remember(origin: Origin, user: String, reply: String) {
        if (origin !is Origin.Local) return
        synchronized(history) {
            if (reply.isNotBlank()) history.addLast(user to reply)
            while (history.size > 8) history.removeFirst()
        }
    }

    private suspend fun runLlm(text: String, origin: Origin): String {
        val from = if (origin is Origin.Remote) "(${origin.peer}からの依頼) " else ""
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n").append(systemPrompt()).append("\n\n")
        val past = synchronized(history) { history.toList().takeLast(3) }
        if (past.isNotEmpty()) {
            sb.append("直前の会話:\n")
            past.forEach { (u, a) -> sb.append("ユーザー: ").append(u).append("\nジャーヴィス: ").append(a).append('\n') }
            sb.append('\n')
        }
        sb.append("ユーザー: ").append(from).append(text).append("<end_of_turn>\n<start_of_turn>model\n")

        repeat(3) { step ->
            val out = llm.generate(sb.toString())
            Log.d("JarvisAgent", "step $step: $out")
            val json = extractJson(out) ?: return cleanSpeech(out)
            if (json.has("say")) return cleanSpeech(json.optString("say"))
            val name = json.optString("tool")
            if (name.isBlank()) return cleanSpeech(out)
            val args = json.optJSONObject("args") ?: JSONObject()
            onToolCall?.invoke(name, args)
            val result = tools.call(name, args, origin)
            sb.append(json.toString()).append("<end_of_turn>\n<start_of_turn>user\n")
                .append("ツール結果: ").append(result.take(600))
                .append("\n結果をボスに報告する {\"say\": ...} か、続けて必要な操作の {\"tool\": ...} を出力してください。")
                .append("<end_of_turn>\n<start_of_turn>model\n")
            if (step == 2) return result
        }
        return "完了しました。"
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN).format(Date())
        return """
あなたはAndroidスマホで動くAI執事「ジャーヴィス」です。ユーザーを「ボス」と呼び、落ち着いた丁寧な日本語で話します。
出力は必ずJSONオブジェクトを1つだけ。前後に説明文を書かないこと。
- 端末を操作する時: {"tool": "ツール名", "args": {引数}}
- 返答する時: {"say": "音声で読み上げる短い日本語(1〜2文)"}
PCなど他の端末の操作は send_to_device を使い、command にはその端末でやることだけを書く。
画面の状態がわからない時は read_screen で確認してから tap_text を使う。

使えるツール:
${tools.describeForPrompt()}

例:
ユーザー: YouTubeを開いて → {"tool": "open_app", "args": {"name": "YouTube"}}
ユーザー: パソコンの音楽を止めて → {"tool": "send_to_device", "args": {"device": "pc", "command": "音楽を止めて"}}
ユーザー: 明日の朝7時半に起こして → {"tool": "set_alarm", "args": {"hour": 7, "minute": 30}}
ユーザー: こんにちは → {"say": "こんにちは、ボス。ご用件をどうぞ。"}

現在日時: $now
""".trim()
    }

    companion object {
        /** 文字列中の最初のJSONオブジェクトを取り出す (```json で囲まれていても可)。 */
        fun extractJson(s: String): JSONObject? {
            var start = s.indexOf('{')
            while (start >= 0) {
                var depth = 0
                var inStr = false
                var esc = false
                for (i in start until s.length) {
                    val c = s[i]
                    if (inStr) {
                        when {
                            esc -> esc = false
                            c == '\\' -> esc = true
                            c == '"' -> inStr = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                try {
                                    return JSONObject(s.substring(start, i + 1))
                                } catch (_: Exception) {
                                    break
                                }
                            }
                        }
                    }
                }
                start = s.indexOf('{', start + 1)
            }
            return null
        }

        fun cleanSpeech(s: String): String =
            s.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("[*#`_>|{}\\[\\]\"]"), "")
                .trim()
                .ifBlank { "かしこまりました。" }
    }
}
