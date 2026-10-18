package com.zen.jarvis.link

import android.util.Log
import com.zen.jarvis.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 他端末からの依頼を受けるサーバー。
 * handler(request, peerName) は {"ok": Boolean, "text": String} を返す。
 */
class LinkServer(
    port: Int,
    private val prefs: Prefs,
    private val handler: suspend (JSONObject, String) -> JSONObject,
) : WebSocketServer(InetSocketAddress(port)) {

    private class Session(val nonce: String) {
        @Volatile var authed = false
        @Volatile var peer = "?"
    }

    private val sessions = ConcurrentHashMap<WebSocket, Session>()
    private val failUntil = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        isReuseAddr = true
        connectionLostTimeout = 60
    }

    override fun onStart() {
        Log.i(TAG, "listening on $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
        if ((failUntil[ip] ?: 0L) > System.currentTimeMillis()) {
            conn.close()
            return
        }
        val nonce = Protocol.nonce()
        sessions[conn] = Session(nonce)
        conn.send(
            JSONObject()
                .put("type", "challenge")
                .put("v", Protocol.VERSION)
                .put("nonce", nonce)
                .put("device", Protocol.self(prefs))
                .toString()
        )
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        sessions.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val s = sessions[conn] ?: return
        val msg = try {
            JSONObject(message)
        } catch (e: JSONException) {
            return
        }
        if (!s.authed) {
            val token = prefs.token
            if (msg.optString("type") == "auth" &&
                Protocol.constEquals(msg.optString("mac"), Protocol.mac(token, s.nonce))
            ) {
                s.authed = true
                s.peer = msg.optJSONObject("device")?.optString("name")?.ifBlank { null }
                    ?: conn.remoteSocketAddress?.address?.hostAddress ?: "?"
                conn.send(
                    JSONObject().put("type", "auth_ok")
                        .put("mac", Protocol.mac(token, msg.optString("cnonce"))).toString()
                )
            } else {
                val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
                failUntil[ip] = System.currentTimeMillis() + 3000
                Log.w(TAG, "auth failed from $ip")
                conn.send(JSONObject().put("type", "auth_fail").toString())
                conn.close()
            }
            return
        }
        val id = msg.optString("id")
        scope.launch {
            val res = try {
                handler(msg, s.peer)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("text", "エラー: ${e.message}")
            }
            res.put("type", "result").put("id", id)
            if (conn.isOpen) conn.send(res.toString())
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.w(TAG, "server error", ex)
    }

    fun shutdown() {
        try {
            stop(1000)
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "JarvisLinkServer"
    }
}
