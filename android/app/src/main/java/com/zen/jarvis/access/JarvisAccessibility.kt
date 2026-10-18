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

    // ---------- 番号で操作 ----------

    data class Item(val label: String, val node: AccessibilityNodeInfo)

    /** 最後に読み上げた項目一覧 (「3番をタップ」で使う) */
    @Volatile
    var lastItems: List<Item> = emptyList()
        private set

    /** 画面上の押せる項目を上から順に集めて番号を振る。 */
    fun listItems(max: Int = 20): List<Item> {
        val found = mutableListOf<Pair<Rect, Item>>()
        val seen = HashSet<String>()
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser && (n.isClickable || n.isCheckable || n.isEditable)) {
                    val label = labelOf(n)
                    if (label.isNotEmpty() && seen.add(label)) {
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        if (r.width() > 0 && r.height() > 0) found += r to Item(label, n)
                    }
                }
                true
            }
        }
        lastItems = found.sortedWith(compareBy({ it.first.top / 40 }, { it.first.left })).map { it.second }.take(max)
        return lastItems
    }

    /** ボタン自体に文字がなければ、中の文字や説明文を拾う。 */
    private fun labelOf(n: AccessibilityNodeInfo): String {
        val own = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
        if (own.isNotEmpty()) return own.take(30)
        var child = ""
        walk(n) { c ->
            if (child.isEmpty()) child = (c.text ?: c.contentDescription)?.toString()?.trim().orEmpty()
            child.isEmpty()
        }
        if (child.isNotEmpty()) return child.take(30)
        return if (n.isEditable) "入力欄" else ""
    }

    suspend fun tapItem(index: Int): Item? {
        val item = lastItems.getOrNull(index - 1) ?: return null
        if (item.node.isEditable) {
            item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return item
        }
        if (item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return item
        val r = Rect().also { item.node.getBoundsInScreen(it) }
        return if (tapAt(r.exactCenterX(), r.exactCenterY())) item else null
    }

    // ---------- 長押し・スワイプ ----------

    suspend fun longPressText(query: String): Boolean {
        val q = query.trim().lowercase()
        var target: AccessibilityNodeInfo? = null
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser && labelOf(n).lowercase().contains(q)) target = n
                target == null
            }
            if (target != null) break
        }
        val t = target ?: return false
        var node: AccessibilityNodeInfo? = t
        while (node != null) {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            node = node.parent
        }
        val r = Rect().also { t.getBoundsInScreen(it) }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 900)).build())
    }

    /** left = 次へ (右から左へ指を動かす) / right = 前へ */
    suspend fun swipeHorizontal(left: Boolean): Boolean {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.5f
        val (x1, x2) = if (left) dm.widthPixels * 0.85f to dm.widthPixels * 0.15f else dm.widthPixels * 0.15f to dm.widthPixels * 0.85f
        return swipe(x1, y, x2, y, 250)
    }

    // ---------- 文字列で探してタップ (候補のどれか) ----------

    suspend fun tapAnyOf(labels: List<String>): Boolean {
        for (l in labels) if (tapText(l)) return true
        return false
    }

    // ---------- ブラウザ ----------

    /** 表示中のブラウザのURL。アドレスバーのビューを探して読む。 */
    fun currentUrl(): String? {
        val ids = listOf("url_bar", "location_bar_edit_text", "mozac_browser_toolbar_url_view", "url_field", "url", "address_bar")
        var url: String? = null
        for (root in roots()) {
            walk(root) { n ->
                val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
                if (id in ids && !n.text.isNullOrBlank()) url = n.text.toString()
                url == null
            }
            if (url != null) break
        }
        val u = url?.trim() ?: return null
        if (u.contains(' ') || !u.contains('.')) return null
        return if (u.startsWith("http")) u else "https://$u"
    }

    // ---------- 編集操作 ----------

    fun editAction(action: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return when (action) {
            "copy" -> focused.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "paste" -> focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "cut" -> focused.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "select_all" -> {
                val len = focused.text?.length ?: 0
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                })
            }
            "enter" -> pressEnter()
            else -> false
        }
    }
}
