package com.zen.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zen.jarvis.access.JarvisAccessibility
import com.zen.jarvis.tools.Origin
import com.zen.jarvis.ui.MainScreen
import com.zen.jarvis.ui.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val accessibilityOn = mutableStateOf(false)
    private val serviceOn = mutableStateOf(false)
    private val importProgress = mutableStateOf<String?>(null)
    private val modelName = mutableStateOf("")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ensureService() }

    private val modelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importModel(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = UiActions(
            listen = { JarvisService.start(this, JarvisService.ACTION_LISTEN) },
            sendText = ::sendText,
            reload = { JarvisService.start(this, JarvisService.ACTION_RELOAD) },
            stopService = {
                startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
                serviceOn.value = false
            },
            startService = { ensureService() },
            pickModel = { modelPicker.launch(arrayOf("*/*")) },
            openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        )
        setContent {
            MainScreen(
                actions = actions,
                accessibilityOn = accessibilityOn.value,
                serviceOn = serviceOn.value,
                importProgress = importProgress.value,
                modelName = modelName.value,
            )
        }
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        accessibilityOn.value = JarvisAccessibility.instance != null
        modelName.value = if (Jarvis.llm.isReady) Jarvis.llm.modelName else ""
        ensureService()
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** マイク権限があれば常駐サービスを開始 (前面にいる今のうちに開始する必要がある)。 */
    private fun ensureService() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (mic && !JarvisService.running) JarvisService.start(this)
        serviceOn.value = mic
    }

    private fun sendText(text: String) {
        if (text.isBlank()) return
        lifecycleScope.launch {
            Jarvis.log("you", text)
            Jarvis.setStatus("考え中")
            val reply = withContext(Dispatchers.Default) { Jarvis.agent.handle(text, Origin.Local) }
            Jarvis.log("jarvis", reply)
            Jarvis.setStatus("話しています")
            Jarvis.tts.speak(reply)
            Jarvis.setStatus("待機中")
        }
    }

    /** 選んだ .task モデルをアプリ内にコピーして読み込む (数百MB〜数GBあるので進捗表示)。 */
    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "model.task"
                    var size = -1L
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = c.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0) name = c.getString(n)
                            if (s >= 0) size = c.getLong(s)
                        }
                    }
                    val dir = File(filesDir, "models").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }  // 古いモデルは消して容量を空ける
                    val dest = File(dir, name)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastPct = -1
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                output.write(buf, 0, r)
                                copied += r
                                val pct = if (size > 0) (copied * 100 / size).toInt() else -1
                                if (pct != lastPct) {
                                    lastPct = pct
                                    importProgress.value = if (pct >= 0) "コピー中 $pct%" else "コピー中 ${copied shr 20}MB"
                                }
                            }
                        }
                    }
                    dest.absolutePath
                }
            }
            result.onSuccess { path ->
                importProgress.value = "モデルを読み込み中…"
                Jarvis.prefs.modelPath = path
                Jarvis.llm.load(path)
                    .onSuccess {
                        modelName.value = Jarvis.llm.modelName
                        Jarvis.log("system", "モデル ${Jarvis.llm.modelName} を読み込みました")
                    }
                    .onFailure { Jarvis.log("system", "モデルを読み込めません: ${it.message}") }
            }.onFailure {
                Jarvis.log("system", "コピーに失敗しました: ${it.message}")
            }
            importProgress.value = null
        }
    }
}
