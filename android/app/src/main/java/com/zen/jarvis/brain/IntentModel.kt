package com.zen.jarvis.brain

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 自前で学習した意図理解モデル (brain/train.py で学習)。
 * 文字1〜3-gramの有無 → ソフトマックス回帰 → 意図ごとの確率。
 * normalize / ngrams は Python 側と完全に同じ処理でなければならない。
 */
class IntentModel(modelJson: String, weights: ByteArray) {

    val intents: List<String>
    private val vocab: HashMap<String, Int>
    private val nMin: Int
    private val nMax: Int
    private val w: FloatArray   // [C][V]
    private val b: FloatArray   // [C]
    private val numVocab: Int

    init {
        val j = JSONObject(modelJson)
        nMin = j.getInt("ngram_min")
        nMax = j.getInt("ngram_max")
        val ia = j.getJSONArray("intents")
        intents = List(ia.length()) { ia.getString(it) }
        val va = j.getJSONArray("vocab")
        numVocab = va.length()
        vocab = HashMap(numVocab * 2)
        for (i in 0 until numVocab) vocab[va.getString(i)] = i
        val fb = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val c = intents.size
        require(fb.remaining() == c * numVocab + c) { "weights.bin のサイズがモデルと一致しません" }
        w = FloatArray(c * numVocab).also { fb.get(it) }
        b = FloatArray(c).also { fb.get(it) }
    }

    data class Prediction(val intent: String, val prob: Float)

    /** 確率の高い順に上位 k 件。 */
    fun predict(text: String, k: Int = 3): List<Prediction> {
        val grams = ngrams(normalize(text), nMin, nMax)
        val idx = grams.mapNotNull { vocab[it] }
        val scale = if (idx.isEmpty()) 0f else (1.0 / sqrt(idx.size.toDouble())).toFloat()
        val c = intents.size
        val logits = FloatArray(c) { cls ->
            var s = b[cls]
            val base = cls * numVocab
            for (i in idx) s += w[base + i] * scale
            s
        }
        val max = logits.max()
        val e = DoubleArray(c) { exp((logits[it] - max).toDouble()) }
        val sum = e.sum()
        return (0 until c).sortedByDescending { e[it] }.take(k).map { Prediction(intents[it], (e[it] / sum).toFloat()) }
    }

    companion object {
        private const val REMOVE = " \t\n\r　、。,.!！?？・「」『』\"'()（）〜~…ー-"
        private val WAKE = listOf("じゃゔぃす", "じゃびす", "jarvis")

        fun normalize(text: String): String {
            var t = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            val sb = StringBuilder(t.length)
            for (ch in t) {
                if (ch in REMOVE) continue
                sb.append(if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch)
            }
            t = sb.toString()
            for (wk in WAKE) t = t.replace(wk, "")
            return t
        }

        fun ngrams(t: String, nMin: Int = 1, nMax: Int = 3): Set<String> {
            val s = "^$t$"
            val out = HashSet<String>()
            for (n in nMin..nMax) for (i in 0..s.length - n) out.add(s.substring(i, i + n))
            return out
        }
    }
}
