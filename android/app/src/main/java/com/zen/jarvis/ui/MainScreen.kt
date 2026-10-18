package com.zen.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.zen.jarvis.Jarvis
import com.zen.jarvis.LogEntry

private val Cyan = Color(0xFF4FD8FF)
private val CyanDim = Color(0xFF2A6F8F)
private val Bg = Color(0xFF05090F)
private val Panel = Color(0xFF0B1520)
private val TextMain = Color(0xFFCFEFFF)
private val TextSub = Color(0xFF7FA6B8)
private val Amber = Color(0xFFFFB547)

class UiActions(
    val listen: () -> Unit,
    val sendText: (String) -> Unit,
    val reload: () -> Unit,
    val stopService: () -> Unit,
    val startService: () -> Unit,
    val openAccessibility: () -> Unit,
    val openBattery: () -> Unit,
    val openNotificationAccess: () -> Unit,
)

@Composable
fun MainScreen(
    actions: UiActions,
    accessibilityOn: Boolean,
    serviceOn: Boolean,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Panel, onSurface = TextMain)) {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            val status by Jarvis.status.collectAsState()
            val log by Jarvis.log.collectAsState()
            val peers by Jarvis.directory.peers.collectAsState()
            var showSettings by remember { mutableStateOf(false) }

            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("J.A.R.V.I.S.", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                        Text("${Jarvis.prefs.deviceName} · $status", color = TextSub, fontSize = 13.sp)
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定", tint = if (showSettings) Cyan else TextSub)
                    }
                }

                AnimatedVisibility(showSettings) {
                    SettingsPanel(actions, accessibilityOn, serviceOn)
                }

                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    val label = when (status) {
                        "聞いています" -> "LISTENING"
                        "考え中" -> "THINKING"
                        "話しています" -> "SPEAKING"
                        else -> "TAP"
                    }
                    ArcReactor(active = label != "TAP", label = label, size = if (showSettings) 110 else 200) {
                        actions.listen()
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Chip("自作AI ✓", true)
                    Chip(if (accessibilityOn) "画面操作 ✓" else "画面操作 OFF", accessibilityOn)
                    Chip("連携 ${peers.size}台", peers.isNotEmpty())
                }
                if (peers.isNotEmpty()) {
                    Text(peers.values.joinToString("  ") { "● ${it.name}" }, color = TextSub, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = CyanDim.copy(alpha = 0.4f))
                LogList(log, Modifier.weight(1f))
                CommandInput(actions.sendText)
            }
        }
    }
}

@Composable
private fun ArcReactor(active: Boolean, label: String, size: Int, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "reactor")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 1600 else 9000, easing = LinearEasing)), label = "angle")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(if (active) 500 else 2200), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f * pulse), Color.Transparent), radius = r), r)
            drawCircle(CyanDim, r * 0.92f, style = Stroke(width = r * 0.04f))
            drawCircle(Cyan.copy(alpha = 0.8f), r * 0.62f, style = Stroke(width = r * 0.06f))
            drawCircle(Brush.radialGradient(listOf(Color.White, Cyan.copy(alpha = 0.9f * pulse), Color.Transparent), radius = r * 0.38f), r * 0.38f)
        }
        Canvas(Modifier.fillMaxSize().rotate(angle)) {
            val r = this.size.minDimension / 2
            val inset = r * 0.16f
            for (i in 0 until 6) {
                drawArc(Cyan.copy(alpha = if (active) 0.95f else 0.6f), startAngle = i * 60f, sweepAngle = 38f, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2),
                    style = Stroke(width = r * 0.07f))
            }
        }
        Text(label, color = Bg, fontSize = (size / 18).sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Chip(text: String, ok: Boolean) {
    Text(
        text,
        color = if (ok) Cyan else Amber,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Panel)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun LogList(log: List<LogEntry>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        items(log) { e ->
            val (color, label) = when (e.who) {
                "you" -> TextMain to "YOU"
                "jarvis" -> Cyan to "JARVIS"
                "tool" -> TextSub to "⚙"
                "system" -> Amber to "SYS"
                else -> Color(0xFF9BE7A0) to e.who
            }
            Row {
                Text(e.time, color = TextSub.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(54.dp).padding(top = 2.dp))
                Column {
                    Text(label, color = color.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(e.text, color = color, fontSize = if (e.who == "tool") 12.sp else 15.sp)
                }
            }
        }
    }
}

@Composable
private fun CommandInput(send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("文字で指示 (例: PCでメモ帳を開いて)", fontSize = 13.sp) },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { send(text); text = "" }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信", tint = Cyan)
        }
    }
}

@Composable
private fun SettingsPanel(
    actions: UiActions,
    accessibilityOn: Boolean,
    serviceOn: Boolean,
) {
    val prefs = Jarvis.prefs
    var name by remember { mutableStateOf(prefs.deviceName) }
    var token by remember { mutableStateOf(prefs.token) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var peers by remember { mutableStateOf(prefs.peers) }
    var continuous by remember { mutableStateOf(prefs.continuous) }
    var discovery by remember { mutableStateOf(prefs.discovery) }
    var dangerous by remember { mutableStateOf(prefs.allowRemoteDangerous) }
    var forward by remember { mutableStateOf(prefs.forwardNotifications) }
    var showToken by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(max = 440.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle("端末連携")
        OutlinedTextField(name, { name = it }, label = { Text("この端末の名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it }, label = { Text("連携トークン (PCと同じ値)") }, singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { Text(if (showToken) "隠す" else "表示", color = Cyan, fontSize = 12.sp,
                modifier = Modifier.clickable { showToken = !showToken }.padding(8.dp)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("ポート") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(peers, { peers = it }, label = { Text("手動ピア (外出先用 例: 100.x.y.z:8765)") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        ToggleRow("同じWi-Fiの端末を自動で探す", discovery) { discovery = it }
        ToggleRow("他端末からの画面操作・発信を許可", dangerous) { dangerous = it }
        ToggleRow("スマホの通知をPCに転送", forward) {
            forward = it
            prefs.forwardNotifications = it
            if (it) actions.openNotificationAccess()
        }
        Button(onClick = {
            prefs.deviceName = name
            prefs.token = token
            prefs.port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
            prefs.peers = peers
            prefs.discovery = discovery
            prefs.allowRemoteDangerous = dangerous
            actions.reload()
        }, modifier = Modifier.fillMaxWidth()) { Text("保存して再接続") }

        SectionTitle("音声")
        ToggleRow("常時「ジャーヴィス」を待ち受け", continuous) {
            continuous = it
            prefs.continuous = it
            actions.startService()
            actions.reload()
        }

        SectionTitle("権限")
        OutlinedButton(onClick = actions.openAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text(if (accessibilityOn) "ユーザー補助: オン" else "ユーザー補助を開いて「JARVIS 端末操作」をオン")
        }
        OutlinedButton(onClick = actions.openNotificationAccess, modifier = Modifier.fillMaxWidth()) {
            Text("通知へのアクセスを許可 (通知の読み上げ・PC転送)")
        }
        OutlinedButton(onClick = actions.openBattery, modifier = Modifier.fillMaxWidth()) {
            Text("電池の最適化を解除 (画面オフでも連携を受ける)")
        }
        if (serviceOn) {
            OutlinedButton(onClick = actions.stopService, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber)) { Text("常駐を停止") }
        } else {
            Button(onClick = actions.startService, modifier = Modifier.fillMaxWidth()) { Text("常駐を開始") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
