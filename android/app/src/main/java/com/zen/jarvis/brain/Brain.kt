package com.zen.jarvis.brain

import android.content.Context
import android.util.Log
import com.zen.jarvis.Jarvis
import com.zen.jarvis.agent.Rules
import com.zen.jarvis.tools.Origin
import com.zen.jarvis.tools.ToolRegistry
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * ジャーヴィスの頭脳 (外部AIを使わない自作エンジン)。
 *
 * 1. 意図理解: 自前で学習したモデル (IntentModel) が発話の意図を判定
 * 2. 情報抽出: 意図ごとに「何分」「どのアプリ」「どこの天気」などを取り出す
 * 3. 対話管理: 足りない情報は聞き返し、確認が必要なら「はい/いいえ」を待ち、直前の話題を覚えておく
 * 4. 実行と応答: 端末操作ツールや知識スキルを呼び、ジャーヴィスらしい口調で答える
 */
class Brain(private val context: Context, private val tools: ToolRegistry) {

    private val model: IntentModel by lazy {
        IntentModel(
            context.assets.open("brain/model.json").bufferedReader().use { it.readText() },
            context.assets.open("brain/weights.bin").use { it.readBytes() },
        )
    }
    val memory = Memory(context)
    private val weather = Weather(context)

    var onToolCall: ((String, JSONObject) -> Unit)? = null

    /** 聞き返し中の情報 (例: タイマーの分数を待っている) */
    private data class Pending(val intent: String, val question: String)

    @Volatile private var pending: Pending? = null
    @Volatile private var confirmAction: (suspend () -> String)? = null
    @Volatile private var lastIntent: String? = null
    @Volatile private var lastPlace: String? = null
    @Volatile private var more: String? = null
    @Volatile private var lastTime = 0L
    @Volatile private var lastOpened: String? = null
    @Volatile private var lastOpenedAt = 0L

    /** モデルを先に読み込んでおく (初回の返答を速くするため)。 */
    fun warmUp() {
        runCatching { model.intents.size }
    }

    suspend fun handle(text: String, origin: Origin): String {
        val local = origin is Origin.Local
        if (local && System.currentTimeMillis() - lastTime > 3 * 60_000) {
            pending = null; confirmAction = null; lastIntent = null; more = null
        }
        if (local) lastTime = System.currentTimeMillis()
        return try {
            val parts = splitCompound(text.trim())
            if (parts.size > 1) runCompound(parts, origin) else respond(text.trim(), origin)
        } catch (e: IOException) {
            Log.w(TAG, "network", e)
            "ネットワークに接続できませんでした。電波の状態を確認してください。"
        } catch (e: Exception) {
            Log.w(TAG, "brain error", e)
            "申し訳ありません、${memory.callName}。処理中に問題が起きました。"
        }
    }

    /** 「YouTubeを開いて、猫動画を検索して」のような連続指示を分ける。 */
    private fun splitCompound(text: String): List<String> {
        val parts = text.split(Regex("(?<=て)(?:から)?[、,]\\s*|(?<=て)から|[、,]?(?:それから|そのあと|その後|そして|次に)[、,]?"))
            .map { it.trim(' ', '、', ',') }.filter { it.length >= 2 }
        if (parts.size < 2) return listOf(text)
        val ok = parts.all { p -> model.predict(p, 1)[0].let { it.prob >= 0.5 && it.intent in ACTION_INTENTS } }
        return if (ok) parts else listOf(text)
    }

    private suspend fun runCompound(parts: List<String>, origin: Origin): String {
        val replies = mutableListOf<String>()
        for ((i, p) in parts.withIndex()) {
            val r = respond(p, origin)
            replies += r
            if (pending != null || confirmAction != null) break  // 聞き返しが出たらそこで止める
            if (i < parts.lastIndex) kotlinx.coroutines.delay(if (lastIntent in setOf("open_app", "browser_search")) 1800 else 600)
        }
        return replies.joinToString(" ")
    }

    private suspend fun respond(text: String, origin: Origin): String {
        val local = origin is Origin.Local
        val name = memory.callName
        if (IntentModel.normalize(text).isEmpty()) return "はい、$name。ご用件をどうぞ。"

        // ---- 他の端末宛て (「パソコンで〜」) は最優先で転送 ----
        if (Regex("このページ|このサイト|今のページ|このURL|見てるページ|開いてるページ").containsMatchIn(text) &&
            Regex("パソコン|PC|ピーシー|コンピュータ", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return tool("share_page", obj("device" to "pc"), origin)
        }
        Rules.match(text)?.let { r ->
            if (r.tool in REMOTE_TOOLS) return tool(r.tool, r.args, origin)
        }

        val top = model.predict(text, 2)
        val intent = top[0].intent
        val prob = top[0].prob
        Log.d(TAG, "'$text' -> $top")

        // ---- 確認待ち (はい/いいえ) ----
        confirmAction?.let { action ->
            if (local) {
                confirmAction = null
                when (intent) {
                    "yes" -> return action()
                    "no", "cancel" -> return "承知しました。"
                }
            }
        }

        // ---- 聞き返し中: 答えをそのまま足りない情報として使う ----
        pending?.let { p ->
            if (local) {
                pending = null
                if (intent == "cancel" || (intent == "no" && prob > 0.6)) return "かしこまりました、取りやめます。"
                // 明らかに別の依頼を言われたら、聞き返しは捨ててそちらを処理する
                val newRequest = prob >= 0.85 && intent != p.intent && intent !in setOf("oos", "yes", "wiki")
                if (!newRequest) {
                    val merged = mergeAnswer(p.intent, text)
                    if (merged != null) return dispatch(p.intent, merged, origin, fromPending = true)
                }
            }
        }

        // ---- 「もっと詳しく」「続き」 ----
        if (Regex("^(もっと|続き|つづき|詳しく|くわしく|他には|ほかには)").containsMatchIn(text) && more != null) {
            val m = more!!
            more = null
            return m
        }

        // ---- 直前の話題の続き (「明日は?」「大阪は?」「スポーツは?」) ----
        val norm = IntentModel.normalize(text)
        if (local && norm.length <= 10 && prob < 0.8) {
            when (lastIntent) {
                "weather" -> if (Regex("明日|あした|明後日|あさって|週末|今日|今|は[？?]?$").containsMatchIn(text) && intent != "yes" && intent != "no") {
                    return dispatch("weather", text, origin)
                }
                "news" -> if (text.endsWith("は") || text.endsWith("は？") || text.endsWith("は?")) return dispatch("news", text, origin)
            }
        }

        // ---- 自信がない時 ----
        if (prob < 0.35) {
            // 定型ルールで拾えるならそれを使う
            Rules.match(text)?.let { r -> if (r.tool in tools) return Talk.ack() + tool(r.tool, r.args, origin) }
            return unknown(text, origin)
        }
        return dispatch(intent, text, origin)
    }

    /** 聞き返しへの答えを元の依頼文に合成する (例: タイマー + 「3分」→「3分のタイマー」)。 */
    private fun mergeAnswer(intent: String, answer: String): String? = when (intent) {
        "timer" -> if (Regex("\\d|[一二三四五六七八九十]").containsMatchIn(answer)) "${answer}のタイマー" else null
        "alarm" -> if (Regex("\\d|[一二三四五六七八九十]").containsMatchIn(answer)) "${answer.replace(Regex("に$"), "")}にアラーム" else null
        "open_app" -> "${answer}を開いて"
        "call" -> "${answer.removeSuffix("に")}に電話して"
        "navigate" -> "${answer.removeSuffix("まで")}までナビして"
        "browser_search" -> "${answer}を検索して"
        "wiki" -> "${answer}について教えて"
        "calc" -> answer
        "tap_text" -> "${answer}をタップして"
        "type_text" -> "${answer}と入力して"
        "sms" -> "${answer.removeSuffix("に")}にメッセージを送って"
        "user_name_tell" -> "私の名前は${answer}です"
        "remember_note" -> "メモ $answer"
        else -> null
    }

    private fun ask(intent: String, question: String): String {
        pending = Pending(intent, question)
        return question
    }

    private suspend fun tool(name: String, args: JSONObject, origin: Origin): String {
        onToolCall?.invoke(name, args)
        return tools.call(name, args, origin)
    }

    private fun obj(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }

    /** Rules.match の結果が期待するツールならその引数を使う。 */
    private fun ruleArgs(text: String, expect: String): JSONObject? =
        Rules.match(text)?.takeIf { it.tool == expect }?.args

    // =====================================================================
    private suspend fun dispatch(intent: String, text: String, origin: Origin, fromPending: Boolean = false): String {
        val local = origin is Origin.Local
        val name = memory.callName
        if (local) lastIntent = intent
        val ack = Talk.ack()

        return when (intent) {
            // ---------------- 端末操作 ----------------
            "flashlight" -> ack + tool("flashlight", obj("on" to !Regex("消|けし|オフ|off|切|もういい").containsMatchIn(text)), origin)
            "volume" -> {
                val args = ruleArgs(text, "set_volume") ?: when {
                    Regex("ミュート|消音|音を消").containsMatchIn(text) -> obj("action" to "mute")
                    Regex("最大|マックス").containsMatchIn(text) -> obj("action" to "set", "level" to 100)
                    Regex("うるさい|静か|小さ|下げ|ダウン").containsMatchIn(text) -> obj("action" to "down")
                    else -> obj("action" to "up")
                }
                ack + tool("set_volume", args, origin)
            }
            "media" -> {
                val action = when {
                    Regex("次|スキップ|飛ばし").containsMatchIn(text) -> "next"
                    Regex("前|戻し").containsMatchIn(text) -> "previous"
                    Regex("止め|停止|ストップ|ポーズ").containsMatchIn(text) -> "pause"
                    else -> "play"
                }
                ack + tool("media_control", obj("action" to action), origin)
            }
            "music_play" -> {
                val q = text.replace(Regex("^(ジャーヴィス|ジャービス)[、,\\s]*"), "")
                    .replace(Regex("(の曲|の音楽)?(を|が)?(かけて|流して|再生して|聴きたい|聞きたい).*$"), "").trim()
                if (q.isEmpty() || Regex("^(音楽|曲|何か曲|BGM|作業用の音楽)$").matches(q)) tool("media_control", obj("action" to "play"), origin)
                else tool("play_music", obj("query" to q), origin)
            }
            "timer" -> ruleArgs(text, "set_timer")?.let { tool("set_timer", it, origin) }
                ?: ask("timer", "何分のタイマーにしますか？")
            "alarm" -> ruleArgs(text, "set_alarm")?.let { tool("set_alarm", it, origin) }
                ?: ask("alarm", "何時にセットしますか？")
            "open_app" -> {
                val args = ruleArgs(text, "open_app")
                val appName = args?.optString("name").orEmpty()
                when {
                    args == null || Regex("^アプリ$").matches(appName) -> ask("open_app", "どのアプリを開きますか？")
                    com.zen.jarvis.tools.Sites.looksLikeDomain(appName) -> tool("open_url", obj("url" to appName), origin)
                    else -> {
                        if (local) { lastOpened = appName; lastOpenedAt = System.currentTimeMillis() }
                        val r = tool("open_app", args, origin)
                        val site = com.zen.jarvis.tools.Sites.find(appName)
                        if (r.contains("見つかりませんでした") && site != null) tool("open_url", obj("url" to site.home), origin) else r
                    }
                }
            }
            "call" -> ruleArgs(text, "call")?.let { tool("call", it, origin) }
                ?: Regex("^(.+?)に(?:電話|連絡)").find(text)?.let { tool("call", obj("target" to it.groupValues[1]), origin) }
                ?: ask("call", "どなたに電話しますか？")
            "sms" -> {
                val person = Regex("^(?:ジャーヴィス[、,\\s]*)?(.+?)に").find(text)?.groupValues?.get(1)
                val body = Regex("[「『](.+?)[」』]").find(text)?.groupValues?.get(1)
                    ?: Regex("に(.+?)(?:って|と)(?:送って|メッセージ|伝えて)").find(text)?.groupValues?.get(1)
                if (person == null) ask("sms", "どなたに送りますか？")
                else tool("compose_sms", obj("target" to person, "message" to (body ?: "")), origin)
            }
            "navigate" -> ruleArgs(text, "navigate")?.let { tool("navigate", it, origin) }
                ?: Regex("^(.+?)(?:に連れてって|へのルート|までどうやって)").find(text)?.let { tool("navigate", obj("destination" to it.groupValues[1]), origin) }
                ?: ask("navigate", "どこまで案内しますか？")
            "browser_search" -> {
                val recent = lastOpened?.takeIf { System.currentTimeMillis() - lastOpenedAt < 60_000 }
                val site = com.zen.jarvis.tools.Sites.find(text) ?: recent?.let { com.zen.jarvis.tools.Sites.find(it) }
                // 「LINEで田中を検索」: サイトでなくアプリ名なら、アプリを開いて中で検索
                val appQuery = Regex("^(?:ジャーヴィス[、,\\s]*)?(.+?)(?:アプリ)?で(.+?)(?:を|って)?(?:検索|探して|調べて|さがして)").find(text)
                val appEntry = appQuery?.takeIf { !Regex("^(ネット|ブラウザ|ウェブ|web|インターネット|画面|ここ)$", RegexOption.IGNORE_CASE).matches(it.groupValues[1]) }
                    ?.let { com.zen.jarvis.tools.AppFinder.find(context, it.groupValues[1]) }
                    ?: if (site == null) recent?.let { com.zen.jarvis.tools.AppFinder.find(context, it) } else null
                if (site == null && appEntry != null) {
                    val q = appQuery?.groupValues?.get(2)
                        ?: (ruleArgs(text, "browser_search")?.optString("query") ?: "")
                    if (q.isBlank()) ask("browser_search", "${appEntry.label}で何を検索しますか？")
                    else tool("app_search", obj("app" to appEntry.label, "query" to q), origin)
                } else if (site != null) {
                    val q = com.zen.jarvis.tools.Sites.queryOf(text, site)
                    if (q.isBlank()) tool("open_url", obj("url" to site.home), origin)
                    else tool("site_search", obj("site" to site.name, "query" to q), origin)
                } else {
                    ruleArgs(text, "browser_search")?.let { tool("browser_search", it, origin) }
                        ?: ask("browser_search", "何を検索しますか？")
                }
            }
            "browser_control" -> {
                val action = when {
                    Regex("新しいタブ|シークレット").containsMatchIn(text) -> "new_tab"
                    Regex("戻").containsMatchIn(text) -> "back"
                    Regex("一番上|トップ").containsMatchIn(text) -> "top"
                    Regex("一番下").containsMatchIn(text) -> "bottom"
                    Regex("再読み込み|更新|リロード").containsMatchIn(text) -> "reload"
                    else -> "other"
                }
                tool("browser_control", obj("action" to action), origin)
            }
            "list_items" -> tool("list_items", JSONObject(), origin)
            "tap_item" -> {
                val n = Regex("(\\d+)").find(JapaneseNumber.replaceKanjiNumbers(text))?.value?.toInt()
                if (n == null) ask("tap_item", "何番をタップしますか？") else tool("tap_item", obj("number" to n), origin)
            }
            "long_press" -> {
                val t = Regex("^(?:ジャーヴィス[、,\\s]*)?(.+?)(?:を)?(?:長押し|押しっぱなし)").find(text)?.groupValues?.get(1)
                if (t.isNullOrBlank()) ask("long_press", "どれを長押ししますか？") else tool("long_press", obj("text" to t), origin)
            }
            "swipe" -> tool("swipe", obj("direction" to if (Regex("右|前").containsMatchIn(text)) "right" else "left"), origin)
            "share_page" -> if (Regex("パソコン|PC|ピーシー|コンピュータ", RegexOption.IGNORE_CASE).containsMatchIn(text))
                tool("share_page", obj("device" to "pc"), origin) else "このページはもうスマホで開いていますよ。"
            "find_phone" -> tool("find_phone", JSONObject(), origin)
            "calendar_add" -> {
                val ev = parseEvent(text)
                if (ev == null) "いつの、何の予定ですか？「明日10時に会議を予定に入れて」のように言ってください。"
                else tool("calendar_add", obj("title" to ev.first, "start" to ev.second.toString()), origin)
            }
            "mail" -> {
                val body = Regex("[「『](.+?)[」』]").find(text)?.groupValues?.get(1) ?: ""
                tool("compose_mail", obj("body" to body), origin)
            }
            "post_x" -> {
                val t = Regex("[「『](.+?)[」』]").find(text)?.groupValues?.get(1)
                    ?: Regex("(?:Xに|ツイッターに|エックスに)?(.+?)(?:と|って)(?:投稿|つぶやいて|ポスト|ツイート)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                tool("post_x", obj("text" to (t ?: "")), origin)
            }
            "take_photo" -> tool("take_photo", obj("video" to text.contains("動画")), origin)
            "sleep" -> tool("lock_screen", JSONObject(), origin)
            "file_search", "window" -> forwardToPc(text, origin, "パソコンの機能")
            "close_app" -> {
                val r = tool("go_home", JSONObject(), origin)
                if (r.startsWith("画面操作")) r else "アプリを閉じてホーム画面に戻りました。"
            }
            "keys" -> {
                val act = when {
                    Regex("コピー").containsMatchIn(text) -> "copy"
                    Regex("貼り付け|ペースト").containsMatchIn(text) -> "paste"
                    Regex("切り取").containsMatchIn(text) -> "cut"
                    Regex("全部選択|全選択").containsMatchIn(text) -> "select_all"
                    Regex("エンター|決定|確定").containsMatchIn(text) -> "enter"
                    else -> null
                }
                if (act == null) forwardToPc(text, origin, "パソコンのキーボード操作") else tool("edit_action", obj("action" to act), origin)
            }
            "settings" -> {
                val page = when {
                    Regex("wi-?fi|ワイファイ|無線", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "wifi"
                    Regex("bluetooth|ブルートゥース", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "bluetooth"
                    Regex("明るさ|画面|ディスプレイ").containsMatchIn(text) -> "display"
                    Regex("音|サウンド").containsMatchIn(text) -> "sound"
                    Regex("バッテリー|電池").containsMatchIn(text) -> "battery"
                    Regex("位置|GPS").containsMatchIn(text) -> "location"
                    else -> "general"
                }
                tool("open_settings", obj("page" to page), origin)
            }
            "battery" -> tool("battery_status", JSONObject(), origin)
            "screenshot" -> tool("screenshot", JSONObject(), origin)
            "go_back" -> tool("go_back", JSONObject(), origin)
            "go_home" -> tool("go_home", JSONObject(), origin)
            "recents" -> tool("recent_apps", JSONObject(), origin)
            "notifications" -> tool("read_notifications", JSONObject(), origin)
            "lock_screen" -> tool("lock_screen", JSONObject(), origin)
            "read_screen" -> tool("read_screen", JSONObject(), origin)
            "scroll" -> tool("scroll", obj("direction" to if (Regex("上|戻").containsMatchIn(text)) "up" else "down"), origin)
            "tap_text" -> ruleArgs(text, "tap_text")?.let { tool("tap_text", it, origin) }
                ?: ask("tap_text", "どれをタップしますか？")
            "type_text" -> {
                val field = Regex("^(?:ジャーヴィス[、,\\s]*)?(.+?)(?:の)?(?:欄|入力欄|フィールド|ボックス)に").find(text)?.groupValues?.get(1)
                val t = Regex("[「『](.+?)[」』]").find(text)?.groupValues?.get(1)
                    ?: Regex("^(?:.+?欄に)?(.+?)(?:と|って)(?:入力|打って|書いて|入れて)").find(text)?.groupValues?.get(1)
                when {
                    t.isNullOrBlank() -> ask("type_text", "何と入力しますか？")
                    field != null && !Regex("検索").containsMatchIn(field) -> tool("type_into", obj("field" to field, "text" to t), origin)
                    else -> tool("type_text", obj("text" to t, "enter" to Regex("検索|して検索|確定").containsMatchIn(text.substringAfter(t))), origin)
                }
            }

            // ---------------- 情報 ----------------
            "time" -> {
                val now = LocalDateTime.now()
                val ampm = if (now.hour < 12) "午前" else "午後"
                val h12 = if (now.hour % 12 == 0) 12 else now.hour % 12
                "ただいま${ampm}${h12}時${now.minute}分です。"
            }
            "date" -> "今日は" + LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.JAPAN)) + "です。"
            "days_until" -> daysUntil(text)
            "weather" -> weatherAnswer(text, local)
            "news" -> {
                val topic = NEWS_TOPICS.firstOrNull { text.contains(it, ignoreCase = true) }
                    ?: Regex("^(.+?)(?:の|で)(?:ニュース|最新情報|結果)").find(text)?.groupValues?.get(1)
                        ?.takeIf { it !in setOf("今日", "最新", "主な", "面白い") }
                val a = Knowledge.news(topic)
                if (local) more = a.more?.let { "続きです。$it" }
                a.text + if (a.more != null && local) "続きを聞きたい時は「続き」と言ってください。" else ""
            }
            "wiki" -> {
                val topic = Knowledge.topicOf(text)
                if (topic.isBlank() || topic.length > 30) ask("wiki", "何について調べますか？")
                else {
                    val a = Knowledge.wikipedia(topic)
                    if (a == null) offerSearch(topic, "${topic}についての情報は見つかりませんでした。")
                    else {
                        if (local) more = a.more
                        a.text
                    }
                }
            }
            "calc" -> {
                val expr = Calc.toExpression(text)
                if (expr == null) ask("calc", "何を計算しますか？")
                else runCatching { "${Calc.speakable(expr)}は、${Calc.format(Calc.evaluate(expr))}です。" }
                    .getOrElse { "すみません、その式は計算できませんでした。" }
            }
            "currency" -> Knowledge.currency(text).text

            // ---------------- 記憶 ----------------
            "user_name_tell" -> {
                val n = Regex("(?:名前は|僕は|私は|俺は|わたしは|ぼくは|おれは)(.+?)(?:です|だよ|だ|って言います|といいます)?$").find(text)?.groupValues?.get(1)
                    ?: Regex("^(.+?)(?:と|って)呼んで").find(text)?.groupValues?.get(1)
                if (n.isNullOrBlank()) ask("user_name_tell", "お名前を教えてください。")
                else {
                    memory.userName = n.trim().removeSuffix("さん")
                    "${memory.userName}さんですね。覚えました。これからは${memory.userName}さんとお呼びします。"
                }
            }
            "user_name_ask" -> if (memory.userName.isBlank()) "まだお名前を伺っていません。「私の名前は〇〇」と教えてください。"
            else "${memory.userName}さんです。忘れるはずがありません。"
            "home_tell" -> {
                val c = Regex("^(?:私は|僕は|家は)?(.+?)(?:に住んで|在住|にある|に住ん)").find(text)?.groupValues?.get(1)
                if (c.isNullOrBlank()) "どちらにお住まいですか？"
                else {
                    memory.homeCity = c
                    "${c}ですね。天気を聞かれた時の基準にします。"
                }
            }
            "birthday_tell" -> {
                val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(JapaneseNumber.replaceKanjiNumbers(text))
                if (m == null) "誕生日は何月何日ですか？"
                else {
                    memory.birthday = "%02d-%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    "${m.groupValues[1]}月${m.groupValues[2]}日ですね。覚えておきます。"
                }
            }
            "remember_note" -> {
                val note = text.replace(Regex("^(ジャーヴィス|ジャービス)[、,\\s]*"), "")
                    .replace(Regex("^(メモして|メモしておいて|メモ|覚えておいて|覚えて)[:：、,\\s]*"), "")
                    .replace(Regex("(って|と)?(覚えておいて|覚えといて|覚えて|メモしておいて|メモして|メモ)$"), "")
                    .trim()
                if (note.isBlank() || Regex("^(メモ|覚え)").matches(note)) ask("remember_note", "何を覚えておきましょうか？")
                else {
                    memory.addNote(note)
                    "「$note」、覚えました。"
                }
            }
            "recall_notes" -> {
                val facts = buildList {
                    if (memory.userName.isNotBlank()) add("お名前は${memory.userName}さん")
                    if (memory.homeCity.isNotBlank()) add("お住まいは${memory.homeCity}")
                    if (memory.birthday.isNotBlank()) memory.birthday.split("-").let { add("誕生日は${it[0].toInt()}月${it[1].toInt()}日") }
                }
                val notes = memory.notes
                when {
                    facts.isEmpty() && notes.isEmpty() -> "まだ何も覚えていません。「〇〇って覚えておいて」と言ってください。"
                    else -> (if (facts.isNotEmpty()) facts.joinToString("、") + "です。" else "") +
                        (if (notes.isNotEmpty()) "メモは${notes.size}件あります。" + notes.takeLast(5).joinToString("。") + "。" else "")
                }
            }
            "clear_notes" -> {
                confirmAction = { memory.clearNotes(); "メモをすべて消去しました。" }
                "メモを${memory.notes.size}件すべて消去します。よろしいですか？"
            }

            // ---------------- 会話 ----------------
            "capabilities" -> "いろいろお手伝いできます。スマホの操作なら、アプリを開く、ライト、音量、タイマーやアラーム、電話、道案内、写真撮影。" +
                "アプリの中の操作は「画面の項目を教えて」で番号を読み上げるので、「3番をタップ」のように指示してください。スクロールやスワイプ、長押し、文字入力もできます。" +
                "ブラウザでは、Amazonや楽天、YouTubeなどのサイト内検索や新しいタブ。調べものは、天気、ニュース、Wikipedia、計算、為替。" +
                "「YouTubeを開いて、猫動画を検索して」のように続けて頼むこともできます。" +
                "パソコンとつながっていれば「パソコンで〇〇して」「このページをパソコンで開いて」も使えますよ。"
            "decide" -> Talk.decide(text)
            "oos" -> if (looksLikeQuestion(text)) unknown(text, origin) else Talk.reply("oos", name)!!
            else -> Talk.reply(intent, name) ?: unknown(text, origin)
        }
    }

    // =====================================================================

    private suspend fun weatherAnswer(text: String, local: Boolean): String {
        val whenDay = Weather.whenOf(text)
        val placeName = Weather.placeOf(text) ?: if (lastIntent == "weather") lastPlace else null
        val place = when {
            placeName != null -> weather.geocode(placeName) ?: return "「$placeName」という場所が見つかりませんでした。"
            else -> weather.currentPlace()
                ?: memory.homeCity.takeIf { it.isNotBlank() }?.let { weather.geocode(it) }
                ?: weather.geocode("東京")
                ?: return "場所がわかりませんでした。「大阪の天気」のように地名を付けてください。"
        }
        if (local) lastPlace = placeName ?: lastPlace
        return weather.report(place, whenDay)
    }

    private fun daysUntil(text: String): String {
        val today = LocalDate.now()
        val t = JapaneseNumber.replaceKanjiNumbers(text)
        val (label, md) = when {
            t.contains("クリスマス") -> "クリスマス" to MonthDay.of(12, 25)
            Regex("正月|元日|元旦|新年").containsMatchIn(t) -> "お正月" to MonthDay.of(1, 1)
            t.contains("バレンタイン") -> "バレンタインデー" to MonthDay.of(2, 14)
            t.contains("ホワイトデー") -> "ホワイトデー" to MonthDay.of(3, 14)
            t.contains("ハロウィン") -> "ハロウィン" to MonthDay.of(10, 31)
            t.contains("七夕") -> "七夕" to MonthDay.of(7, 7)
            t.contains("夏休み") -> "夏休み" to MonthDay.of(7, 20)
            Regex("年末|大晦日|大みそか").containsMatchIn(t) -> "大晦日" to MonthDay.of(12, 31)
            t.contains("誕生日") -> {
                if (memory.birthday.isBlank()) return "お誕生日をまだ伺っていません。「私の誕生日は〇月〇日」と教えてください。"
                val p = memory.birthday.split("-")
                "お誕生日" to MonthDay.of(p[0].toInt(), p[1].toInt())
            }
            else -> {
                val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(t) ?: return "何の日までか教えてください。たとえば「クリスマスまであと何日」のように聞いてください。"
                "${m.groupValues[1]}月${m.groupValues[2]}日" to MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
        var target = md.atYear(today.year)
        if (target.isBefore(today)) target = target.plusYears(1)
        val days = ChronoUnit.DAYS.between(today, target)
        return if (days == 0L) "今日が${label}です！" else "${label}まで、あと${days}日です。"
    }

    /** PCにしかない機能は、つながっていればPCへ回す。 */
    private suspend fun forwardToPc(text: String, origin: Origin, what: String): String {
        val pc = Jarvis.directory.list().firstOrNull { it.platform == "windows" }
            ?: return "それは${what}です。パソコン版のJARVISとつながっている時に「パソコンで〜」と頼んでください。"
        return tool("send_to_device", obj("device" to pc.name, "command" to text), origin)
    }

    /** 「明日10時に会議を予定に入れて」→ ("会議", 明日10:00) */
    private fun parseEvent(text: String): Pair<String, LocalDateTime>? {
        val t = JapaneseNumber.replaceKanjiNumbers(text)
        val today = LocalDate.now()
        val date = when {
            Regex("明後日|あさって").containsMatchIn(t) -> today.plusDays(2)
            Regex("明日|あした").containsMatchIn(t) -> today.plusDays(1)
            else -> Regex("(\\d{1,2})月(\\d{1,2})日").find(t)?.let {
                var d = LocalDate.of(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
                if (d.isBefore(today)) d = d.plusYears(1)
                d
            } ?: today
        }
        val tm = Regex("(午前|午後|朝|夜)?(\\d{1,2})時(?:(\\d{1,2})分|(半))?").find(t) ?: return null
        var hour = tm.groupValues[2].toInt()
        if ((tm.groupValues[1] == "午後" || tm.groupValues[1] == "夜") && hour < 12) hour += 12
        val minute = if (tm.groupValues[3].isNotEmpty()) tm.groupValues[3].toInt() else if (tm.groupValues[4].isNotEmpty()) 30 else 0
        val title = t.replace(Regex("^(ジャーヴィス|ジャービス)[、,\\s]*"), "")
            .replace(Regex("(今日|明日|あした|明後日|あさって|\\d{1,2}月\\d{1,2}日)(の)?"), "")
            .replace(tm.value, "")
            .replace(Regex("^(に|から|より|の)"), "")
            .replace(Regex("(を|の)?(予定|スケジュール|カレンダー)?(に|へ)?(入れて|追加して|追加|登録して|登録|を入れて)$"), "")
            .replace(Regex("(の予定|予定|を)$"), "")
            .trim(' ', '、', 'に', 'の', 'を')
        return title.ifBlank { "予定" } to LocalDateTime.of(date, java.time.LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
    }

    private fun looksLikeQuestion(text: String) =
        Regex("(何|なに|誰|だれ|どこ|いつ|なぜ|どうして|どう|いくら|教えて|知ってる|とは|って|か)[？?]?$").containsMatchIn(text)

    /** わからない時: 質問ならWikipediaで調べ、それでもだめならブラウザ検索を提案。 */
    private suspend fun unknown(text: String, origin: Origin): String {
        if (looksLikeQuestion(text)) {
            val topic = Knowledge.topicOf(text)
            if (topic.isNotBlank() && topic.length <= 20) {
                Knowledge.wikipedia(topic)?.let { a ->
                    if (origin is Origin.Local) { more = a.more; lastIntent = "wiki" }
                    return a.text
                }
            }
        }
        return offerSearch(text, "申し訳ありません、${memory.callName}。その件はまだ勉強中です。")
    }

    private fun offerSearch(query: String, lead: String): String {
        confirmAction = { tool("browser_search", obj("query" to query), Origin.Local) }
        return "${lead}ブラウザで調べましょうか？"
    }

    companion object {
        private const val TAG = "JarvisBrain"
        private val ACTION_INTENTS = setOf("flashlight", "volume", "media", "music_play", "timer", "alarm", "open_app",
            "call", "sms", "navigate", "browser_search", "browser_control", "settings", "screenshot", "go_back", "go_home",
            "recents", "notifications", "lock_screen", "scroll", "tap_text", "type_text", "list_items", "tap_item",
            "long_press", "swipe", "share_page", "take_photo", "keys", "close_app", "window", "file_search", "calendar_add",
            "mail", "post_x", "read_screen", "weather", "news", "time", "battery")
        private val REMOTE_TOOLS = setOf("send_to_device", "speak_on_device", "get_device_clipboard", "send_clipboard")
        private val NEWS_TOPICS = listOf("スポーツ", "経済", "IT", "芸能", "政治", "野球", "サッカー", "テクノロジー",
            "国際", "AI", "エンタメ", "科学", "ビジネス", "株", "ゲーム", "天気")
    }
}
