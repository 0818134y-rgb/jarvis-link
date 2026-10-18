package com.zen.jarvis.notify

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zen.jarvis.Jarvis
import kotlinx.coroutines.launch
import org.json.JSONObject

data class NotificationItem(val app: String, val title: String, val text: String, val time: Long)

/** 届いた通知を覚えておく (読み上げ・PC転送用)。 */
object NotificationStore {
    @Volatile var connected = false
    private val items = ArrayDeque<NotificationItem>()

    @Synchronized fun add(n: NotificationItem) {
        items.addLast(n)
        while (items.size > 30) items.removeFirst()
    }

    @Synchronized fun recent(k: Int): List<NotificationItem> = items.toList().takeLast(k).reversed()
}

/**
 * 通知へのアクセス (設定 > 通知 > 通知へのアクセス) を許可すると動く。
 * 通知を記録し、設定でオンならPCへ転送する。
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationStore.connected = true
    }

    override fun onListenerDisconnected() {
        NotificationStore.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().take(200)
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val item = NotificationItem(app, title, text, sbn.postTime)
        NotificationStore.add(item)

        if (!Jarvis.prefs.forwardNotifications) return
        Jarvis.scope.launch {
            for (peer in Jarvis.directory.list().filter { it.platform == "windows" }) {
                runCatching {
                    Jarvis.client.request(peer, JSONObject().put("type", "notify")
                        .put("app", app).put("title", title).put("text", text), 15_000)
                }
            }
        }
    }
}
