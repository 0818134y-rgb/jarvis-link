package com.zen.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.zen.jarvis.access.JarvisAccessibility

/**
 * バックグラウンドから画面を開くための共通処理。
 * Android 10+ は裏からの Activity 起動が制限されるが、システムにバインドされた
 * AccessibilityService を持つアプリは例外なので、可能ならそのコンテキストから起動する。
 */
fun startActivitySafe(context: Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ctx: Context = JarvisAccessibility.instance ?: context
    return try {
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

data class AppEntry(val label: String, val packageName: String)

/** 起動可能なアプリを名前のゆれに強い形で探す。 */
object AppFinder {
    // 読み → 表示名でよく使われる綴り
    private val aliases = mapOf(
        "ユーチューブ" to "youtube", "ライン" to "line", "クローム" to "chrome",
        "グーグルクローム" to "chrome", "ジーメール" to "gmail", "gメール" to "gmail",
        "マップ" to "maps", "地図" to "maps", "グーグルマップ" to "maps",
        "スポティファイ" to "spotify", "ツイッター" to "x", "エックス" to "x",
        "インスタ" to "instagram", "インスタグラム" to "instagram", "ティックトック" to "tiktok",
        "ディスコード" to "discord", "設定" to "settings", "セッティング" to "settings",
        "カメラ" to "camera", "写真" to "photos", "フォト" to "photos", "ギャラリー" to "gallery",
        "時計" to "clock", "カレンダー" to "calendar", "電卓" to "calculator", "計算機" to "calculator",
        "メッセージ" to "messages", "電話" to "phone", "連絡先" to "contacts",
        "プレイストア" to "play store", "アマゾン" to "amazon", "ネットフリックス" to "netflix",
        "ペイペイ" to "paypay", "スラック" to "slack", "ノーション" to "notion", "ズーム" to "zoom",
    )

    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.packageName }
    }

    fun find(context: Context, query: String): AppEntry? {
        val apps = installed(context)
        val q = norm(query)
        val alias = aliases[q] ?: aliases.entries.firstOrNull { q.contains(norm(it.key)) }?.value
        val keys = listOfNotNull(q, alias?.let { norm(it) })
        for (k in keys) apps.firstOrNull { norm(it.label) == k }?.let { return it }
        for (k in keys) apps.firstOrNull { norm(it.label).contains(k) || (it.label.length >= 2 && k.contains(norm(it.label))) }?.let { return it }
        for (k in keys) apps.firstOrNull { it.packageName.lowercase().contains(k) && k.length >= 3 }?.let { return it }
        // 最後の手段: 編集距離
        val best = apps.minByOrNull { levenshtein(norm(it.label), q) } ?: return null
        val d = levenshtein(norm(best.label), q)
        return if (d <= maxOf(1, q.length / 3)) best else null
    }

    private fun norm(s: String) = s.lowercase().replace(" ", "").replace("　", "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
