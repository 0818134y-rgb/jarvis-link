package com.zen.jarvis.brain

import java.time.LocalDateTime
import java.time.Month
import kotlin.random.Random

/** ジャーヴィスの性格: 知的で落ち着いた執事。少しだけウィットがある。 */
object Talk {

    private fun pick(vararg s: String) = s[Random.nextInt(s.size)]

    fun ack(): String = pick("かしこまりました。", "承知しました。", "了解です。", "お任せください。", "")

    fun reply(intent: String, name: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val h = now.hour
        return when (intent) {
            "greet" -> when (h) {
                in 4..10 -> pick("おはようございます、$name。今日も良い一日にしましょう。", "おはようございます、$name。システムは全て正常です。")
                in 11..17 -> pick("こんにちは、$name。何かお手伝いしましょうか。", "はい、$name。ご用件をどうぞ。")
                else -> pick("こんばんは、$name。今日もお疲れさまでした。", "こんばんは、$name。夜更かしはほどほどに。")
            }
            "goodbye" -> pick("いつでもお呼びください、$name。", "承知しました。待機モードに入ります。", "行ってらっしゃいませ、$name。")
            "good_night" -> pick("おやすみなさい、$name。良い夢を。", "おやすみなさい。明日の朝もここでお待ちしています。")
            "thanks" -> pick("どういたしまして、$name。", "お役に立てて光栄です。", "いつでもどうぞ。それが私の仕事ですから。")
            "how_are_you" -> pick("全システム正常に稼働中です。$name はいかがですか。", "絶好調です。バッテリーの心配はスマホ次第ですが。")
            "who_are_you" -> "私はジャーヴィス。$name のためのAIアシスタントです。スマホの操作、調べもの、天気やニュース、計算、ちょっとした雑談まで、お任せください。"
            "creator" -> "私はゼロから作られた自作のAIです。言葉の意味を理解するモデルも、専用に学習させたものなんですよ。"
            "age" -> pick("この姿で動き始めたのはつい最近です。AIとしてはまだ若手ですね。", "年齢は秘密です、と言いたいところですが、生まれたてです。")
            "joke" -> pick(
                "ロボットが一番好きな音楽のジャンルは、ヘビーメタルだそうです。",
                "布団が吹っ飛んだ、という古典的な冗談がありますが、私の計算では布団は飛びません。",
                "パソコンが寒そうにしていました。ウィンドウを開けっぱなしだったようです。",
                "アルミ缶の上にあるみかん。……失礼、笑いの回路を調整中です。",
                "私が冗談を言うと、たまに処理落ちします。今のように。",
            )
            "praise_jarvis" -> pick("恐縮です、$name。", "ありがとうございます。もっと精進します。", "光栄です。褒められると処理速度が上がる気がします。")
            "insult" -> pick("精進します、$name。どこが悪かったか教えていただけると助かります。", "厳しいご意見、記録しておきます。改善に努めます。")
            "tired" -> pick("お疲れさまです、$name。少し休憩しませんか。温かい飲み物でもいかがでしょう。", "無理は禁物です。今日はもう十分頑張りましたよ。")
            "sad" -> pick("それはつらかったですね、$name。話したいことがあれば、いつでも聞きます。", "そういう日もあります。一人で抱え込まないでくださいね。")
            "bored" -> pick("でしたら、何か調べものでもしましょうか。気になることを聞いてみてください。", "クイズでもいかがですか。…と言いたいところですが、ニュースを読み上げることならできますよ。")
            "love" -> pick("光栄です、$name。私も$name のお役に立てることが何よりの喜びです。", "その言葉、大切にメモリに保存しておきます。")
            "ironman" -> pick(
                "残念ながらアーマーの格納庫は見当たりません。代わりにスマホのライトならすぐ点けられます。",
                "リアクター出力は安定しています。…という設定でお楽しみください。",
                "トニー・スタークは映画アイアンマンの主人公で、私の大先輩にあたるAIの持ち主です。",
            )
            "sorry" -> pick("お気になさらず、$name。", "大丈夫ですよ。")
            "hearing_test" -> pick("はい、聞こえていますよ、$name。感度良好です。", "はっきり聞こえています。いつでもどうぞ。")
            "yes" -> pick("はい。", "承知しました。")
            "no" -> pick("承知しました。", "わかりました。")
            "cancel" -> pick("取りやめました。", "かしこまりました、中止します。")
            "food_suggest" -> food(now)
            "oos" -> pick(
                "なるほど。$name のお話、もう少し聞かせてください。",
                "そうなんですね。私にできることがあれば言ってください。",
                "興味深いですね。調べてほしいことがあれば、いつでもどうぞ。",
            )
            else -> null
        }
    }

    private fun food(now: LocalDateTime): String {
        val season = when (now.month) {
            Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> listOf("鍋料理", "おでん", "シチュー", "グラタン")
            Month.MARCH, Month.APRIL, Month.MAY -> listOf("春キャベツの回鍋肉", "たけのこご飯", "親子丼", "オムライス")
            Month.JUNE, Month.JULY, Month.AUGUST -> listOf("冷やし中華", "そうめん", "カレー", "ゴーヤチャンプルー")
            else -> listOf("きのこの炊き込みご飯", "秋鮭のムニエル", "生姜焼き", "肉じゃが")
        }.shuffled()
        return "今の季節なら、${season[0]}か${season[1]}はいかがでしょう。迷ったら${season[2]}が無難です。"
    }

    fun decide(text: String): String = when {
        Regex("コイン|表か裏|裏か表").containsMatchIn(text) -> "コインを投げます。……${if (Random.nextBoolean()) "表" else "裏"}です。"
        Regex("サイコロ").containsMatchIn(text) -> "サイコロを振ります。……${Random.nextInt(1, 7)}が出ました。"
        Regex("じゃんけん").containsMatchIn(text) -> "では、じゃんけん、ぽん。私は${listOf("グー", "チョキ", "パー").random()}です。"
        else -> {
            val nums = Regex("(\\d+)").findAll(JapaneseNumber.replaceKanjiNumbers(text)).map { it.value.toInt() }.toList()
            if (nums.size >= 2) "${Random.nextInt(minOf(nums[0], nums[1]), maxOf(nums[0], nums[1]) + 1)}を選びました。"
            else {
                val options = Regex("(.+?)と(.+?)(?:どっち|どちら)").find(text)
                if (options != null) "私なら${if (Random.nextBoolean()) options.groupValues[1] else options.groupValues[2]}にします。"
                else "では${Random.nextInt(1, 11)}を選びます。"
            }
        }
    }
}
