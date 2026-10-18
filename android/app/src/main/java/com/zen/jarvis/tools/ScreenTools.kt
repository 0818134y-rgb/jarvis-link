package com.zen.jarvis.tools

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.zen.jarvis.Jarvis
import com.zen.jarvis.access.JarvisAccessibility

private const val NEED_ACCESS = "画面操作を使うには、設定のユーザー補助で「JARVIS 端末操作」をオンにしてください。"

/** ユーザー補助サービス経由の画面操作ツール。 */
fun ToolRegistry.registerScreenTools() {
    fun svc() = JarvisAccessibility.instance

    add("tap_text", "画面に表示されているボタンや文字をタップする", Param("text", "タップしたい表示文字 (例: 送信, OK, 設定)"),
        risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        val t = a.getString("text")
        if (s.tapText(t)) "「$t」をタップしました。" else "画面に「$t」が見つかりませんでした。"
    }

    add("scroll", "画面をスクロールする", Param("direction", "down(下へ進む) / up(上へ戻る)"),
        risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        if (s.scroll(a.optString("direction", "down") != "up")) "スクロールしました。" else "スクロールできませんでした。"
    }

    add("type_text", "今選択中の入力欄に文字を入力する",
        Param("text", "入力する文字"), Param("enter", "true なら入力後に確定(検索/送信)", required = false, type = "boolean"),
        risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        if (!s.typeText(a.getString("text"))) return@add "入力欄が見つかりませんでした。"
        if (a.optBoolean("enter", false)) s.pressEnter()
        "入力しました。"
    }

    add("read_screen", "今の画面に表示されている文字を読み取る([ ]はタップできる要素)",
        risk = Risk.REMOTE_BLOCKED) { _ ->
        val s = svc() ?: return@add NEED_ACCESS
        val text = s.readScreen()
        if (text.isBlank()) "画面から文字を読み取れませんでした。" else "アプリ ${s.currentPackage()} の画面: $text"
    }

    add("list_items", "画面の押せる項目に番号を振って読み上げる(「3番をタップ」で使う)", risk = Risk.REMOTE_BLOCKED) { _ ->
        val s = svc() ?: return@add NEED_ACCESS
        val items = s.listItems(30)
        if (items.isEmpty()) "押せる項目が見つかりませんでした。"
        else {
            val head = items.take(12).mapIndexed { i, it -> "${i + 1}番、${it.label}" }.joinToString("。") + "。"
            val rest = if (items.size > 12) "ほかに${items.size - 12}個あります。番号で選べます。" else ""
            "${items.size}個あります。$head$rest"
        }
    }

    add("tap_item", "list_items で読み上げた番号の項目をタップする", Param("number", "番号", type = "integer"),
        risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        if (s.lastItems.isEmpty()) s.listItems()
        val n = a.getInt("number")
        val item = s.tapItem(n) ?: return@add "${n}番の項目がありません。「画面の項目を教えて」で番号を確認してください。"
        "${n}番、${item.label}をタップしました。"
    }

    add("long_press", "画面の項目を長押しする", Param("text", "長押しする項目の文字"), risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        val t = a.getString("text")
        if (s.longPressText(t)) "「$t」を長押ししました。" else "画面に「$t」が見つかりませんでした。"
    }

    add("swipe", "画面を左右にスワイプする (写真やページをめくる)", Param("direction", "left(次へ) / right(前へ)"),
        risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        if (s.swipeHorizontal(a.optString("direction", "left") != "right")) "スワイプしました。" else "スワイプできませんでした。"
    }

    add("edit_action", "入力中の欄でコピー・貼り付け・切り取り・全選択・確定をする",
        Param("action", "copy / paste / cut / select_all / enter"), risk = Risk.REMOTE_BLOCKED) { a ->
        val s = svc() ?: return@add NEED_ACCESS
        val names = mapOf("copy" to "コピー", "paste" to "貼り付け", "cut" to "切り取り", "select_all" to "全選択", "enter" to "確定")
        val act = a.getString("action")
        if (s.editAction(act)) "${names[act] ?: act}しました。" else "入力欄が選ばれていないため${names[act] ?: act}できませんでした。"
    }

    add("current_url", "ブラウザで今開いているページのURLを調べる") { _ ->
        val s = svc() ?: return@add NEED_ACCESS
        s.currentUrl() ?: "ブラウザのページが開かれていないようです。"
    }

    add("browser_control", "ブラウザの操作 (new_tab / back / top / bottom / reload)",
        Param("action", "new_tab / back / top / bottom / reload")) { a ->
        val s = svc()
        when (a.getString("action")) {
            "new_tab" -> {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))
                    .putExtra(android.provider.Browser.EXTRA_CREATE_NEW_TAB, true)
                startActivitySafe(Jarvis.app, i)
                "新しいタブを開きました。"
            }
            "back" -> { s?.global(AccessibilityService.GLOBAL_ACTION_BACK) ?: return@add NEED_ACCESS; "前のページに戻りました。" }
            "top", "bottom" -> {
                s ?: return@add NEED_ACCESS
                repeat(8) { s.scroll(a.getString("action") == "bottom") }
                if (a.getString("action") == "top") "ページの一番上に移動しました。" else "ページの一番下に移動しました。"
            }
            "reload" -> {
                s ?: return@add NEED_ACCESS
                if (s.tapAnyOf(listOf("再読み込み", "更新", "Refresh", "Reload"))) "再読み込みしました。"
                else "スマホのブラウザでは再読み込みボタンが見つかりませんでした。画面を下に引っ張ると更新できます。"
            }
            else -> "スマホのブラウザではその操作に対応していません。"
        }
    }

    add("go_back", "戻る") { _ ->
        svc()?.global(AccessibilityService.GLOBAL_ACTION_BACK) ?: return@add NEED_ACCESS
        "戻りました。"
    }

    add("go_home", "ホーム画面に戻る") { _ ->
        svc()?.global(AccessibilityService.GLOBAL_ACTION_HOME) ?: return@add NEED_ACCESS
        "ホーム画面に戻りました。"
    }

    add("recent_apps", "最近使ったアプリの一覧を開く") { _ ->
        svc()?.global(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: return@add NEED_ACCESS
        "最近のアプリを開きました。"
    }

    add("open_notifications", "通知を開く", risk = Risk.REMOTE_BLOCKED) { _ ->
        svc()?.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: return@add NEED_ACCESS
        "通知を開きました。"
    }

    add("quick_settings", "クイック設定を開く") { _ ->
        svc()?.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: return@add NEED_ACCESS
        "クイック設定を開きました。"
    }

    add("lock_screen", "画面をロックする") { _ ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@add "この端末のAndroidでは使えません。"
        svc()?.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: return@add NEED_ACCESS
        "画面をロックしました。"
    }

    add("screenshot", "スクリーンショットを撮る") { _ ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@add "この端末のAndroidでは使えません。"
        svc()?.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) ?: return@add NEED_ACCESS
        "スクリーンショットを撮りました。"
    }
}
