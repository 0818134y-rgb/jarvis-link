package com.zen.jarvis.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 画面操作の実体。ユーザーが「設定 > ユーザー補助」で有効にすると動く。
 * イベントは購読せず、指示が来た時だけ画面ツリーを読む。
 */
class JarvisAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibility? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ---------- 読み取り ----------

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = windows.mapNotNull { it.root }
        return list.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null || depth > 40) return
        if (!visit(node)) return
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    fun readScreen(limit: Int = 1500): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser) {
                    val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                    if (!t.isNullOrEmpty() && seen.add(t)) {
                        if (n.isClickable) sb.append("[").append(t).append("] ") else sb.append(t).append(" / ")
                    }
                }
                sb.length < limit
            }
        }
        return sb.toString().take(limit)
    }

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: "?"

    // ---------- タップ ----------

    /** 表示テキストまたは説明文で要素を探してタップ。 */
    suspend fun tapText(query: String): Boolean {
        val q = query.trim().lowercase()
        var exact: AccessibilityNodeInfo? = null
        var partial: AccessibilityNodeInfo? = null
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser) {
                    val label = ((n.text ?: "").toString() + " " + (n.contentDescription ?: "").toString()).trim().lowercase()
                    if (label.isNotEmpty()) {
                        val parts = label.split(" ")
                        if (exact == null && parts.any { it == q }) exact = n
                        else if (partial == null && label.contains(q)) partial = n
                    }
                }
                exact == null
            }
            if (exact != null) break
        }
        val target = exact ?: partial ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        val r = Rect().also { target.getBoundsInScreen(it) }
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatch(g)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 350): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        return dispatch(g)
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    // ---------- スクロール ----------

    suspend fun scroll(forward: Boolean): Boolean {
        var scrollable: AccessibilityNodeInfo? = null
        rootInActiveWindow?.let { root ->
            walk(root) { n ->
                if (n.isScrollable && n.isVisibleToUser) scrollable = n
                true  // 一番深い(最後に見つかる)スクロール領域を使う
            }
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable?.performAction(action) == true) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (forward) dm.heightPixels * 0.75f to dm.heightPixels * 0.3f
        else dm.heightPixels * 0.3f to dm.heightPixels * 0.75f
        return swipe(x, y1, x, y2)
    }

    // ---------- 入力 ----------

    fun typeText(text: String, append: Boolean = false): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable() ?: return false
        val newText = if (append) (focused.text?.toString() ?: "") + text else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(rootInActiveWindow) { n ->
            if (n.isEditable && n.isVisibleToUser) found = n
            found == null
        }
        return found
    }

    /** IMEの実行ボタン(検索・送信)相当。対応していない入力欄では false。 */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)
}
