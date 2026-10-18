package com.zen.jarvis.tools

import android.util.Log
import org.json.JSONObject

/** リスク区分 (protocol/PROTOCOL.md と共通)。 */
enum class Risk { SAFE, REMOTE_BLOCKED, CONFIRM }

/** type は JSON Schema の型 ("string" / "integer" / "boolean")。 */
data class Param(val name: String, val desc: String, val required: Boolean = true, val type: String = "string")

class Tool(
    val name: String,
    val description: String,
    val params: List<Param>,
    val risk: Risk,
    val run: suspend (JSONObject) -> String,
)

/** 実行元。LOCAL = この端末で話しかけた / REMOTE = 連携端末からの依頼 */
sealed class Origin {
    data object Local : Origin()
    data class Remote(val peer: String) : Origin()
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    /** ローカルで CONFIRM ツールを実行する前に呼ばれる確認関数 (音声で「はい」を待つ)。 */
    var confirm: (suspend (String) -> Boolean)? = null
    var allowRemoteDangerous: () -> Boolean = { false }

    fun add(
        name: String,
        description: String,
        vararg params: Param,
        risk: Risk = Risk.SAFE,
        run: suspend (JSONObject) -> String,
    ) {
        tools[name] = Tool(name, description, params.toList(), risk, run)
    }

    operator fun contains(name: String) = name in tools

    fun all(): Collection<Tool> = tools.values

    /** LLMプロンプト用のツール一覧。 */
    fun describeForPrompt(): String = tools.values.joinToString("\n") { t ->
        val args = t.params.joinToString(", ") { p -> "${p.name}${if (p.required) "" else "?"}: ${p.desc}" }
        "- ${t.name}($args): ${t.description}"
    }

    fun describeJson(): String {
        val arr = org.json.JSONArray()
        tools.values.forEach {
            arr.put(JSONObject().put("name", it.name).put("description", it.description)
                .put("risk", it.risk.name.lowercase()))
        }
        return arr.toString()
    }

    /** Claude API の tools 配列 (input_schema 付き)。 */
    fun claudeTools(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        tools.values.forEach { t ->
            val props = JSONObject()
            val required = org.json.JSONArray()
            t.params.forEach { p ->
                props.put(p.name, JSONObject().put("type", p.type).put("description", p.desc))
                if (p.required) required.put(p.name)
            }
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", required))
            )
        }
        return arr
    }

    suspend fun call(name: String, args: JSONObject, origin: Origin): String {
        val tool = tools[name] ?: return "ツール $name は存在しません。"
        val remote = origin is Origin.Remote
        if (tool.risk != Risk.SAFE && remote && !allowRemoteDangerous()) {
            return "安全のため、他の端末からの『$name』は許可されていません。"
        }
        if (tool.risk == Risk.CONFIRM && !remote) {
            val c = confirm
            if (c != null && !c("${tool.description} (${summarize(args)})")) {
                return "ユーザーが実行をキャンセルしました。"
            }
        }
        for (p in tool.params) {
            if (p.required && (!args.has(p.name) || args.isNull(p.name))) {
                return "引数 ${p.name} が足りません。"
            }
        }
        return try {
            tool.run(args)
        } catch (e: Exception) {
            Log.w("Jarvis", "tool $name failed", e)
            "エラー: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun summarize(args: JSONObject): String =
        args.keys().asSequence().joinToString(", ") { "$it=${args.opt(it)}" }
}
