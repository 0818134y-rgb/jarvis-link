package com.zen.jarvis.tools

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import com.zen.jarvis.access.JarvisAccessibility
import com.zen.jarvis.notify.NotificationStore
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId

/** アプリをまたいだ操作: サイト内検索・予定・メール・投稿・写真・スマホを鳴らす・通知。 */
fun ToolRegistry.registerAppTools(ctx: Context) {

    add("site_search", "サイトやアプリの中で検索する (Amazon, 楽天, YouTube, メルカリ, 食べログ など)",
        Param("site", "サイト名"), Param("query", "検索語")) { a ->
        val site = Sites.find(a.getString("site")) ?: return@add "「${a.getString("site")}」は対応していないサイトです。"
        val q = a.getString("query")
        // YouTube と地図は専用アプリがあればアプリで開く
        val ok = when (site.name) {
            "YouTube" -> startActivitySafe(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)) ||
                startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Sites.searchUrl(site, q))))
            "Googleマップ" -> startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            "Spotify" -> startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))) ||
                startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Sites.searchUrl(site, q))))
            else -> startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Sites.searchUrl(site, q))))
        }
        if (ok) "${site.name}で「$q」を検索しました。" else "${site.name}を開けませんでした。"
    }

    add("calendar_add", "カレンダーに予定を追加する画面を開く(保存はユーザーが押す)",
        Param("title", "予定の名前"), Param("start", "開始日時 yyyy-MM-ddTHH:mm"),
        Param("minutes", "長さ(分)", required = false, type = "integer")) { a ->
        val start = LocalDateTime.parse(a.getString("start"))
        val begin = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = begin + a.optInt("minutes", 60) * 60_000L
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        if (startActivitySafe(ctx, i)) "${start.monthValue}月${start.dayOfMonth}日${start.hour}時${if (start.minute > 0) "${start.minute}分" else ""}の「${a.getString("title")}」を入力しました。保存を押してください。"
        else "カレンダーアプリが見つかりません。"
    }

    add("compose_mail", "メールの下書きを開く(送信はユーザーが押す)",
        Param("to", "宛先(メールアドレスや名前)", required = false), Param("subject", "件名", required = false),
        Param("body", "本文", required = false)) { a ->
        val to = a.optString("to").takeIf { it.contains("@") } ?: ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, a.optString("body"))
        if (startActivitySafe(ctx, i)) "メールの作成画面を開きました。" else "メールアプリが見つかりません。"
    }

    add("post_x", "X(旧Twitter)の投稿画面を開く(投稿はユーザーが押す)", Param("text", "投稿する文")) { a ->
        val url = "https://x.com/intent/post?text=" + Uri.encode(a.getString("text"))
        if (startActivitySafe(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "投稿画面を開きました。内容を確認して投稿してください。"
        else "開けませんでした。"
    }

    add("take_photo", "カメラを開いて写真(または動画)を撮る",
        Param("video", "true なら動画", required = false, type = "boolean")) { a ->
        val video = a.optBoolean("video", false)
        val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (!startActivitySafe(ctx, i)) return@add "カメラアプリが見つかりません。"
        val svc = JarvisAccessibility.instance ?: return@add "カメラを開きました。シャッターを押してください。"
        delay(2500)  // カメラの起動待ち
        val shutter = if (video) listOf("録画", "動画を撮影", "Record", "録画開始", "シャッター")
        else listOf("シャッター", "写真を撮影", "撮影", "Shutter", "Take photo", "写真を撮る", "キャプチャ")
        if (svc.tapAnyOf(shutter)) (if (video) "録画を開始しました。" else "撮影しました。")
        else "カメラを開きました。シャッターボタンが見つからなかったので押してください。"
    }

    add("find_phone", "スマホを最大音量で鳴らして場所を知らせる") { _ ->
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ring = RingtoneManager.getRingtone(ctx, uri)
        ring?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        ring?.play()
        delay(15_000)
        ring?.stop()
        "スマホを15秒間鳴らしました。"
    }

    add("read_notifications", "最近届いた通知を読み上げる") { _ ->
        val list = NotificationStore.recent(5)
        when {
            !NotificationStore.connected -> {
                JarvisAccessibility.instance?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "通知を開きました。読み上げるには設定で「通知へのアクセス」をJARVISに許可してください。"
            }
            list.isEmpty() -> "新しい通知はありません。"
            else -> "通知は${list.size}件です。" + list.joinToString("。") { "${it.app}、${it.title} ${it.text}".trim() } + "。"
        }
    }
}
