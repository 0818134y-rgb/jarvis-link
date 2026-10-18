package com.zen.jarvis.agent

import org.json.JSONObject

/**
 * よく使う指示を LLM なしで即実行するための日本語ルール。
 * 小さな端末内モデルより速く確実。マッチしなければ LLM に回す。
 */
object Rules {
    data class Match(val tool: String, val args: JSONObject, val fallbackToLlm: Boolean = false)

    private val IC = RegexOption.IGNORE_CASE

    fun match(raw: String): Match? {
        val t = normalize(raw)
        if (t.isEmpty()) return null

        // ---- 他端末: 読み上げ・クリップボード ----
        val pcWord = "(?:pc|パソコン|ピーシー|コンピューター|コンピュータ|デスクトップ)"
        Regex("^$pcWord(?:に|で)[、,\\s]*[「『]?(.+?)[」』]?(?:って|と)(?:読み上げて|伝えて|言って|しゃべって)$", IC).find(t)?.let {
            return Match("speak_on_device", obj("device" to "pc", "text" to it.groupValues[1]))
        }
        if (Regex("^$pcWord(?:の|から)クリップボード", IC).containsMatchIn(t)) {
            return Match("get_device_clipboard", obj("device" to "pc"))
        }
        if (Regex("クリップボードを?$pcWord(?:に|へ)", IC).containsMatchIn(t)) {
            return Match("send_clipboard", obj("device" to "pc"))
        }

        // ---- 他端末へ転送 ----
        Regex("^(?:pc|パソコン|ピーシー|コンピューター|コンピュータ|デスクトップ)(?:で|に|の|から)[、,\\s]*(.+)$", IC).find(t)?.let {
            return Match("send_to_device", obj("device" to "pc", "command" to it.groupValues[1]))
        }
        // 「スマホで〜」は自分宛てなので前置きを外す
        val s = t.replace(Regex("^(?:スマホ|スマートフォン|携帯|この端末)(?:で|の)[、,\\s]*"), "")

        // ---- ライト ----
        if (Regex("ライト|懐中電灯|フラッシュ").containsMatchIn(s)) {
            if (Regex("消|けし|オフ|off", IC).containsMatchIn(s)) return Match("flashlight", obj("on" to false))
            if (Regex("つけ|付け|点け|オン|on|点灯", IC).containsMatchIn(s)) return Match("flashlight", obj("on" to true))
        }

        // ---- 音量 ----
        if (s.contains("音量") || s.contains("ボリューム")) {
            Regex("(\\d{1,3})\\s*(?:%|パーセント)").find(s)?.let {
                return Match("set_volume", obj("action" to "set", "level" to it.groupValues[1].toInt()))
            }
            if (Regex("上げ|大きく|アップ").containsMatchIn(s)) return Match("set_volume", obj("action" to "up"))
            if (Regex("下げ|小さく|ダウン").containsMatchIn(s)) return Match("set_volume", obj("action" to "down"))
        }
        if (Regex("^(?:ミュート|消音)(?:して|に)?$").matches(s)) return Match("set_volume", obj("action" to "mute"))

        // ---- メディア ----
        if (Regex("次の曲|スキップ").containsMatchIn(s)) return Match("media_control", obj("action" to "next"))
        if (Regex("前の曲").containsMatchIn(s)) return Match("media_control", obj("action" to "previous"))
        if (Regex("^(?:音楽|曲|動画)?を?(?:止めて|ポーズ|ストップ)(?:して)?$").matches(s)) return Match("media_control", obj("action" to "pause"))
        if (Regex("^(?:音楽|曲|動画)?を?(?:再生|再開)(?:して)?$").matches(s)) return Match("media_control", obj("action" to "play"))

        // ---- タイマー ----
        if (s.contains("タイマー")) {
            val h = Regex("(\\d+)\\s*時間").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            val m = Regex("(\\d+)\\s*分").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            val sec = Regex("(\\d+)\\s*秒").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            if (h + m + sec > 0) return Match("set_timer", obj("minutes" to h * 60 + m, "seconds" to sec))
        }

        // ---- アラーム ----
        Regex("(午前|午後|朝|夜)?\\s*(\\d{1,2})\\s*時\\s*(?:(\\d{1,2})\\s*分|(半))?.*(?:アラーム|起こして)").find(s)?.let {
            var hour = it.groupValues[2].toInt()
            val ampm = it.groupValues[1]
            if ((ampm == "午後" || ampm == "夜") && hour < 12) hour += 12
            val minute = when {
                it.groupValues[3].isNotEmpty() -> it.groupValues[3].toInt()
                it.groupValues[4].isNotEmpty() -> 30
                else -> 0
            }
            return Match("set_alarm", obj("hour" to hour, "minute" to minute))
        }

        // ---- 画面操作 ----
        if (Regex("^(?:戻って|戻る|バック|1つ前に戻って)$").matches(s)) return Match("go_back", JSONObject())
        if (Regex("^ホーム(?:画面)?(?:に戻って|へ|に|を開いて|に戻る)?$").matches(s)) return Match("go_home", JSONObject())
        if (Regex("^(?:下|した)に?スクロール").containsMatchIn(s)) return Match("scroll", obj("direction" to "down"))
        if (Regex("^(?:上|うえ)に?スクロール").containsMatchIn(s)) return Match("scroll", obj("direction" to "up"))
        if (Regex("スクショ|スクリーンショット").containsMatchIn(s)) return Match("screenshot", JSONObject())
        if (Regex("^(?:画面|この画面)を?(?:読んで|読み上げて)").containsMatchIn(s)) return Match("read_screen", JSONObject())
        if (Regex("^通知を?(?:見せて|開いて)").containsMatchIn(s)) return Match("open_notifications", JSONObject())
        if (Regex("^画面を?(?:ロック|消して)").containsMatchIn(s)) return Match("lock_screen", JSONObject())

        // ---- 情報 ----
        if (Regex("バッテリー|電池").containsMatchIn(s)) return Match("battery_status", JSONObject())
        if (Regex("(?:今|いま)?(?:何時|なんじ)|今日は何日|何曜日").containsMatchIn(s)) return Match("get_time", JSONObject())

        // ---- ナビ・電話 ----
        Regex("^(.+?)(?:まで|へ)の?(?:ナビ|道案内|案内|行き方)").find(s)?.let {
            return Match("navigate", obj("destination" to it.groupValues[1]))
        }
        Regex("^(.+?)に電話(?:して|をかけて|かけて)?$").find(s)?.let {
            return Match("call", obj("target" to it.groupValues[1]), fallbackToLlm = true)
        }

        // ---- タップ ----
        Regex("^(.+?)(?:を|って)?(?:タップ|押して|クリック)(?:して)?$").find(s)?.let {
            return Match("tap_text", obj("text" to it.groupValues[1].removeSuffix("ボタン")), fallbackToLlm = true)
        }

        // ---- 検索 ----
        Regex("^(?:ユーチューブ|youtube)で(.+?)を?(?:検索|探して|調べて|再生|見せて)", IC).find(s)?.let {
            return Match("web_search", obj("query" to it.groupValues[1], "site" to "youtube"))
        }
        Regex("^(.+?)(?:を|で|って|について)?(?:検索して|検索|調べて|ググって)$").find(s)?.let {
            return Match("web_search", obj("query" to it.groupValues[1]))
        }

        // ---- アプリ起動 ----
        Regex("^(.+?)(?:を|の)?(?:開いて|ひらいて|起動して|起動|立ち上げて|開けて|つけて)$").find(s)?.let {
            val name = it.groupValues[1].removeSuffix("アプリ").removeSuffix("の").trim()
            if (name.isNotEmpty()) return Match("open_app", obj("name" to name), fallbackToLlm = true)
        }
        return null
    }

    private fun obj(vararg pairs: Pair<String, Any>): JSONObject =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    /** 句読点除去、全角数字→半角、時間表現の漢数字→数字。 */
    fun normalize(raw: String): String {
        var t = raw.trim().trimEnd('。', '、', '！', '？', '!', '?', '.', ' ')
        t = t.replace("ジャーヴィス", "").replace("ジャービス", "").trimStart('、', ',', ' ')
        t = t.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        t = t.replace("一時停止", "ポーズ")
        t = Regex("([〇一二三四五六七八九十百]+)(?=\\s*(?:時間|時|分|秒|%|パーセント))").replace(t) {
            kanjiToInt(it.groupValues[1])?.toString() ?: it.value
        }
        return t
    }

    private fun kanjiToInt(s: String): Int? {
        val digits = mapOf('〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var cur = 0
        for (c in s) {
            when (c) {
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                else -> cur = digits[c] ?: return null
            }
        }
        return total + cur
    }
}
