package com.zen.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    @Test fun variants() {
        assertEquals("ライトつけて", WakeWord.detect("ジャーヴィス、ライトつけて")!!.command)
        assertEquals("天気は", WakeWord.detect("じゃあ ビス 天気 は")!!.command)
        assertEquals("", WakeWord.detect("ジャービス")!!.command)
        assertEquals("今何時", WakeWord.detect("ねえジャーヴィス今何時")!!.command)
        assertEquals("", WakeWord.detect("Jarvis")!!.command)
        assertEquals("音量上げて", WakeWord.detect("ちゃあ びず 音量 上げ て")!!.command)
    }

    @Test fun rejects() {
        assertNull(WakeWord.detect("サービスを開いて"))
        assertNull(WakeWord.detect("今日はいい天気"))
    }

    @Test fun extraWords() {
        assertEquals("ライト", WakeWord.detect("邪 ビスケ ライト", listOf("邪ビスケ"))!!.command)
    }
}
