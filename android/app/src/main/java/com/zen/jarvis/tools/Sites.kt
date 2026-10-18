package com.zen.jarvis.tools

import android.net.Uri

/** よく使うサイト・アプリ内検索のURL。PC版 (pc/jarvis/sites.py) と同じ内容。 */
object Sites {
    data class Site(val name: String, val aliases: List<String>, val home: String, val search: String?)

    val all = listOf(
        Site("Google", listOf("google", "グーグル", "ぐぐる"), "https://www.google.com", "https://www.google.com/search?q=%s"),
        Site("YouTube", listOf("youtube", "ユーチューブ", "ようつべ"), "https://www.youtube.com", "https://www.youtube.com/results?search_query=%s"),
        Site("Amazon", listOf("amazon", "アマゾン"), "https://www.amazon.co.jp", "https://www.amazon.co.jp/s?k=%s"),
        Site("楽天", listOf("楽天", "らくてん", "rakuten"), "https://www.rakuten.co.jp", "https://search.rakuten.co.jp/search/mall/%s/"),
        Site("メルカリ", listOf("メルカリ", "mercari"), "https://jp.mercari.com", "https://jp.mercari.com/search?keyword=%s"),
        Site("ヤフー", listOf("ヤフー", "yahoo", "やふー"), "https://www.yahoo.co.jp", "https://search.yahoo.co.jp/search?p=%s"),
        Site("Wikipedia", listOf("wikipedia", "ウィキペディア", "ウィキ"), "https://ja.wikipedia.org", "https://ja.wikipedia.org/w/index.php?search=%s"),
        Site("Googleマップ", listOf("googleマップ", "グーグルマップ", "地図", "マップ"), "https://www.google.com/maps", "https://www.google.com/maps/search/%s"),
        Site("食べログ", listOf("食べログ", "たべろぐ"), "https://tabelog.com", "https://tabelog.com/rstLst/?sw=%s"),
        Site("価格.com", listOf("価格.com", "価格コム", "かかくコム"), "https://kakaku.com", "https://search.kakaku.com/%s/"),
        Site("X", listOf("ツイッター", "twitter", "エックス"), "https://x.com", "https://x.com/search?q=%s"),
        Site("Spotify", listOf("spotify", "スポティファイ"), "https://open.spotify.com", "https://open.spotify.com/search/%s"),
        Site("GitHub", listOf("github", "ギットハブ"), "https://github.com", "https://github.com/search?q=%s"),
        Site("Netflix", listOf("netflix", "ネットフリックス", "ネトフリ"), "https://www.netflix.com", "https://www.netflix.com/search?q=%s"),
    )

    /** 文中に出てくるサイトを探す (長い別名から優先)。 */
    fun find(text: String): Site? {
        val t = text.lowercase()
        return all.flatMap { s -> s.aliases.map { it to s } }
            .sortedByDescending { it.first.length }
            .firstOrNull { t.contains(it.first.lowercase()) }?.second
    }

    fun searchUrl(site: Site, query: String): String =
        (site.search ?: site.home).replace("%s", Uri.encode(query))

    /** 「github.com」「example.co.jp」などドメインらしい文字列か */
    fun looksLikeDomain(s: String): Boolean = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/\\S*)?$").matches(s.trim())

    /** 「Amazonでイヤホンを探して」→「イヤホン」 */
    fun queryOf(text: String, site: Site): String {
        var t = text.replace(Regex("^(ジャーヴィス|ジャービス|ねえ)[、,\\s]*"), "")
        for (a in site.aliases.sortedByDescending { it.length }) {
            t = t.replace(Regex(Regex.escape(a) + "(アプリ)?(で|の|から)", RegexOption.IGNORE_CASE), "")
        }
        return t.replace(Regex("^(を)?開いて"), "")
            .replace(Regex("(の値段)?(を|が)?(検索して|検索|探して|調べて|見せて|再生して|見たい|買いたい|聴きたい|流して)[。、！!？?]*$"), "")
            .trim()
    }
}
