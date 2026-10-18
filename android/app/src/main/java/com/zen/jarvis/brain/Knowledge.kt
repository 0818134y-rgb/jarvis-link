package com.zen.jarvis.brain

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** 知識系スキル: Wikipedia・ニュース・為替 (どれも無料・APIキー不要)。 */
object Knowledge {

    data class Answer(val text: String, val more: String? = null)

    // ---------------- Wikipedia ----------------

    private val TOPIC_SUFFIXES = listOf(
        "について詳しく教えてください", "について詳しく教えて", "について調べて教えて", "について教えてください",
        "について教えて", "について知りたい", "について詳しく", "について", "とは何ですか", "とはなんですか", "とは",
        "ってどういうもの", "ってどういう意味", "ってどんなの", "ってどんな人", "ってなんですか", "って何ですか",
        "って何", "ってなに", "ってなん", "って誰", "ってだれ", "って知ってる", "を知ってる", "知ってる", "を説明して",
        "の意味を教えて", "の意味は", "の意味", "は誰ですか", "は誰", "はだれ", "は何ですか", "は何", "はなに",
        "はどこにある", "はどこ", "はいつできた", "はいつ", "の歴史", "の高さは", "の大きさは", "ですか", "教えて",
    )

    fun topicOf(text: String): String {
        var t = text.replace(Regex("[、。？?！!\\s]+$"), "").replace(Regex("^(ジャーヴィス|ジャービス|ねえ)[、,\\s]*"), "").trim()
        var changed = true
        while (changed) {
            changed = false
            for (s in TOPIC_SUFFIXES) {
                if (t.endsWith(s) && t.length > s.length) {
                    t = t.removeSuffix(s).trim()
                    changed = true
                }
            }
        }
        return t
    }

    suspend fun wikipedia(topic: String): Answer? {
        val search = JSONObject(Net.get(
            "https://ja.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&utf8=1&srsearch=" +
                Net.enc(topic)))
        val title = search.getJSONObject("query").getJSONArray("search").optJSONObject(0)?.optString("title")
            ?: return null
        val sum = JSONObject(Net.get("https://ja.wikipedia.org/api/rest_v1/page/summary/" + Net.enc(title.replace(' ', '_'))))
        val extract = sum.optString("extract").replace(Regex("（[^）]*）|\\([^)]*\\)"), "").trim()
        if (extract.isBlank()) return null
        val sentences = extract.split("。").map { it.trim() }.filter { it.isNotEmpty() }.map { "$it。" }
        val head = sentences.take(2).joinToString("")
        val rest = sentences.drop(2).take(4).joinToString("").ifBlank { null }
        val prefix = if (sum.optString("type") == "disambiguation") "${title}にはいくつか意味があります。" else ""
        return Answer(prefix + head, rest)
    }

    // ---------------- ニュース ----------------

    suspend fun news(topic: String?): Answer {
        val url = if (topic.isNullOrBlank()) "https://news.google.com/rss?hl=ja&gl=JP&ceid=JP:ja"
        else "https://news.google.com/rss/search?hl=ja&gl=JP&ceid=JP:ja&q=" + Net.enc(topic)
        val titles = parseRssTitles(Net.get(url)).map { it.substringBeforeLast(" - ").trim() }.distinct()
        if (titles.isEmpty()) return Answer("今はニュースを取得できませんでした。")
        val head = titles.take(3).mapIndexed { i, t -> "${i + 1}つ目、$t。" }.joinToString("")
        val more = titles.drop(3).take(3).mapIndexed { i, t -> "${i + 4}つ目、$t。" }.joinToString("").ifBlank { null }
        val lead = if (topic.isNullOrBlank()) "最新のニュースです。" else "${topic}に関するニュースです。"
        return Answer(lead + head, more)
    }

    private fun parseRssTitles(xml: String): List<String> {
        val out = mutableListOf<String>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var inItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) out += p.nextText()
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
            }
        }
        return out
    }

    // ---------------- 為替 ----------------

    private val CURRENCIES = linkedMapOf(
        "ドル" to "USD", "ユーロ" to "EUR", "ポンド" to "GBP", "人民元" to "CNY", "元" to "CNY", "ウォン" to "KRW",
        "豪ドル" to "AUD", "カナダドル" to "CAD", "スイスフラン" to "CHF", "フラン" to "CHF", "香港ドル" to "HKD",
        "台湾ドル" to "TWD", "バーツ" to "THB", "ルピー" to "INR", "円" to "JPY",
    )
    private val NAMES = mapOf("USD" to "ドル", "EUR" to "ユーロ", "GBP" to "ポンド", "CNY" to "人民元", "KRW" to "ウォン",
        "AUD" to "豪ドル", "CAD" to "カナダドル", "CHF" to "スイスフラン", "HKD" to "香港ドル", "TWD" to "台湾ドル",
        "THB" to "バーツ", "INR" to "ルピー", "JPY" to "円")

    suspend fun currency(text: String): Answer {
        val t = JapaneseNumber.replaceKanjiNumbers(text)
        // 文中に出てくる通貨を出現順に
        val found = mutableListOf<Pair<Int, String>>()
        val covered = BooleanArray(t.length)
        for ((word, code) in CURRENCIES.entries.sortedByDescending { it.key.length }) {
            var idx = t.indexOf(word)
            while (idx >= 0) {
                if ((idx until idx + word.length).none { covered[it] }) {
                    for (k in idx until idx + word.length) covered[k] = true
                    found += idx to code
                }
                idx = t.indexOf(word, idx + word.length)
            }
        }
        val codes = found.sortedBy { it.first }.map { it.second }.distinct()
        val (from, to) = when {
            codes.size >= 2 -> codes[0] to codes[1]
            codes.size == 1 && codes[0] != "JPY" -> codes[0] to "JPY"
            else -> "USD" to "JPY"
        }
        val amount = Regex("(\\d+(?:\\.\\d+)?)").find(t)?.value?.toDouble() ?: 1.0
        val rate = rateOf(from, to)
        val result = amount * rate
        val fmt = if (result >= 100) "%,.0f".format(result) else "%,.2f".format(result)
        return Answer("今のレートで、${Calc.format(amount)}${NAMES[from]}は約${fmt}${NAMES[to]}です。")
    }

    private suspend fun rateOf(from: String, to: String): Double = runCatching {
        JSONObject(Net.get("https://api.frankfurter.app/latest?from=$from&to=$to")).getJSONObject("rates").getDouble(to)
    }.getOrElse {
        JSONObject(Net.get("https://open.er-api.com/v6/latest/$from")).getJSONObject("rates").getDouble(to)
    }
}
