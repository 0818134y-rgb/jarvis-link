package com.zen.jarvis.brain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 日本語の計算式を解く電卓 (再帰下降パーサを自作)。 */
object Calc {

    /** 「3たす5は」→ "3+5" のような式に変換。数式が見つからなければ null。 */
    fun toExpression(text: String): String? {
        var t = JapaneseNumber.replaceKanjiNumbers(text)
        t = t.replace(Regex("[、。？?！!\\s]"), "")
            .replace(Regex("(を)?計算(して)?|はいくつ|は何|はなに|いくつ|は$|=|＝|って何|ってなに|イコール"), "")
            .replace(Regex("たす|足す|プラス|＋"), "+")
            .replace(Regex("ひく|引く|マイナス|－|−"), "-")
            .replace(Regex("かける|掛ける|×|✕|＊"), "*")
            .replace(Regex("わる|割る|÷|／"), "/")
            .replace(Regex("の二乗|の2乗"), "^2")
            .replace(Regex("の(\\d+(?:\\.\\d+)?)乗"), "^$1")
            .replace(Regex("ルート|√"), "r")
            .replace(Regex("パーセント|％"), "%")
            .replace("（", "(").replace("）", ")")
        t = t.replace(Regex("(\\d)x(\\d)"), "$1*$2")
        if (!t.any { it.isDigit() }) return null
        if (!t.all { it.isDigit() || it in "+-*/^r%.()" }) return null
        return t
    }

    fun evaluate(expr: String): Double = Parser(expr).parse()

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "計算できません"
        if (abs(v - Math.round(v)) < 1e-9 && abs(v) < 1e15) return "%,d".format(Math.round(v))
        return "%.6f".format(v).trimEnd('0').trimEnd('.')
    }

    /** 式を声で読める形に (3+5 → 3たす5) */
    fun speakable(expr: String): String = expr
        .replace("+", "たす").replace("-", "ひく").replace("*", "かける").replace("/", "わる")
        .replace(Regex("\\^(\\d+)"), "の$1乗").replace("r", "ルート").replace("%", "パーセント")

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("式が読めません")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = power()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return base.pow(power())
            }
            return base
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == 'r') { i++; return sqrt(unary()) }
            var v = primary()
            if (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }

        fun primary(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("数字がありません")
            return s.substring(start, i).toDouble()
        }
    }
}

/** 漢数字・「万」「億」を含む数を算用数字に。 */
object JapaneseNumber {
    private val digit = mapOf('〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '両' to 2)
    private val small = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val big = mapOf('万' to 10_000L, '億' to 100_000_000L)

    fun replaceKanjiNumbers(text: String): String {
        var t = text.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        // 「3万5千」のような混在も含めて変換
        t = Regex("[0-9〇零一二三四五六七八九十百千万億]+").replace(t) { m ->
            val s = m.value
            if (s.all { it.isDigit() }) s else parse(s)?.toString() ?: s
        }
        return t
    }

    fun parse(s: String): Long? {
        var total = 0L
        var section = 0L
        var num = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    num = s.substring(i, j).toLong()
                    i = j
                    continue
                }
                c in digit -> num = digit.getValue(c).toLong()
                c in small -> { section += (if (num == 0L) 1 else num) * small.getValue(c); num = 0 }
                c in big -> { total += (section + num).let { if (it == 0L) 1 else it } * big.getValue(c); section = 0; num = 0 }
                else -> return null
            }
            i++
        }
        return total + section + num
    }
}
