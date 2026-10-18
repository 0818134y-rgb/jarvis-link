package com.zen.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesTest {
    private fun tool(text: String) = Rules.match(text)?.tool

    @Test fun light() {
        assertEquals("flashlight", tool("ライトつけて"))
        assertEquals(false, Rules.match("懐中電灯を消して")!!.args.getBoolean("on"))
    }

    @Test fun timerAndAlarm() {
        assertEquals(3, Rules.match("三分タイマーをセットして")!!.args.getInt("minutes"))
        val a = Rules.match("午後3時半にアラーム")!!.args
        assertEquals(15, a.getInt("hour"))
        assertEquals(30, a.getInt("minute"))
    }

    @Test fun routing() {
        val m = Rules.match("パソコンでメモ帳を開いて")!!
        assertEquals("send_to_device", m.tool)
        assertEquals("メモ帳を開いて", m.args.getString("command"))
        assertEquals("speak_on_device", tool("パソコンに「ご飯できたよ」と読み上げて"))
        assertEquals("get_device_clipboard", tool("パソコンのクリップボードを取り込んで"))
        assertEquals("flashlight", tool("スマホのライトをつけて"))
    }

    @Test fun apps() {
        assertEquals("カメラ", Rules.match("カメラアプリを開いて")!!.args.getString("name"))
        assertEquals("media_control", tool("一時停止"))
        assertEquals("browser_search", tool("ユーチューブで猫動画を検索"))
    }

    @Test fun freeTalkGoesToLlm() {
        assertNull(tool("量子コンピューターについて教えて"))
    }

    @Test fun extractJson() {
        val j = Agent.extractJson("はい ```json\n{\"tool\": \"open_app\", \"args\": {\"name\": \"YouTube\"}}\n```")
        assertEquals("open_app", j!!.getString("tool"))
        assertNull(Agent.extractJson("JSONではない返事"))
    }
}
