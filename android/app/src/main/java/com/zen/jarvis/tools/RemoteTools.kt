package com.zen.jarvis.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.zen.jarvis.Jarvis
import com.zen.jarvis.link.LinkException
import com.zen.jarvis.link.Peer
import org.json.JSONObject

/** 他の端末(PC等)と連携するツール群。 */
fun ToolRegistry.registerRemoteTools(ctx: Context) {

    suspend fun peerOf(device: String): Peer =
        Jarvis.directory.resolveOrRefresh(device) ?: run {
            val known = Jarvis.directory.list().joinToString("、") { it.name }.ifBlank { "なし" }
            throw LinkException("端末「$device」が見つかりません(見つかっている端末: $known)")
        }

    add("list_devices", "連携できる他の端末の一覧を調べる") { _ ->
        val list = Jarvis.directory.list()
        if (list.isEmpty()) "連携できる端末はまだ見つかっていません。"
        else list.joinToString("、") { "${it.name}(${it.platform}, ${if (it.source == "mdns") "同じWi-Fi" else "手動接続"})" }
    }

    add("send_to_device", "他の端末(PC等)に自然文の指示を送って実行させる。例: PCでメモ帳を開く、PCの音楽を止める",
        Param("device", "端末名または種類 (例: pc, パソコン)"),
        Param("command", "その端末で実行させる指示 (例: メモ帳を開いて)")) { a ->
        val device = a.getString("device")
        val res = Jarvis.client.request(peerOf(device),
            JSONObject().put("type", "command").put("text", a.getString("command")))
        (if (res.optBoolean("ok")) "" else "失敗: ") + res.optString("text")
    }

    add("speak_on_device", "他の端末で文章を読み上げさせる(呼び出し・伝言)",
        Param("device", "端末名または種類"), Param("text", "読み上げる文章")) { a ->
        val device = a.getString("device")
        val res = Jarvis.client.request(peerOf(device),
            JSONObject().put("type", "speak").put("text", a.getString("text")), 30_000)
        if (res.optBoolean("ok")) "${device}で読み上げました。" else "失敗: ${res.optString("text")}"
    }

    add("share_page", "スマホのブラウザで開いているページを他の端末(PC等)のブラウザで開く", Param("device", "端末名または種類")) { a ->
        val url = com.zen.jarvis.access.JarvisAccessibility.instance?.currentUrl()
            ?: return@add "ブラウザで開いているページが見つかりませんでした(画面操作の許可とブラウザ表示が必要です)。"
        val device = a.getString("device")
        val res = Jarvis.client.request(peerOf(device),
            JSONObject().put("type", "tool").put("name", "open_url").put("args", JSONObject().put("url", url)), 20_000)
        if (res.optBoolean("ok")) "${device}でこのページを開きました。" else "失敗: ${res.optString("text")}"
    }

    add("send_clipboard", "この端末のクリップボードを他の端末へ送る", Param("device", "端末名または種類")) { a ->
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            ?: return@add "クリップボードを読めませんでした(JARVISの画面を開いた状態で試してください)。"
        val device = a.getString("device")
        val res = Jarvis.client.request(peerOf(device),
            JSONObject().put("type", "clipboard").put("text", text), 15_000)
        if (res.optBoolean("ok")) "${device}にクリップボードを送りました。" else "失敗: ${res.optString("text")}"
    }

    add("get_device_clipboard", "他の端末(PC等)のクリップボードをこの端末に取り込む", Param("device", "端末名または種類")) { a ->
        val device = a.getString("device")
        val res = Jarvis.client.request(peerOf(device),
            JSONObject().put("type", "tool").put("name", "get_clipboard").put("args", JSONObject()), 15_000)
        if (!res.optBoolean("ok")) return@add "失敗: ${res.optString("text")}"
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("JARVIS", res.optString("text")))
        "${device}のクリップボードを取り込みました。"
    }
}
