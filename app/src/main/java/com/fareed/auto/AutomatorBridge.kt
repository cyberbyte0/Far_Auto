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

/**
 * Bridge between Python background thread and Android Main Thread.
 * Every method follows the CompletableFuture + 5s Timeout pattern.
 * Correctly recycles AccessibilityNodeInfo to prevent memory leaks.
 */
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
                val json = nodeToJson(root, "root")
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
            
            val result = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    future.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    future.complete(false)
                }
            }, null)
            
            if (!result) future.complete(false)
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            if (s == null) {
                future.complete(false)
                return@post
            }
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val result = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    future.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    future.complete(false)
                }
            }, null)

            if (!result) future.complete(false)
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    fun inputText(text: String, clearFirst: Boolean): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            val node = s?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    fun getScreenSize(): String {
        val future = CompletableFuture<String>()
        handler.post {
            val context = service ?: return@post
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                future.complete("${bounds.width()},${bounds.height()}")
            } else {
                val display = wm.defaultDisplay
                val size = android.graphics.Point()
                display.getRealSize(size)
                future.complete("${size.x},${size.y}")
            }
        }
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "1080,2400" // Sensible default
        }
    }

    fun pressBack(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun pressRecent(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    fun launchApp(packageName: String): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            val intent = s?.packageManager?.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                future.complete(false)
                return@post
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                s.startActivity(intent)
                future.complete(true)
            } catch (e: Exception) {
                future.complete(false)
            }
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
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

    fun clearLogs() {
        ScriptExecutionService.clearLogs()
    }

    fun dumpTree(): String {
        val future = CompletableFuture<String>()
        handler.post {
            val root = service?.rootInActiveWindow
            val json = JSONObject()
            if (root != null) {
                json.put("root", nodeToJson(root, "root"))
                root.recycle()
            }
            future.complete(json.toString())
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "{}"
        }
    }

    fun findNodes(resourceId: String?, text: String?): String {
        val future = CompletableFuture<String>()
        handler.post {
            val root = service?.rootInActiveWindow ?: run {
                future.complete("[]")
                return@post
            }
            val list = mutableListOf<AccessibilityNodeInfo>()
            if (resourceId != null) {
                list.addAll(root.findAccessibilityNodeInfosByViewId(resourceId))
            } else if (text != null) {
                list.addAll(root.findAccessibilityNodeInfosByText(text))
            }

            val array = JSONArray()
            list.forEach { 
                array.put(nodeToJson(it, "found"))
                it.recycle()
            }
            root.recycle()
            future.complete(array.toString())
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "[]"
        }
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
            val screenWidth = metrics?.widthPixels ?: 1080
            val screenHeight = metrics?.heightPixels ?: 2400
            
            walkInteractable(root, "root", array, screenWidth, screenHeight)
            root.recycle()
            future.complete(array.toString())
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "[]"
        }
    }

    private fun walkInteractable(node: AccessibilityNodeInfo, path: String, array: JSONArray, sw: Int, sh: Int) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        
        // Visibility check
        if (rect.left < sw && rect.top < sh && rect.right > 0 && rect.bottom > 0) {
            
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val id = node.viewIdResourceName
            
            // Smarter filter: 
            // 1. Is it explicitly interactable?
            // 2. Does it actually have some content (text/desc/id) OR is it a known input type?
            val hasContent = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !id.isNullOrEmpty()
            val isInput = node.className?.contains("EditText", true) == true
            val isExplicitlyActionable = node.isClickable || node.isFocusable
            
            if (isExplicitlyActionable) {
                // If it has content, OR it's an input, OR one of its children has text
                if (hasContent || isInput || findDeepText(node).isNotEmpty()) {
                    array.put(nodeToJson(node, path, false))
                }
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    walkInteractable(child, "$path/$i", array, sw, sh)
                    child.recycle()
                }
            }
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, path: String, recursive: Boolean = true): JSONObject {
        val json = JSONObject()
        json.put("path", path)
        json.put("package", node.packageName?.toString() ?: "")
        json.put("class", node.className?.toString() ?: "")
        
        // Primary text/desc
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName
        
        if (!text.isNullOrEmpty()) json.put("text", text)
        if (!desc.isNullOrEmpty()) json.put("desc", desc)
        if (!id.isNullOrEmpty()) json.put("id", id)

        // Deep text fallback: if this is a clickable container with no text, find child text
        if (text.isNullOrEmpty() && desc.isNullOrEmpty()) {
            val deepText = findDeepText(node)
            if (deepText.isNotEmpty()) json.put("text", deepText)
        }

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        json.put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
        
        if (node.isClickable) json.put("clickable", true)
        if (node.isFocusable) json.put("focusable", true)

        if (recursive) {
            val childrenArray = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    childrenArray.put(nodeToJson(child, "$path/$i", true))
                    child.recycle()
                }
            }
            if (childrenArray.length() > 0) json.put("children", childrenArray)
        }

        return json
    }

    private fun findDeepText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) return text
        
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty()) return desc
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val childText = findDeepText(child)
                child.recycle()
                if (childText.isNotEmpty()) return childText
            }
        }
        return ""
    }
    private fun performGlobalAction(action: Int): Boolean {
        val future = CompletableFuture<Boolean>()
        handler.post {
            val s = service
            if (s == null) {
                android.util.Log.e("FarAuto", "Accessibility Service not running - cannot perform action $action")
                future.complete(false)
                return@post
            }
            val success = s.performGlobalAction(action)
            android.util.Log.i("FarAuto", "Global action $action result: $success")
            future.complete(success)
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }
}
