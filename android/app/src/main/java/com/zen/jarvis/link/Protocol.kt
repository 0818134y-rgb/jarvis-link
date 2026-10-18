package com.zen.jarvis.link

import com.zen.jarvis.Prefs
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** protocol/PROTOCOL.md と同じ定義。 */
object Protocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_jarvislink._tcp"
    const val PLATFORM = "android"

    private val random = SecureRandom()

    fun nonce(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    fun mac(token: String, message: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return m.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun constEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun self(prefs: Prefs): JSONObject =
        JSONObject().put("name", prefs.deviceName).put("platform", PLATFORM)

    val PLATFORM_ALIASES = mapOf(
        "windows" to listOf("pc", "パソコン", "ピーシー", "コンピューター", "コンピュータ", "windows", "デスクトップ", "ノートパソコン"),
        "android" to listOf("スマホ", "スマートフォン", "携帯", "アンドロイド", "android", "phone", "タブレット"),
    )
}

class LinkException(message: String) : Exception(message)

data class Peer(
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    val source: String,  // "mdns" | "manual"
    val seen: Long = System.currentTimeMillis(),
)
