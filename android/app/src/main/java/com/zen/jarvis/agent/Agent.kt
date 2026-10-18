package com.zen.jarvis.agent

import android.util.Log
import com.zen.jarvis.tools.Origin
import com.zen.jarvis.tools.ToolRegistry
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指示の処理: まず日本語ルールで即実行を試み、わからなければ端末内LLMにツールを選ばせる。
 * 小型モデルでも安定するよう、関数呼び出しは「JSONを1つだけ出力」させる方式。
 */
class Agent(private val tools: ToolRegistry, private val llm: LocalLlm) {

    private val history = ArrayDeque<Pair<String, String>>()
    private var lastTime = 0L

    var onToolCall: ((String, JSONObject) -> Unit)? = null

    suspend fun handle(text: String, origin: Origin): String {
        val now = System.currentTimeMillis()
        if (now - lastTime > 5 * 60_000) synchronized(history) { history.clear() }
        lastTime = now

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
            return "その指示はまだ理解できません。アプリの設定で言語モデルを読み込むと、自由な言い方に対応できます。"
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

    private fun remember(origin: Origin, user: String, reply: String) {
        if (origin !is Origin.Local) return
        synchronized(history) {
            history.addLast(user to reply)
            while (history.size > 3) history.removeFirst()
        }
    }

    private suspend fun runLlm(text: String, origin: Origin): String {
        val from = if (origin is Origin.Remote) "(${origin.peer}からの依頼) " else ""
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n").append(systemPrompt()).append("\n\n")
        val past = synchronized(history) { history.toList() }
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
