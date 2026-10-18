package com.zen.jarvis.tools

import android.accessibilityservice.AccessibilityService
import android.os.Build
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
