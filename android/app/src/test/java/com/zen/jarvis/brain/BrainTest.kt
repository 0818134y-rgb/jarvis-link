package com.zen.jarvis.brain

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrainTest {

    private val model by lazy {
        IntentModel(
            File("src/main/assets/brain/model.json").readText(),
            File("src/main/assets/brain/weights.bin").readBytes(),
        )
    }

    /** Python で学習した時の予測と、Kotlin の推論結果が一致すること。 */
    @Test fun matchesPythonPredictions() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("brain_cases.json")!!.readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("text")
            assertEquals("normalize: $text", c.getString("normalized"), IntentModel.normalize(text))
            val p = model.predict(text, 1)[0]
            assertEquals("intent: $text", c.getString("intent"), p.intent)
            assertEquals("prob: $text", c.getDouble("prob"), p.prob.toDouble(), 0.01)
        }
    }

    @Test fun calc() {
        fun calc(t: String) = Calc.format(Calc.evaluate(Calc.toExpression(t)!!))
        assertEquals("8", calc("3たす5は"))
        assertEquals("96", calc("12かける8"))
        assertEquals("1,024", calc("2の10乗"))
        assertEquals("33.333333", calc("100割る3"))
        assertEquals("1.414214", calc("ルート2"))
        assertEquals("35,000", calc("3万5千たす0"))
        assertEquals("25", calc("二十五"))
        assertNull(Calc.toExpression("計算して"))
    }

    @Test fun topics() {
        assertEquals("富士山", Knowledge.topicOf("富士山って何？"))
        assertEquals("量子コンピューター", Knowledge.topicOf("ジャーヴィス、量子コンピューターについて教えて"))
        assertEquals("織田信長", Knowledge.topicOf("織田信長は誰"))
        assertEquals("東京タワー", Knowledge.topicOf("東京タワーの高さは"))
    }

    @Test fun weatherSlots() {
        assertEquals("大阪", Weather.placeOf("明日の大阪の天気は"))
        assertEquals("札幌", Weather.placeOf("札幌は明日晴れる？"))
        assertNull(Weather.placeOf("今日の天気は"))
        assertNull(Weather.placeOf("明日雨降る？"))
        assertEquals(Weather.When.TOMORROW, Weather.whenOf("明日の天気"))
        assertEquals(Weather.When.WEEKEND, Weather.whenOf("週末の天気"))
    }

    @Test fun sites() {
        val site = com.zen.jarvis.tools.Sites.find("Amazonでイヤホンを探して")!!
        assertEquals("Amazon", site.name)
        assertEquals("イヤホン", com.zen.jarvis.tools.Sites.queryOf("Amazonでイヤホンを探して", site))
        assertEquals("猫動画", com.zen.jarvis.tools.Sites.queryOf("ユーチューブで猫動画を再生して", com.zen.jarvis.tools.Sites.find("ユーチューブ")!!))
        assertTrue(com.zen.jarvis.tools.Sites.looksLikeDomain("github.com"))
    }

    @Test fun decide() {
        assertTrue(Talk.decide("サイコロ振って").contains("出ました"))
        assertTrue(Talk.decide("コイン投げて").contains("です"))
    }
}
