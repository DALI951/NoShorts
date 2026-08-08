package com.dali951.noshorts

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Two jobs:
 * 1) Tell OverlayService when YouTube is in the foreground (show/hide the box).
 * 2) Auto-click the "More" (3-dot) button of the Shorts section in the Home feed,
 *    then click "Show fewer Shorts" — so the feed gets cleaned without user action.
 *
 * Cooldown: the click only runs at most once per 5 minutes to avoid loops.
 */
class ShortsWatchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShortsWatch"
        private const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 5 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ytForeground = false
    private var lastShowFewerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        Prefs.init(this)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                ytForeground = pkg == YT_PACKAGE
                OverlayService.setYouTubeForeground(ytForeground)
                Log.i(TAG, "Foreground: $pkg")
                if (ytForeground) scheduleScan(1500)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (ytForeground && Prefs.clickerEnabled) scheduleScan(1500)
            }
        }
    }

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs)
    }

    private val scanRunnable = Runnable {
        if (!ytForeground || !Prefs.clickerEnabled) return@Runnable

        val now = System.currentTimeMillis()
        if (now - lastShowFewerAt < COOLDOWN_MS) return@Runnable

        try {
            val root = rootInActiveWindow ?: return@Runnable
            val header = findShortsHeader(root) ?: return@Runnable
            val moreButton = findMoreButton(root, header) ?: return@Runnable

            if (moreButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked the 3-dot next to a Shorts section")
                lastShowFewerAt = now

                // Wait for the menu popup, then click "Show fewer Shorts"
                handler.postDelayed({
                    try {
                        val r = rootInActiveWindow ?: return@postDelayed
                        val item = findTextNode(r) { n ->
                            val t = n.text?.toString() ?: ""
                            t.contains("fewer Shorts", ignoreCase = true) ||
                                t.contains("fewer shorts", ignoreCase = true)
                        }
                        if (item != null && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "Clicked 'Show fewer Shorts'")
                            toast("Shorts hidden from feed")
                        } else {
                            Log.i(TAG, "Menu open but 'Show fewer Shorts' not found")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "menu click error: ${e.message}")
                    }
                }, 900)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan error: ${e.message}")
        }
    }

    /** Finds a "Shorts" text node that is NOT the bottom nav tab label. */
    private fun findShortsHeader(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        return findTextNode(root) { n ->
            val t = n.text?.toString() ?: return@findTextNode false
            val cd = n.contentDescription?.toString() ?: ""
            val isShorts = t.equals("Shorts", ignoreCase = true) ||
                cd.contains("Shorts", ignoreCase = true)
            if (!isShorts || !n.isVisibleToUser) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            // Bottom nav bar occupies roughly the bottom 12% of the screen; skip it
            b.centerY() < screenH * 0.85 && b.height() > 0
        }
    }

    /**
     * Finds the "More" (3-dot) button on the same row as the Shorts header:
     * rightmost clickable node whose center Y matches the header row.
     */
    private fun findMoreButton(root: AccessibilityNodeInfo, header: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hb = Rect().also { header.getBoundsInScreen(it) }
        val found = findTextNode(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            Math.abs(b.centerY() - hb.centerY()) < 120 &&
                b.left > hb.centerX() &&
                b.width() < 200 && b.height() < 200
        }
        return found
    }

    /** DFS over the accessibility tree; returns the first node matching [match]. */
    private fun findTextNode(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTextNode(child, match)
            if (found != null) return found
        }
        return null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        OverlayService.setYouTubeForeground(false)
        super.onDestroy()
    }
}
