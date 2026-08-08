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
 * Three jobs:
 * 1) Tell OverlayService when YouTube is in the foreground (show/hide the box).
 * 2) Auto-click the "More" (3-dot) button of the Shorts section in the Home feed,
 *    then click "Show fewer Shorts" — so the feed gets cleaned without user action.
 * 3) If a Short opens anyway (player or Shorts tab), press Back to leave Shorts.
 *
 * Cooldowns: the click only runs at most once per 5 minutes, the auto-exit
 * once per 45 seconds (up to 3 backs per detection) — to avoid loops.
 */
class ShortsWatchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShortsWatch"
        private const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private const val EXIT_COOLDOWN_MS = 45 * 1000L
        private const val MAX_EXIT_BACKS = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ytForeground = false
    private var lastShowFewerAt = 0L
    private var lastBarVisible = true
    private var lastExitAt = 0L

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
                if (ytForeground) {
                    scheduleScan(1500)
                    scheduleBarCheck(700)
                    scheduleExitCheck(1500)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (ytForeground) {
                    scheduleScan(1500)
                    scheduleBarCheck(300)
                    scheduleExitCheck(1800)
                }
            }
        }
    }

    /**
     * YouTube hides its bottom bar on scroll-down. Detect whether the bar is
     * visible by looking for its labels (Home/Shorts/Subscriptions/You) in the
     * bottom 15% of the screen, and mirror that to the overlay box.
     */
    private fun scheduleBarCheck(delayMs: Long) {
        handler.removeCallbacks(barCheckRunnable)
        handler.postDelayed(barCheckRunnable, delayMs)
    }

    private val barCheckRunnable = Runnable {
        if (!ytForeground) return@Runnable
        try {
            val root = rootInActiveWindow ?: return@Runnable
            val screenH = resources.displayMetrics.heightPixels
            val found = findTextNode(root) { n ->
                val t = n.text?.toString() ?: ""
                val b = Rect().also { n.getBoundsInScreen(it) }
                n.isVisibleToUser && b.height() > 0 &&
                    b.centerY() > screenH * 0.85 &&
                    (t == "Home" || t == "Shorts" || t == "Subscriptions" || t == "You")
            }
            val visible = found != null
            if (visible != lastBarVisible) {
                lastBarVisible = visible
                OverlayService.setBarVisible(visible)
                Log.i(TAG, "Nav bar visible: $visible")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bar check error: ${e.message}")
        }
    }

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun scheduleExitCheck(delayMs: Long) {
        handler.postDelayed(exitRunnable, delayMs)
    }

    /**
     * If a Short opens anyway (the Shorts player, or the Shorts tab), press
     * Back to leave Shorts — that's the whole point of the app.
     */
    private val exitRunnable = Runnable {
        if (!ytForeground || !Prefs.autoExitShorts) return@Runnable
        val now = System.currentTimeMillis()
        if (now - lastExitAt < EXIT_COOLDOWN_MS) return@Runnable
        try {
            val root = rootInActiveWindow ?: return@Runnable
            val shorts = findShortsAtTop(root) ?: return@Runnable
            Log.i(TAG, "Shorts open — pressing Back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastExitAt = now
            var backs = 1
            // After the back animation, if we're still in Shorts, back again.
            // (Player → its feed grid → previous tab, max 3 total.)
            for (round in 1..(MAX_EXIT_BACKS - 1)) {
                handler.postDelayed({
                    try {
                        if (!ytForeground || backs >= MAX_EXIT_BACKS) return@postDelayed
                        val r = rootInActiveWindow ?: return@postDelayed
                        if (findShortsAtTop(r) != null) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            backs++
                            Log.i(TAG, "Still in Shorts — Back again ($backs/$MAX_EXIT_BACKS)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "exit re-check error: ${e.message}")
                    }
                }, 1400L * round)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exit error: ${e.message}")
        }
    }

    /** "Shorts" title near the top of the screen = Shorts player or Shorts tab. */
    private fun findShortsAtTop(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        return findTextNode(root) { n ->
            if (!n.isVisibleToUser) return@findTextNode false
            val t = n.text?.toString() ?: ""
            val cd = n.contentDescription?.toString() ?: ""
            val isShorts = t.equals("Shorts", ignoreCase = true) ||
                (cd.isNotEmpty() && cd.equals("Shorts", ignoreCase = true))
            if (!isShorts) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            b.centerY() < screenH * 0.30 && b.height() > 0
        }
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
    private fun findShortsHeader(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {        val screenH = resources.displayMetrics.heightPixels
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
