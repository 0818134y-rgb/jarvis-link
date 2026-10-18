package com.zen.jarvis.link

import com.zen.jarvis.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 他端末への使い捨て接続: 接続 → 相互認証 → 1リクエスト → 切断。 */
class LinkClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)  // LAN / Tailscale へ直結
        .build()

    private class Session(val ws: WebSocket, val inbox: Channel<String>)

    private fun open(host: String, port: Int): Session {
        val inbox = Channel<String>(Channel.UNLIMITED)
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val req = Request.Builder().url("ws://$h:$port").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                inbox.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.close(t)
            }
        })
        return Session(ws, inbox)
    }

    private suspend fun next(s: Session, timeoutMs: Long): JSONObject = try {
        JSONObject(withTimeout(timeoutMs) { s.inbox.receive() })
    } catch (e: TimeoutCancellationException) {
        throw LinkException("相手からの応答がありません")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ClosedReceiveChannelException) {
        throw LinkException("接続が切れました")
    } catch (e: LinkException) {
        throw e
    } catch (e: Exception) {
        throw LinkException("接続できません: ${e.message ?: e.javaClass.simpleName}")
    }

    /** 認証なしで相手の名前と種類だけ確認する。 */
    suspend fun probe(host: String, port: Int): JSONObject {
        val s = open(host, port)
        try {
            return next(s, 6000)
        } finally {
            s.ws.close(1000, null)
        }
    }

    suspend fun request(peer: Peer, payload: JSONObject, timeoutMs: Long = 90_000): JSONObject {
        val s = open(peer.host, peer.port)
        try {
            val ch = next(s, 8000)
            if (ch.optString("type") != "challenge") throw LinkException("相手がJARVIS Linkではありません")
            val token = prefs.token
            val cnonce = Protocol.nonce()
            s.ws.send(
                JSONObject()
                    .put("type", "auth")
                    .put("mac", Protocol.mac(token, ch.getString("nonce")))
                    .put("cnonce", cnonce)
                    .put("device", Protocol.self(prefs))
                    .toString()
            )
            val ok = next(s, 8000)
            if (ok.optString("type") != "auth_ok" ||
                !Protocol.constEquals(ok.optString("mac"), Protocol.mac(token, cnonce))
            ) {
                throw LinkException("認証に失敗しました。トークンがPCと同じか確認してください")
            }
            val id = payload.optString("id").ifBlank { UUID.randomUUID().toString().take(12) }
            payload.put("id", id)
            s.ws.send(payload.toString())
            var res: JSONObject
            do {
                res = next(s, timeoutMs)
            } while (!(res.optString("type") == "result" && res.optString("id") == id))
            return res
        } finally {
            s.ws.close(1000, null)
        }
    }
}
