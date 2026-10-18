package com.zen.jarvis.brain

import android.content.Context
import org.json.JSONArray

/** ジャーヴィスが覚えておくこと (名前・住んでいる街・誕生日・メモ)。端末内にだけ保存。 */
class Memory(context: Context) {
    private val sp = context.getSharedPreferences("brain_memory", Context.MODE_PRIVATE)

    var userName: String
        get() = sp.getString("userName", "") ?: ""
        set(v) = sp.edit().putString("userName", v).apply()

    var homeCity: String
        get() = sp.getString("homeCity", "") ?: ""
        set(v) = sp.edit().putString("homeCity", v).apply()

    /** "MM-dd" 形式 */
    var birthday: String
        get() = sp.getString("birthday", "") ?: ""
        set(v) = sp.edit().putString("birthday", v).apply()

    val notes: List<String>
        get() {
            val a = JSONArray(sp.getString("notes", "[]"))
            return List(a.length()) { a.getString(it) }
        }

    fun addNote(note: String) {
        val list = (notes + note).takeLast(50)
        sp.edit().putString("notes", JSONArray(list).toString()).apply()
    }

    fun clearNotes() = sp.edit().putString("notes", "[]").apply()

    /** 呼びかけ。名前を教わっていれば名前、なければ「ボス」。 */
    val callName: String get() = userName.ifBlank { "ボス" }
}
