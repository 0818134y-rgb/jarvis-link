package com.zen.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : ComponentActivity() {

    private val accessibilityOn = mutableStateOf(false)
    private val serviceOn = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ensureService() }


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
            openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        )
        setContent {
            MainScreen(
                actions = actions,
                accessibilityOn = accessibilityOn.value,
                serviceOn = serviceOn.value,
            )
        }
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        accessibilityOn.value = JarvisAccessibility.instance != null
        ensureService()
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
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
            val reply = withContext(Dispatchers.Default) { Jarvis.brain.handle(text, Origin.Local) }
            Jarvis.log("jarvis", reply)
            Jarvis.setStatus("話しています")
            Jarvis.tts.speak(reply)
            Jarvis.setStatus("待機中")
        }
    }
}
