package com.fareed.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Keep
class AutomatorBridge {

    private val handler = Handler(Looper.getMainLooper())
    private val service: FarAutoAccessibilityService?
        get() = FarAutoAccessibilityService.instance

    fun getRoot(): String? {
        val future = CompletableFuture<String?>()
        handler.post {
            val root = service?.rootInActiveWindow
            if (root != null) {
                val json = nodeToJson(root, "root", true)
                root.recycle()
                future.complete(json.toString())
            } else {
                future.complete(null)
            }
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun click(x: Float, y: Float): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            if (s == null) {
                future.complete(false)
                return@post
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { future.complete(true) }
                override fun onCancelled(gd: GestureDescription?) { future.complete(false) }
            }, null)
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            if (s == null) {
                future.complete(false)
                return@post
            }
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { future.complete(true) }
                override fun onCancelled(gd: GestureDescription?) { future.complete(false) }
            }, null)
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun inputText(text: String, clearFirst: Boolean): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val node = service?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node == null) {
                future.complete(false)
                return@post
            }

            if (clearFirst) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val res = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            node.recycle()
            future.complete(res)
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun getScreenSize(): String {
        val future = CompletableFuture<String>()
        handler.post {
            val context = service
            if (context == null) {
                future.complete("1080,2400")
                return@post
            }
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            future.complete("${bounds.width()},${bounds.height()}")
        }
        return try { future.get(2, TimeUnit.SECONDS) } catch (e: Exception) { "1080,2400" }
    }

    fun pressBack(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun pressRecent(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    fun launchApp(packageName: String): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val intent = service?.packageManager?.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                future.complete(false)
                return@post
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                service?.startActivity(intent)
                future.complete(true)
            } catch (e: Exception) { future.complete(false) }
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun openAppSettings(packageName: String): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service?.startActivity(intent)
                future.complete(true)
            } catch (e: Exception) {
                future.complete(false)
            }
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun isSecureWindow(): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val root = service?.rootInActiveWindow
            val isSecure = root == null && service != null
            root?.recycle()
            future.complete(isSecure)
        }
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    fun getLastToast(): String? {
        val future = CompletableFuture<String?>()
        handler.post { future.complete(service?.lastToastText) }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    fun clearLastToast() {
        handler.post {
            service?.lastToastText = null
            service?.lastToastPackage = null
        }
    }

    fun getLastToastPackage(): String? {
        val future = CompletableFuture<String?>()
        handler.post { future.complete(service?.lastToastPackage) }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    fun setToastPackageFilter(packageName: String?) {
        handler.post { service?.toastPackageFilter = packageName }
    }

    fun clearLogs() = ScriptExecutionService.clearLogs()

    fun isScreenRecordReady(): Boolean = ScreenRecordService.isReady()

    fun isScreenRecording(): Boolean = ScreenRecordService.isRecording()

    fun startScreenRecord(filename: String): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post { future.complete(ScreenRecordService.instance?.startRecording(filename) ?: false) }
        return try { future.get(10, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    fun stopScreenRecord(): String? {
        val future = CompletableFuture<String?>()
        handler.post { future.complete(ScreenRecordService.instance?.stopRecording()) }
        return try { future.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    fun takeScreenshot(): String? {
        val future = CompletableFuture<String?>()
        handler.post {
            val s = service
            if (s == null) {
                future.complete(null)
                return@post
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshotResult.hardwareBuffer
                        val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, null)
                        buffer.close()
                        if (bitmap != null) {
                            val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            val stream = java.io.ByteArrayOutputStream()
                            softwareBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                            val bytes = stream.toByteArray()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            future.complete(base64)
                        } else {
                            future.complete(null)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        future.complete(null)
                    }
                })
            } else {
                future.complete(null)
            }
        }
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun dumpTree(): String {
        val future = CompletableFuture<String>()
        handler.post {
            val root = service?.rootInActiveWindow
            val json = JSONObject()
            if (root != null) {
                json.put("root", nodeToJson(root, "root", true))
                root.recycle()
            }
            future.complete(json.toString())
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "{}" }
    }

    fun findNodes(resourceId: String?, text: String?): String {
        val future = CompletableFuture<String>()
        handler.post {
            val root = service?.rootInActiveWindow ?: run {
                future.complete("[]")
                return@post
            }
            val results = mutableListOf<AccessibilityNodeInfo>()
            if (resourceId != null) {
                results.addAll(root.findAccessibilityNodeInfosByViewId(resourceId))
            }
            if (text != null) {
                results.addAll(root.findAccessibilityNodeInfosByText(text))
            }

            val array = JSONArray()
            val seen = mutableSetOf<String>()
            results.forEach { node ->
                val json = nodeToJson(node, "found", false)
                val key = json.optString("id") + json.optString("bounds")
                if (seen.add(key)) {
                    array.put(json)
                }
                node.recycle()
            }
            root.recycle()
            future.complete(array.toString())
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "[]" }
    }

    fun getInteractableNodes(): String {
        val future = CompletableFuture<String>()
        handler.post {
            val root = service?.rootInActiveWindow ?: run {
                future.complete("[]")
                return@post
            }
            val array = JSONArray()
            val metrics = service?.resources?.displayMetrics
            walkInteractable(root, "root", array, metrics?.widthPixels ?: 1080, metrics?.heightPixels ?: 2400)
            root.recycle()
            future.complete(array.toString())
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "[]" }
    }

    private fun walkInteractable(node: AccessibilityNodeInfo, path: String, array: JSONArray, sw: Int, sh: Int) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.left < sw && rect.top < sh && rect.right > 0 && rect.bottom > 0) {
            val isInput = node.className?.contains("EditText", true) == true
            if (node.isClickable || node.isFocusable || isInput) {
                val item = nodeToJson(node, path, false)
                if (!item.has("text") && !item.has("desc")) {
                    val deepText = findDeepText(node)
                    if (deepText.isNotEmpty()) item.put("text", deepText)
                }
                array.put(item)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walkInteractable(child, "$path/$i", array, sw, sh)
                child.recycle()
            }
        }
    }

    private fun findDeepText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: ""
        if (t.isNotEmpty()) return t
        val d = node.contentDescription?.toString() ?: ""
        if (d.isNotEmpty()) return d
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val res = findDeepText(c)
            c.recycle()
            if (res.isNotEmpty()) return res
        }
        return ""
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, path: String, recursive: Boolean): JSONObject {
        val json = JSONObject()
        json.put("path", path)
        json.put("package", node.packageName?.toString() ?: "")
        json.put("class", node.className?.toString() ?: "")
        node.text?.let { json.put("text", it.toString()) }
        node.contentDescription?.let { json.put("desc", it.toString()) }
        node.viewIdResourceName?.let { json.put("id", it) }
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        json.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        if (node.isClickable) json.put("clickable", true)
        if (node.isFocusable) json.put("focusable", true)

        if (recursive) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(nodeToJson(child, "$path/$i", true))
                child.recycle()
            }
            if (children.length() > 0) json.put("children", children)
        }
        return json
    }

    private fun performGlobalAction(action: Int): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post { future.complete(service?.performGlobalAction(action) ?: false) }
        return try { future.get(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }
}
