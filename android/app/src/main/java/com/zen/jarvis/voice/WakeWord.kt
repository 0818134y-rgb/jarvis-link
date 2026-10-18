package com.zen.jarvis.voice

import java.text.Normalizer

/**
 * 「ジャーヴィス」の呼びかけ判定。
 * 音声認識は「ジャービス」「じゃあ ビス」「ジャーヴィズ」など色々な書き方で返すので、
 * ひらがなに揃えて音の似た形をまとめて受け付ける。追加の呼びかけ語も設定できる。
 */
object WakeWord {

    // 長音・小さい「っ」・空白を除いたひらがな表記に対して照合
    private val PATTERN = Regex("(?:じゃ|ぢゃ|ちゃ|じや|ぢや)あ?(?:ゔぃ|ゔ|び|ぴ|べ|ぶい|ぶぃ|うぃ|ゔぇ)(?:す|ず|しゅ|じゅ)|jarvis|jervis|じゃびす")

    data class Hit(val command: String)

    fun normalize(text: String): String {
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(t.length)
        for (ch in t) {
            if (ch == ' ' || ch == 'ー' || ch == 'っ' || ch == 'ッ' || ch == '・' || ch == '、' || ch == '。' || ch == ',') continue
            sb.append(if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch)
        }
        return sb.toString()
    }

    /**
     * 呼びかけが含まれていれば、その後ろの言葉 (指示) を返す。含まれなければ null。
     * extra: 設定で追加した呼びかけ語
     */
    fun detect(text: String, extra: List<String> = emptyList()): Hit? {
        if (text.isBlank()) return null
        // 元の文字列の位置を保ったまま照合するため、文字ごとに正規化して対応表を作る
        val map = ArrayList<Int>()
        val sb = StringBuilder()
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        for ((i, ch) in nfkc.withIndex()) {
            if (ch == ' ' || ch == 'ー' || ch == 'っ' || ch == 'ッ' || ch == '・' || ch == '、' || ch == '。' || ch == ',') continue
            sb.append(if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch)
            map.add(i)
        }
        val norm = sb.toString()
        var endNorm = -1
        PATTERN.find(norm)?.let { endNorm = it.range.last }
        if (endNorm < 0) {
            for (w in extra) {
                val nw = normalize(w)
                if (nw.isEmpty()) continue
                val idx = norm.indexOf(nw)
                if (idx >= 0) { endNorm = idx + nw.length - 1; break }
            }
        }
        if (endNorm < 0) return null
        val endOrig = map[endNorm] + 1
        val rest = nfkc.substring(endOrig)
            .trimStart(' ', '、', ',', '。', 'ー', 'ッ', 'っ', '!', '！')
            .replace(Regex("^(?:さん|くん|ー)"), "")
            .replace(Regex("\\s+"), "")   // 音声認識の単語区切りの空白を詰める
            .trim()
        return Hit(rest)
    }
}
