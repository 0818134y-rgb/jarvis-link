package com.zen.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zen.jarvis.link.LinkServer
import com.zen.jarvis.link.Protocol
import com.zen.jarvis.tools.Origin
import com.zen.jarvis.voice.VoiceLoop
import com.zen.jarvis.voice.WakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 常駐サービス: 音声セッション、常時待ち受け、端末連携サーバーを担当。
 * 通知の「話しかける」ボタンからも起動できる。
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_LISTEN = "com.zen.jarvis.LISTEN"
        const val ACTION_STOP = "com.zen.jarvis.STOP"
        const val ACTION_RELOAD = "com.zen.jarvis.RELOAD"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1
        private const val TAG = "JarvisService"

        private const val FOLLOW_UP_MS = 7000L
        val YES = Regex("はい|お願い|いいよ|オッケー|オーケー|ok|実行|どうぞ|やって|イエス", RegexOption.IGNORE_CASE)
        val NO = Regex("いいえ|だめ|ダメ|やめ|キャンセル|ノー|中止|待って")

        @Volatile
        var running = false
            private set

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, JarvisService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = Mutex()
    private var server: LinkServer? = null
    private var listenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Android 14+: マイク型の常駐はアプリが前面にある時しか開始できない
            Log.w(TAG, "cannot start foreground", e)
            stopSelf()
            return
        }
        running = true
        acquireLocks()
        Jarvis.tools.confirm = { desc -> confirmByVoice(desc) }
        Jarvis.tools.allowRemoteDangerous = { Jarvis.prefs.allowRemoteDangerous }
        Jarvis.brain.onToolCall = { name, args -> Jarvis.log("tool", "$name $args") }
        scope.launch(Dispatchers.IO) { startLink() }
        setupVoice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> listenSession()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> scope.launch(Dispatchers.IO) {
                stopLink()
                startLink()
            }
        }
        updateContinuous()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        fallbackJob?.cancel()
        listenJob?.cancel()
        voice?.release()
        Jarvis.setStatus("停止中")
        val s = server
        server = null
        Thread { s?.shutdown() }.start()
        Jarvis.directory.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 音声 ----------

    private var voice: VoiceLoop? = null
    private var fallbackJob: Job? = null

    private fun setupVoice() {
        val v = VoiceLoop(
            context = this,
            extraWakeWords = { Jarvis.prefs.extraWakeWords.split('\n').map { it.trim() }.filter { it.isNotEmpty() } },
            onHeard = { Jarvis.setHeard(it) },
            onWake = { if (!session.isLocked) Jarvis.setStatus("聞いています") },
            onUtterance = { cmd, _ -> onVoice(cmd) },
        )
        voice = v
        scope.launch {
            Jarvis.setStatus("音声モデル準備中")
            val ok = v.prepare()
            if (!ok) Jarvis.log("system", "端末内の音声モデルが見つからないため、システムの音声認識で待ち受けます。")
            Jarvis.setStatus("待機中")
            updateContinuous()
        }
    }

    /** 呼びかけ(または会話モード中の発話)を受け取った */
    private fun onVoice(cmd: String) {
        if (session.isLocked) return
        scope.launch {
            session.withLock {
                voice?.stop()  // 自分の声を聞かないよう、返答が終わるまでマイクを止める
                var c = cmd
                if (c.isBlank()) {
                    Jarvis.tts.speak("はい")
                    Jarvis.setStatus("聞いています")
                    c = if (Jarvis.prefs.preciseCommand || voice?.isReady != true) Jarvis.stt.listenOnce() ?: "" else ""
                    if (c.isBlank() && voice?.isReady == true && !Jarvis.prefs.preciseCommand) {
                        resumeListening(followUp = true)
                        return@withLock
                    }
                }
                if (c.isNotBlank()) runCommand(c) else Jarvis.setStatus("待ち受け中")
                resumeListening(followUp = c.isNotBlank())
            }
        }
    }

    private fun resumeListening(followUp: Boolean) {
        val v = voice ?: return
        if (!Jarvis.prefs.continuous || !v.isReady) {
            Jarvis.setStatus("待機中")
            updateContinuous()
            return
        }
        v.start()
        if (followUp) v.openFollowUp(FOLLOW_UP_MS)
        Jarvis.setStatus(if (followUp) "聞いています" else "待ち受け中")
        if (followUp) scope.launch {
            delay(FOLLOW_UP_MS)
            if (!session.isLocked && Jarvis.status.value == "聞いています") Jarvis.setStatus("待ち受け中")
        }
    }

    /** ボタン・通知から話しかける */
    private fun listenSession() {
        if (listenJob?.isActive == true || session.isLocked) return
        listenJob = scope.launch {
            session.withLock {
                voice?.stop()
                fallbackJob?.cancel()
                Jarvis.tts.stop()
                Jarvis.setStatus("聞いています")
                val text = Jarvis.stt.listenOnce()
                if (text != null) runCommand(text) else Jarvis.setStatus("待機中")
                resumeListening(followUp = text != null)
            }
        }
    }

    private suspend fun runCommand(text: String) {
        Jarvis.log("you", text)
        Jarvis.setStatus("考え中")
        val reply = withContext(Dispatchers.Default) { Jarvis.brain.handle(text, Origin.Local) }
        Jarvis.log("jarvis", reply)
        Jarvis.setStatus("話しています")
        Jarvis.tts.speak(reply)
        Jarvis.setStatus("待機中")
    }

    private fun updateContinuous() {
        val v = voice
        if (Jarvis.prefs.continuous) {
            if (v != null && v.isReady) {
                fallbackJob?.cancel()
                if (!v.running && !session.isLocked) {
                    v.start()
                    Jarvis.setStatus("待ち受け中")
                }
            } else if (fallbackJob?.isActive != true) {
                fallbackJob = scope.launch { fallbackLoop() }
            }
        } else {
            v?.stop()
            fallbackJob?.cancel()
            if (!session.isLocked) Jarvis.setStatus("待機中")
        }
    }

    /** 端末内モデルが使えない時の待ち受け (システムの音声認識を繰り返す。開始音は消音)。 */
    private suspend fun fallbackLoop() {
        while (scope.isActive && Jarvis.prefs.continuous && voice?.isReady != true) {
            val heard = session.withLock {
                Jarvis.setStatus("待ち受け中")
                Jarvis.stt.listenOnce()
            }
            if (heard == null) {
                delay(200)
                continue
            }
            Jarvis.setHeard(heard)
            val hit = WakeWord.detect(heard, Jarvis.prefs.extraWakeWords.split('\n').filter { it.isNotBlank() }) ?: continue
            session.withLock {
                var cmd = hit.command
                if (cmd.isEmpty()) {
                    Jarvis.tts.speak("はい")
                    cmd = Jarvis.stt.listenOnce() ?: ""
                }
                if (cmd.isNotEmpty()) runCommand(cmd) else Jarvis.setStatus("待ち受け中")
            }
        }
    }

    private suspend fun confirmByVoice(desc: String): Boolean = withContext(Dispatchers.Main) {
        Jarvis.tts.speak("確認です。$desc。実行してよろしいですか？")
        val ans = Jarvis.stt.listenOnce() ?: ""
        Jarvis.log("you", ans.ifBlank { "(無言)" })
        val ok = YES.containsMatchIn(ans) && !NO.containsMatchIn(ans)
        if (!ok) Jarvis.tts.speak("中止しました。")
        ok
    }

    // ---------- 連携 ----------

    private suspend fun startLink() {
        val prefs = Jarvis.prefs
        try {
            server = LinkServer(prefs.port, prefs) { req, peer -> handleRemote(req, peer) }.also { it.start() }
        } catch (e: Exception) {
            Jarvis.log("system", "連携サーバーを起動できません: ${e.message}")
        }
        withContext(Dispatchers.Main) { Jarvis.directory.startDiscovery(this@JarvisService) }
        Jarvis.directory.refreshManual()
    }

    private fun stopLink() {
        server?.shutdown()
        server = null
        Jarvis.directory.stop()
    }

    private suspend fun handleRemote(req: JSONObject, peer: String): JSONObject {
        val out = JSONObject().put("ok", true)
        when (req.optString("type")) {
            "ping" -> out.put("text", "pong")
            "info" -> out.put("text", JSONObject()
                .put("name", Jarvis.prefs.deviceName)
                .put("platform", Protocol.PLATFORM)
                .put("tools", JSONArray(Jarvis.tools.describeJson()))
                .toString())
            "command" -> {
                val text = req.optString("text")
                Jarvis.log(peer, text)
                val reply = withContext(Dispatchers.Default) { Jarvis.brain.handle(text, Origin.Remote(peer)) }
                Jarvis.log("jarvis → $peer", reply)
                out.put("text", reply)
            }
            "tool" -> {
                val r = Jarvis.tools.call(req.optString("name"), req.optJSONObject("args") ?: JSONObject(), Origin.Remote(peer))
                out.put("ok", !(r.startsWith("エラー") || r.startsWith("安全のため") || r.startsWith("ツール ")))
                out.put("text", r)
            }
            "speak" -> {
                val text = req.optString("text")
                Jarvis.log(peer, "🔊 $text")
                Jarvis.tts.speak(text)
                out.put("text", "読み上げました")
            }
            "clipboard" -> {
                withContext(Dispatchers.Main) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("JARVIS", req.optString("text")))
                }
                Jarvis.log(peer, "クリップボードを受信しました")
                out.put("text", "クリップボードに設定しました")
            }
            else -> out.put("ok", false).put("text", "未対応のリクエスト")
        }
        return out
    }

    // ---------- 通知・ロック ----------

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:link").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "jarvis:link").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "JARVIS 常駐", NotificationManager.IMPORTANCE_LOW).apply {
                description = "音声アシスタントと端末連携の待ち受け"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val listen = PendingIntent.getService(this, 1,
            Intent(this, JarvisService::class.java).setAction(ACTION_LISTEN), flags)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS 待機中")
            .setContentText("「話しかける」をタップ / ${Jarvis.prefs.deviceName}:${Jarvis.prefs.port}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "話しかける", listen)
            .addAction(0, "停止", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
