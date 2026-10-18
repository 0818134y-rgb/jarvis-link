package com.zen.jarvis.tools

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 端末そのものを操作するツール群 (アプリ起動・ライト・音量・タイマー・電話など)。 */
fun ToolRegistry.registerPhoneTools(ctx: Context) {
    val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    add("open_app", "アプリを開く", Param("name", "アプリ名 (例: YouTube, LINE, カメラ)")) { a ->
        val name = a.getString("name")
        val app = AppFinder.find(ctx, name) ?: return@add "「$name」というアプリが見つかりませんでした。"
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return@add "${app.label}は起動できません。"
        if (startActivitySafe(ctx, intent)) "${app.label}を開きました。" else "${app.label}を開けませんでした。"
    }

    add("flashlight", "ライト(懐中電灯)をつける/消す", Param("on", "true でつける、false で消す", type = "boolean")) { a ->
        val on = a.optBoolean("on", true)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@add "この端末にはライトがありません。"
        cm.setTorchMode(id, on)
        if (on) "ライトをつけました。" else "ライトを消しました。"
    }

    add("set_volume", "メディア音量を変える",
        Param("action", "up / down / mute / unmute / set のいずれか"),
        Param("level", "action=set の時の音量 0〜100", required = false, type = "integer")) { a ->
        val stream = AudioManager.STREAM_MUSIC
        when (a.optString("action", "up")) {
            "up" -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val max = audio.getStreamMaxVolume(stream)
                val lv = a.optInt("level", 50).coerceIn(0, 100)
                audio.setStreamVolume(stream, max * lv / 100, AudioManager.FLAG_SHOW_UI)
            }
            else -> return@add "音量の操作がわかりません。"
        }
        val pct = 100 * audio.getStreamVolume(stream) / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        "音量を${pct}%にしました。"
    }

    add("media_control", "音楽や動画の再生を操作する", Param("action", "play / pause / play_pause / next / previous")) { a ->
        val code = when (a.optString("action")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        "操作しました。"
    }

    add("set_timer", "タイマーをセットする",
        Param("minutes", "分", required = false, type = "integer"), Param("seconds", "秒", required = false, type = "integer"),
        Param("label", "名前", required = false)) { a ->
        val total = a.optInt("minutes", 0) * 60 + a.optInt("seconds", 0)
        if (total <= 0) return@add "時間がわかりませんでした。"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "JARVIS"))
        if (startActivitySafe(ctx, i)) "${formatDuration(total)}のタイマーをセットしました。" else "時計アプリが見つかりません。"
    }

    add("set_alarm", "アラームをセットする",
        Param("hour", "時 (0〜23)", type = "integer"), Param("minute", "分", required = false, type = "integer"),
        Param("label", "名前", required = false)) { a ->
        val h = a.getInt("hour").coerceIn(0, 23)
        val m = a.optInt("minute", 0).coerceIn(0, 59)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "JARVIS"))
        if (startActivitySafe(ctx, i)) "${h}時${m}分にアラームをセットしました。" else "時計アプリが見つかりません。"
    }

    add("open_url", "ブラウザでURLを開く", Param("url", "URL")) { a ->
        var url = a.getString("url")
        if (!url.startsWith("http")) url = "https://$url"
        startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        "開きました。"
    }

    add("browser_search", "ブラウザやYouTube・地図アプリで検索結果を画面に開く", Param("query", "検索語"),
        Param("site", "google / youtube / map のいずれか", required = false)) { a ->
        val q = a.getString("query")
        val ok = when (a.optString("site", "google")) {
            "youtube" -> startActivitySafe(ctx, Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            "map" -> startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            else -> startActivitySafe(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
                startActivitySafe(ctx, Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        if (ok) "「$q」を検索しました。" else "検索できませんでした。"
    }

    add("navigate", "目的地までの道案内を開始する", Param("destination", "目的地")) { a ->
        val dest = a.getString("destination")
        val ok = startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))) ||
            startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))
        if (ok) "${dest}への案内を開始します。" else "地図アプリが見つかりません。"
    }

    add("play_music", "曲名やアーティスト名で音楽を再生する", Param("query", "曲名・アーティスト名")) { a ->
        val q = a.getString("query")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (startActivitySafe(ctx, i)) "「$q」を再生します。" else "音楽アプリが見つかりません。"
    }

    add("call", "電話をかける", Param("target", "電話番号または連絡先の名前"), risk = Risk.CONFIRM) { a ->
        val target = a.getString("target")
        val number = if (target.any { it.isDigit() } && target.count { it.isDigit() } >= 3) target
        else lookupNumber(ctx, target) ?: return@add "連絡先に「$target」が見つかりませんでした。"
        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivitySafe(ctx, Intent(action, Uri.parse("tel:" + Uri.encode(number))))
        if (canCall) "${target}に発信します。" else "${target}の番号を表示しました。発信ボタンを押してください。"
    }

    add("compose_sms", "SMSの下書きを開く(送信はユーザーが押す)",
        Param("target", "電話番号または連絡先の名前"), Param("message", "本文")) { a ->
        val target = a.getString("target")
        val number = if (target.count { it.isDigit() } >= 3) target else lookupNumber(ctx, target) ?: ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", a.getString("message"))
        if (startActivitySafe(ctx, i)) "メッセージの下書きを開きました。" else "メッセージアプリが見つかりません。"
    }

    add("open_settings", "設定画面を開く", Param("page", "wifi / bluetooth / display / sound / battery / location / apps / general")) { a ->
        val action = when (a.optString("page")) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        startActivitySafe(ctx, Intent(action))
        "設定を開きました。"
    }

    add("battery_status", "バッテリー残量を調べる") { _ ->
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        "バッテリーは${pct}%です${if (bm.isCharging) "(充電中)" else ""}。"
    }

    add("get_time", "現在の日付と時刻を調べる") { _ ->
        SimpleDateFormat("M月d日(E) H時m分", Locale.JAPAN).format(Date()) + "です。"
    }

    add("get_clipboard", "この端末のクリップボードの文字を取得する") { _ ->
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            ?: "クリップボードを読めませんでした(Androidの制限により、JARVISの画面を開いている時のみ読めます)。"
    }

    add("set_clipboard", "この端末のクリップボードに文字を入れる", Param("text", "文字")) { a ->
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("JARVIS", a.getString("text")))
        "クリップボードに入れました。"
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return buildString {
        if (m > 0) append("${m}分")
        if (s > 0) append("${s}秒")
    }
}

private fun lookupNumber(ctx: Context, name: String): String? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    ctx.contentResolver.query(uri, proj, sel, arrayOf("%${name.trim()}%"), null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return null
}
