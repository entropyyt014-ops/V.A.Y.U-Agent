package com.vayu.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Direct Android controls. Decisions belong to the connected MCP client. */
class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VayuService"
        var instance: VayuService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "VayuService connected — ready")
        Toast.makeText(this, "VAYU Agent Active", Toast.LENGTH_SHORT).show()

        // Initialize relay client and auto-connect
        RelayClient.init(getSharedPreferences("vayu_settings", MODE_PRIVATE), this)
        if (RelayClient.isEnabled()) {
            RelayClient.connect()
            Log.i(TAG, "MCP Relay auto-connecting...")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { RelayClient.disconnect() }

    override fun onUnbind(intent: Intent?): Boolean {
        RelayClient.disconnect()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        RelayClient.disconnect()
        instance = null
    }

    @android.annotation.SuppressLint("NewApi")
    private suspend fun captureScreen(): String? = withContext(Dispatchers.Main) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Use the official takeScreenshot API
                captureScreenApi30()
            } else {
                // API 29 and below: Fallback — return null gracefully
                // The agent will work with UI tree only on older devices
                Log.w(TAG, "Screenshot capture not available on API ${Build.VERSION.SDK_INT} (requires API 30+)")
                null
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen exception: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private suspend fun captureScreenApi30(): String? = withContext(Dispatchers.Main) {
        try {
            suspendCancellableCoroutine<String?> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBitmap = result.hardwareBuffer.let { hb ->
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            }
                            if (hwBitmap == null) {
                                Log.e(TAG, "wrapHardwareBuffer returned null")
                                if (cont.isActive) cont.resume(null)
                                return
                            }

                            val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to copy hardware bitmap")
                                if (cont.isActive) cont.resume(null)
                                return
                            }

                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            if (cont.isActive) cont.resume(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed: ${e.message}")
                            if (cont.isActive) cont.resume(null)
                        } finally { result.hardwareBuffer.close() }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with code: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenApi30 exception: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("NewApi")
    suspend fun captureScreenPublic(): String? = captureScreen()

    fun getUiTreePublic(): String? {
        return try {
            parseUITree(rootInActiveWindow)
        } catch (e: Exception) {
            Log.e(TAG, "getUiTreePublic failed: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════
    //  AUTO-CAPTURE
    // ════════════════════════════════════════════════

    @android.annotation.SuppressLint("NewApi")
    suspend fun autoCapture(): Pair<String?, String?> {
        delay(350)
        val b64 = captureScreen()
        val uiTree = withContext(Dispatchers.Main) {
            try { parseUITree(rootInActiveWindow) } catch (e: Exception) { null }
        }
        return Pair(b64, uiTree)
    }

    // ════════════════════════════════════════════════
    //  DIRECT ACTION EXECUTION
    // ════════════════════════════════════════════════

    suspend fun executeDirectAction(action: AgentAction) {
        executeAction(action)
    }

    suspend fun findAndTapTextPublic(query: String): UiActionResult {
        val node = findBestNodeByText(rootInActiveWindow, query, preferClickable = true)
            ?: return UiActionResult(false, "No visible element matched: $query")
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX()
        val y = rect.centerY()
        executeAction(AgentAction(action = "TAP", x = x, y = y))
        return UiActionResult(true, "Tapped best match for: $query", x, y)
    }

    suspend fun typeInFieldPublic(text: String, hint: String = ""): UiActionResult {
        val root = rootInActiveWindow ?: return UiActionResult(false, "No active window")
        val target = if (hint.isNotBlank()) findBestNodeByText(root, hint, preferEditable = true)
            else findAnyEditableNode(root)
        if (target == null || !target.isEditable) return UiActionResult(false, "No editable field matched the hint")
        val rect = android.graphics.Rect()
        target.getBoundsInScreen(rect)
        val x = rect.centerX()
        val y = rect.centerY()
        executeAction(AgentAction(action = "TYPE", x = x, y = y, text = text))
        return UiActionResult(true, "Text input accepted", x, y)
    }

    private fun findBestNodeByText(root: AccessibilityNodeInfo?, query: String, preferClickable: Boolean = false, preferEditable: Boolean = false): AccessibilityNodeInfo? {
        if (root == null || query.isBlank()) return null
        val q = query.lowercase().trim()
        var best: Pair<AccessibilityNodeInfo, Int>? = null

        fun scoreNode(node: AccessibilityNodeInfo): Int {
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName
            ).map { it.lowercase().trim() }.filter { it.isNotBlank() }
            var score = -1
            for (value in values) {
                score = maxOf(score, when {
                    value == q -> 100
                    value.startsWith(q) -> 85
                    value.contains(q) -> 70
                    else -> -1
                })
            }
            if (score < 0 || !node.isVisibleToUser || (preferEditable && !node.isEditable)) return -1
            if (preferClickable && node.isClickable) score += 25
            if (preferEditable && node.isEditable) score += 40
            return score
        }

        fun walk(node: AccessibilityNodeInfo) {
            val score = scoreNode(node)
            if (score >= 0 && (best == null || score > best!!.second)) best = node to score
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }

        walk(root)
        return best?.first
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
    }

    private fun findAnyEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val found = findAnyEditableNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun parseUITree(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = java.lang.StringBuilder()
        fun traverse(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18) return
            val indent = " ".repeat(depth * 2)
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)
            val text = if (n.isPassword) "[redacted]" else n.text?.toString() ?: ""
            val desc = if (n.isPassword) "[redacted]" else n.contentDescription?.toString() ?: ""
            val resId = n.viewIdResourceName ?: ""
            val clickable = n.isClickable
            val editable = n.isEditable
            val scrollable = n.isScrollable
            val checkable = n.isCheckable
            val checked = n.isChecked

            if (text.isNotBlank() || desc.isNotBlank() || clickable || editable || scrollable || resId.isNotBlank()) {
                val flags = buildString {
                    if (clickable) append(" click")
                    if (editable) append(" edit")
                    if (scrollable) append(" scroll")
                    if (checkable) append(" check=${checked}")
                }
                sb.append("$indent[${n.className}] text='$text' desc='$desc' resId='$resId' bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]$flags\n")
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { traverse(it, depth + 1) }
            }
        }
        traverse(node, 0)
        return sb.toString().take(12000)
    }

    // ════════════════════════════════════════════════
    //  ACTION EXECUTOR
    // ════════════════════════════════════════════════

    private suspend fun dispatchGestureAndWait(gesture: GestureDescription, label: String): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        fun finish(result: Boolean) {
            if (!resumed && cont.isActive) {
                resumed = true
                cont.resume(result)
            }
        }
        try {
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture completed: $label")
                    finish(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled: $label")
                    finish(false)
                }
            }, null)
            if (!accepted) finish(false)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture $label failed: ${e.message}")
            finish(false)
        }
    }

    private suspend fun executeAction(action: AgentAction) {
        try {
            when (action.action) {
                "TAP" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    check(dispatchGestureAndWait(gesture, "TAP")) { "Tap cancelled" }
                    Log.d(TAG, "TAP at (${action.x}, ${action.y})")
                }

                "SWIPE", "SCROLL" -> {
                    val path = Path().apply {
                        moveTo(action.x.toFloat(), action.y.toFloat())
                        lineTo(action.x2.toFloat(), action.y2.toFloat())
                    }
                    val duration = action.duration.takeIf { it > 0 } ?: if (action.action == "SCROLL") 550L else 450L
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                        .build()
                    check(dispatchGestureAndWait(gesture, action.action)) { "Swipe cancelled" }
                    Log.d(TAG, "${action.action} from (${action.x},${action.y}) to (${action.x2},${action.y2})")
                }

                "TYPE" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val tapGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    check(dispatchGestureAndWait(tapGesture, "TYPE")) { "Field tap cancelled" }

                    delay(300)

                    val root = rootInActiveWindow
                    val hit = findNodeAt(root, action.x, action.y)
                    val focused = findFocusedNode(root)
                    val target = hit?.takeIf { it.isEditable } ?: focused?.takeIf {
                        val bounds = android.graphics.Rect()
                        it.getBoundsInScreen(bounds)
                        bounds.contains(action.x, action.y)
                    } ?: error("No editable field at the requested coordinates")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                    }
                    var typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (!typed && action.text.isNotEmpty()) {
                        val length = target.text?.length ?: 0
                        val selected = length == 0 || target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                        })
                        check(selected) { "Cannot replace existing field contents" }
                        val clip = android.content.ClipData.newPlainText("vayu", action.text)
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                        typed = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                    check(typed) { "Text could not be entered" }
                }

                "OPEN_APP" -> {
                    val pm = packageManager
                    val intent = pm.getInstalledApplications(0).find {
                        pm.getApplicationLabel(it).toString().equals(action.app, ignoreCase = true)
                    }?.let { pm.getLaunchIntentForPackage(it.packageName) }
                        ?: pm.getLaunchIntentForPackage(action.app)

                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        Log.d(TAG, "OPEN_APP: ${action.app}")
                    } else {
                        Log.w(TAG, "OPEN_APP: App not found — ${action.app}")
                        error("App not found: ${action.app}")
                    }
                }

                "LONG_PRESS" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                        .build()
                    check(dispatchGestureAndWait(gesture, "LONG_PRESS")) { "Long press cancelled" }
                    Log.d(TAG, "LONG_PRESS at (${action.x}, ${action.y})")
                }

                "PRESS_BACK" -> check(performGlobalAction(GLOBAL_ACTION_BACK)) { "Navigation failed" }
                "PRESS_HOME" -> check(performGlobalAction(GLOBAL_ACTION_HOME)) { "Navigation failed" }
                "PRESS_RECENTS" -> check(performGlobalAction(GLOBAL_ACTION_RECENTS)) { "Navigation failed" }
                else -> error("Unknown action: ${action.action}")
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun findNodeAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until root.childCount) {
            val child = findNodeAt(root.getChild(i), x, y)
            if (child != null) return child
        }
        return if (root.isEditable || root.isClickable) root else null
    }

}
