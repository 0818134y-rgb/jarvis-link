package com.zen.jarvis

import android.app.Application
import android.content.Context
import com.zen.jarvis.agent.Agent
import com.zen.jarvis.agent.ClaudeClient
import com.zen.jarvis.agent.LocalLlm
import com.zen.jarvis.link.DeviceDirectory
import com.zen.jarvis.link.LinkClient
import com.zen.jarvis.tools.ToolRegistry
import com.zen.jarvis.tools.registerPhoneTools
import com.zen.jarvis.tools.registerRemoteTools
import com.zen.jarvis.tools.registerScreenTools
import com.zen.jarvis.voice.SpeechInput
import com.zen.jarvis.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Jarvis.init(this)
    }
}

data class LogEntry(val time: String, val who: String, val text: String)

/** アプリ全体で共有する部品 (簡易サービスロケータ)。 */
object Jarvis {
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var tools: ToolRegistry
        private set
    lateinit var llm: LocalLlm
        private set
    lateinit var agent: Agent
        private set
    lateinit var claude: ClaudeClient
        private set
    lateinit var directory: DeviceDirectory
        private set
    lateinit var client: LinkClient
        private set
    lateinit var tts: SpeechOutput
        private set
    lateinit var stt: SpeechInput
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow("待機中")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        tts = SpeechOutput(app)
        stt = SpeechInput(app)
        llm = LocalLlm(app)
        client = LinkClient(prefs)
        directory = DeviceDirectory(prefs, client)
        tools = ToolRegistry().apply {
            registerPhoneTools(app)
            registerScreenTools()
            registerRemoteTools(app)
        }
        claude = ClaudeClient(prefs)
        agent = Agent(tools, llm, claude)

        val model = prefs.modelPath
        if (model.isNotBlank()) {
            scope.launch {
                setStatus("モデル読み込み中")
                llm.load(model).onFailure { log("system", "モデルを読み込めません: ${it.message}") }
                setStatus("待機中")
            }
        }
    }

    fun setStatus(s: String) {
        _status.value = s
    }

    fun log(who: String, text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        _log.value = (_log.value + LogEntry(t, who, text)).takeLast(200)
    }
}
