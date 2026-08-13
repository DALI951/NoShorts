package com.dali951.noshorts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Three jobs:
 * 1) Track the REAL "Shorts" tab in YouTube's bottom bar and feed its bounds
 *    to OverlayService — the box follows the icon and exists only while the
 *    icon is visible. Also auto-starts the overlay when the service connects.
 * 2) Auto-click the "More" (3-dot) button of the Shorts section in the Home feed,
 *    then click "Show fewer Shorts" / "Not interested" — so the feed gets
 *    cleaned without user action.
 * 3) If a Short opens anyway (the Shorts player, or the Shorts tab), press
 *    Back to leave Shorts almost instantly.
 *
 * v1.3 detection notes:
 * - The tab is found by localized label ("Shorts" / "شورتس" …) OR, when no
 *   label is exposed, by geometry: the bottom nav is a row of 5 evenly-spaced
 *   clickable items and item #2 is Shorts. Language-independent.
 * - The tab counts as visible only when its bounds are actually on screen —
 *   bounds are the ground truth, not the (often stale) isVisibleToUser flag.
 * - The box is suppressed while the Shorts player is open (setInShortsPlayer).
 * - A tree dump is logged to logcat (tag ShortsWatch) so detection can be
 *   tuned against the real device without guessing.
 *
 * Cooldowns: feed cleaning runs at most once per 2 minutes, the auto-exit
 * once per 15 seconds (up to 3 backs per detection) — to avoid loops.
 */
class ShortsWatchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShortsWatch"
        private const val YT_PACKAGE = "com.google.android.youtube"
        private const val COOLDOWN_MS = 2 * 60 * 1000L
        private const val EXIT_COOLDOWN_MS = 15 * 1000L
        private const val MAX_EXIT_BACKS = 3
        private const val TAB_POLL_MS = 250L

        /** "Shorts" in the languages the user might have (EN, FR, AR, …). */
        private val SHORTS_LABELS = listOf("shorts", "شورتس")

        /** The tab row lives in the bottom 18% of the screen. */
        private const val NAV_BAND = 0.82f

        /** Content descriptions commonly used for the shelf's 3-dot button. */
        private val MORE_CDS = listOf(
            "more options", "more", "المزيد", "3-dot menu", "see more", "view more"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ytForeground = false
    private var lastShowFewerAt = 0L
    private var lastExitAt = 0L
    private var lastShortsRect: Rect? = null
    private var suppressedByPlayer = false
    private var lastHeartbeatAt = 0L
    private var lastSampleAt = 0L
    private var lastSample: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        // Auto-start the overlay so the box works without opening the app first.
        if (Prefs.overlayEnabled) {
            try {
                startService(Intent(this, OverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "overlay start failed: ${e.message}")
            }
        }
        // The service may have restarted while YouTube was already open —
        // adopt the active window instead of waiting for the next event.
        try {
            val r = rootInActiveWindow
            val pkg = r?.packageName?.toString()
            if (pkg == YT_PACKAGE) {
                ytForeground = true
                OverlayService.setYouTubeForeground(true)
                startTabPolling()
                scheduleScan(1200)
                scheduleExitCheck(700)
                Log.i(TAG, "Service connected while YouTube active")
            }
        } catch (e: Exception) {
            Log.w(TAG, "connect probe error: ${e.message}")
        }
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
                    startTabPolling()
                    scheduleScan(1200)
                    scheduleExitCheck(700)
                    handler.postDelayed({ dumpRelevant(rootInActiveWindow) }, 1500)
                } else {
                    stopTabPolling()
                    OverlayService.setInShortsPlayer(false)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (ytForeground) {
                    scheduleScan(1200)
                    scheduleExitCheck(350)
                }
            }
            // Fires on bar show/hide, fullscreen transitions, scroll end.
            // Trigger an immediate tree re-read so the box follows right away.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (ytForeground) startTabPolling()
            }
        }
    }

    // ------------------------------------------------------------------
    // Shorts TAB tracking (the box)
    // ------------------------------------------------------------------

    /**
     * Poll the Shorts tab every ~250ms while YouTube is foreground. Bounds
     * are the ground truth: when the bar hides (scroll down, fullscreen
     * video) the tab's bounds go off-screen or disappear → box hides.
     */
    private fun startTabPolling() {
        handler.removeCallbacks(tabPollRunnable)
        handler.post(tabPollRunnable)
    }

    private fun stopTabPolling() {
        handler.removeCallbacks(tabPollRunnable)
        updateShortsRect(null)
    }

    private val tabPollRunnable = object : Runnable {
        override fun run() {
            try {
                if (ytForeground) {
                    val root = rootInActiveWindow
                    val rect = if (root != null) {
                        findShortsTab(root)?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
                    } else {
                        null
                    }
                    updateShortsRect(rect)
                    maybeSampleColor()
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatAt > 5000) {
                        lastHeartbeatAt = now
                        Log.i(
                            TAG,
                            "poll: yt=$ytForeground tab=${lastShortsRect != null} " +
                                "player=${OverlayService.inShortsPlayer} " +
                                "overlay=${OverlayService.isRunning} box=${OverlayService.boxVisible}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tab poll error: ${e.message}")
            }
            handler.postDelayed(this, TAB_POLL_MS)
        }
    }

    // ------------------------------------------------------------------
    // Adaptive color (replaces the old screen-capture service)
    // ------------------------------------------------------------------
    //
    // The old ScreenCaptureService used MediaProjection, which pops the
    // annoying "start screen sharing" consent dialog. Instead we read the
    // screen through the accessibility service's takeScreenshot() — no
    // dialog, no extra permission, only works while the a11y service is on
    // (which it always is, it's the core of the app).

    /** Throttled (~1.5s) screenshot sampler; feeds OverlayService. */
    private fun maybeSampleColor() {
        if (!Prefs.adaptiveEnabled || !OverlayService.boxVisible) return
        // takeScreenshot(displayId, …) with ScreenshotResult.getHardwareBuffer()
        // is the only path on Android 16 (getBitmap / the old overload are gone).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val info = serviceInfo ?: return
        if (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) return
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < 1500) return
        lastSampleAt = now
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executor { r -> r.run() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        processScreenshot(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot error: ${e.message}")
        }
    }

    private fun processScreenshot(s: ScreenshotResult) {
        try {
            val hb = s.hardwareBuffer
            val wrapped = Bitmap.wrapHardwareBuffer(hb, null)
            hb.close()
            val bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: return
            try {
                val w = bmp.width
                val h = bmp.height
                val d = resources.displayMetrics.density
                val bottomOff = (Prefs.bottomOffsetDp * d).toInt()
                val boxH = (Prefs.boxHeightDp * d).toInt()
                val y = (h - bottomOff - boxH / 2).coerceIn(0, h - 1)
                val samples = arrayListOf<Int>()
                // Same band as the box, but in the GAPS between the bottom-nav
                // icons (15/42/65/85% width) where the bar is plain background.
                for (fx in floatArrayOf(0.15f, 0.42f, 0.65f, 0.85f)) {
                    val x = (w * fx).toInt().coerceIn(0, w - 1)
                    samples.add(bmp.getPixel(x, y))
                }
                val avg = median(samples)
                val prev = lastSample ?: avg
                lastSample = blend(prev, avg, 0.45f)
                OverlayService.setAdaptiveColor(lastSample)
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "sample error: ${e.message}")
        }
    }

    /** Per-channel median — robust against the odd icon pixel. */
    private fun median(list: List<Int>): Int {
        val rs = list.map { (it shr 16) and 0xFF }.sorted()
        val gs = list.map { (it shr 8) and 0xFF }.sorted()
        val bs = list.map { it and 0xFF }.sorted()
        val mid = list.size / 2
        return (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + ((bv - av) * t).toInt()).coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Pushes the rect to the overlay only when it actually changed. */
    private fun updateShortsRect(rect: Rect?) {
        val suppressed = OverlayService.inShortsPlayer
        if (suppressed != suppressedByPlayer) {
            // Player opened/closed — force a push so the box hides/returns
            // even though the tab rect itself didn't change.
            suppressedByPlayer = suppressed
            OverlayService.setShortsTabRect(if (suppressed) null else rect)
            Log.i(TAG, "tab ${if (suppressed) "null (player)" else rect?.flattenToString()}")
            return
        }
        val prev = lastShortsRect
        val changed = (rect == null) != (prev == null) ||
            (rect != null && prev != null && (
                rect.left != prev.left || rect.top != prev.top ||
                    rect.right != prev.right || rect.bottom != prev.bottom))
        if (!changed) return
        lastShortsRect = rect
        // While the Shorts player is open the tab rect is not trustworthy —
        // the player covers the feed's nav bar. Suppress the box until we
        // leave the player (the tab reappearing means we're back).
        OverlayService.setShortsTabRect(if (suppressed) null else rect)
        Log.i(TAG, "tab ${rect?.flattenToString() ?: "null"}")
    }

    /**
     * The Shorts tab of the bottom nav bar. Tries, in order:
     * 1) a node labeled with a localized "Shorts" word in the bottom band;
     * 2) geometry: a row of >=4 evenly-spaced clickable items in the bottom
     *    band → item #2 is Shorts (YouTube's 5-tab layout, language-free).
     * Returns null when the bar is hidden (scroll down, video, YouTube closed).
     */
    private fun findShortsTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val bottomY = (screenH * NAV_BAND).toInt()

        // 1) labeled match (native search first, DFS as fallback)
        val byText = root.findAccessibilityNodeInfosByText("Shorts")
        if (!byText.isNullOrEmpty()) {
            for (n in byText) {
                if (isShortsLabel(n) && isTabVisible(n, bottomY, screenH, screenW)) return n
            }
        }
        findTextNode(root) { n ->
            isShortsLabel(n) && isTabVisible(n, bottomY, screenH, screenW)
        }?.let { return it }

        // 2) view-id match — some YouTube versions expose the tab node with a
        //    shorts_* id (e.g. shorts_tab). Feed-shelf shorts ids are mid-screen,
        //    so the bottom-band check keeps them out.
        findNodeBfs(root) { n ->
            n.viewIdResourceName?.lowercase()?.contains("shorts") == true &&
                isTabVisible(n, bottomY, screenH, screenW)
        }?.let { return it }

        // 3) geometric fallback
        return findTabByGeometry(root)
    }

    private fun isShortsLabel(n: AccessibilityNodeInfo): Boolean {
        val t = n.text?.toString() ?: ""
        val cd = n.contentDescription?.toString() ?: ""
        return SHORTS_LABELS.any { t.equals(it, true) || cd.equals(it, true) }
    }

    /** Bounds are the ground truth — no isVisibleToUser (it's often stale). */
    private fun isTabVisible(n: AccessibilityNodeInfo, bottomY: Int, screenH: Int, screenW: Int): Boolean {
        val b = Rect().also { n.getBoundsInScreen(it) }
        return b.width() > 0 && b.height() > 0 &&
            b.centerY() > bottomY &&
            b.top < screenH && b.bottom > 0 && b.left >= 0 && b.right <= screenW
    }

    /**
     * Language-free fallback: find a row of evenly-spaced clickable items in
     * the bottom band. YouTube's bar has 5 items → the 2nd is Shorts.
     */
    private fun findTabByGeometry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val bottomY = (screenH * NAV_BAND).toInt()

        val candidates = findNodes(root) { n ->
            if (!n.isVisibleToUser) return@findNodes false
            val b = Rect().also { n.getBoundsInScreen(it) }
            b.height() in 20..240 && b.width() in 20..320 &&
                b.centerY() > bottomY && b.top < screenH && b.bottom > 0 &&
                b.left >= 0 && b.right <= screenW
        }

        val sorted = candidates.map { n -> n to Rect().also { n.getBoundsInScreen(it) } }
            .sortedBy { it.second.centerX() }

        // Merge nodes of the same column (icon + label may be separate nodes);
        // keep the largest one per column.
        val columns = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for ((n, b) in sorted) {
            val last = columns.lastOrNull()
            if (last != null &&
                Math.abs(last.second.centerX() - b.centerX()) < 60 &&
                Math.abs(last.second.centerY() - b.centerY()) < Math.max(b.height(), last.second.height()) * 2
            ) {
                if (b.width() * b.height() > last.second.width() * last.second.height()) {
                    columns[columns.size - 1] = n to b
                }
                continue
            }
            columns.add(n to b)
        }

        if (columns.size < 4) return null
        val gaps = (1 until columns.size).map {
            columns[it].second.centerX() - columns[it - 1].second.centerX()
        }
        val median = gaps.sorted()[gaps.size / 2]
        val evenlySpaced = gaps.all { it > median * 0.5 && it < median * 1.5 }
        if (!evenlySpaced) return null

        val idx = if (columns.size == 5 || columns.size == 4) 1 else return null
        Log.i(TAG, "tab by geometry: ${columns.size} items, picked #$idx")
        return columns[idx].first
    }

    // ------------------------------------------------------------------
    // Shorts PLAYER detection (auto-exit)
    // ------------------------------------------------------------------

    private fun scheduleExitCheck(delayMs: Long) {
        handler.removeCallbacks(exitRunnable)
        handler.postDelayed(exitRunnable, delayMs)
    }

    /**
     * If a Short opens anyway (the Shorts player, or the Shorts tab), press
     * Back to leave Shorts — that's the whole point of the app. Runs ~350ms
     * after content changes so it feels instant.
     */
    private val exitRunnable = Runnable {
        if (!ytForeground || !Prefs.autoExitShorts) return@Runnable
        val now = System.currentTimeMillis()
        if (now - lastExitAt < EXIT_COOLDOWN_MS) return@Runnable
        try {
            val root = rootInActiveWindow ?: return@Runnable
            val shorts = findShortsPlayer(root) ?: return@Runnable
            OverlayService.setInShortsPlayer(true)
            Log.i(TAG, "Shorts open — pressing Back")
            dumpRelevant(root)
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
                        if (findShortsPlayer(r) != null) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            backs++
                            Log.i(TAG, "Still in Shorts — Back again ($backs/$MAX_EXIT_BACKS)")
                        } else {
                            OverlayService.setInShortsPlayer(false)
                            Log.i(TAG, "Out of Shorts")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "exit re-check error: ${e.message}")
                    }
                }, 800L * round)
            }
            // Always release the player flag when the sequence ends — a match
            // that survives MAX backs is likely a false positive (e.g. the
            // feed's Shorts shelf), and suppressing the box forever would be
            // worse than one extra back. Re-detection re-suppresses instantly.
            handler.postDelayed({
                OverlayService.setInShortsPlayer(false)
                Log.i(TAG, "exit sequence done — player flag released")
            }, 800L * MAX_EXIT_BACKS)
        } catch (e: Exception) {
            Log.w(TAG, "exit error: ${e.message}")
        }
    }

    /**
     * Is the Shorts PLAYER open? PRIMARY signal: the player's vertical
     * position bar, exposed as view id "reel_progress_bar" — unique to the
     * Shorts player, never present on the home feed (this is the signal the
     * open-source Shorts-Blocker uses). FALLBACK: exact "Shorts" text pinned
     * to the very top — deliberately NOT "contains" and NOT mid-screen so the
     * home feed's Shorts shelf header can never trigger a back.
     */
    private fun findShortsPlayer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        findNodeBfs(root) { n ->
            n.isVisibleToUser &&
                n.viewIdResourceName?.lowercase()?.contains("reel_progress_bar") == true
        }?.let { return it }
        return findTextNode(root) { n ->
            if (!n.isVisibleToUser) return@findTextNode false
            val t = n.text?.toString() ?: ""
            val cd = n.contentDescription?.toString() ?: ""
            val exact = SHORTS_LABELS.any { t.equals(it, true) || cd.equals(it, true) }
            if (!exact) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            b.centerY() < screenH * 0.30 && b.height() > 0
        }
    }

    // ------------------------------------------------------------------
    // Feed cleaner ("Show fewer Shorts")
    // ------------------------------------------------------------------

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
                tryClickFewer(0)
            } else {
                Log.i(TAG, "More button click failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan error: ${e.message}")
        }
    }

    /** Tries to click "Show fewer Shorts"/"Not interested" up to 2 times. */
    private fun tryClickFewer(attempt: Int) {
        handler.postDelayed({
            try {
                val r = rootInActiveWindow ?: return@postDelayed
                val item = findTextNode(r) { n ->
                    val t = n.text?.toString() ?: ""
                    val cd = n.contentDescription?.toString() ?: ""
                    t.contains("fewer", true) || t.contains("not interested", true) ||
                        cd.contains("fewer", true) || cd.contains("not interested", true) ||
                        t.contains("عدد أقل", true) || t.contains("مهتم", true)
                }
                if (item != null && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "Clicked the menu item")
                    toast("Shorts hidden from feed")
                } else if (attempt < 1) {
                    tryClickFewer(attempt + 1)
                } else {
                    Log.i(TAG, "Menu open but item not found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "menu click error: ${e.message}")
            }
        }, 900L + attempt * 900L)
    }

    /** Finds a "Shorts" text node that is NOT the bottom nav tab label. */
    private fun findShortsHeader(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        return findTextNode(root) { n ->
            val t = n.text?.toString() ?: return@findTextNode false
            val cd = n.contentDescription?.toString() ?: ""
            val isShorts = SHORTS_LABELS.any {
                t.equals(it, true) || cd.contains(it, true) || t.contains(it, true)
            }
            if (!isShorts || !n.isVisibleToUser) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            // Bottom nav bar occupies roughly the bottom 18% of the screen; skip it
            b.centerY() < screenH * NAV_BAND && b.height() > 0
        }
    }

    /**
     * Finds the "More" (3-dot) button on the same row as the Shorts header:
     * 1) any clickable node with a "More"-style content description on the row;
     * 2) fallback: rightmost clickable node whose center Y matches the row.
     */
    private fun findMoreButton(root: AccessibilityNodeInfo, header: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hb = Rect().also { header.getBoundsInScreen(it) }

        findTextNode(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser) return@findTextNode false
            val cd = n.contentDescription?.toString() ?: ""
            val b = Rect().also { n.getBoundsInScreen(it) }
            MORE_CDS.any { cd.equals(it, true) } &&
                Math.abs(b.centerY() - hb.centerY()) < 150 && b.left > hb.centerX()
        }?.let { return it }

        return findTextNode(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser) return@findTextNode false
            val b = Rect().also { n.getBoundsInScreen(it) }
            Math.abs(b.centerY() - hb.centerY()) < 120 &&
                b.left > hb.centerX() &&
                b.width() < 200 && b.height() < 200
        }
    }

    // ------------------------------------------------------------------
    // Tree helpers
    // ------------------------------------------------------------------

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

    /** BFS over the accessibility tree (bounded); returns the first match. */
    private fun findNodeBfs(
        root: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 200) {
            val n = queue.removeFirst()
            count++
            if (match(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    /** Collects every node matching [match] (DFS). */
    private fun findNodes(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        collectMatches(node, match, out)
        return out
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (match(node)) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatches(child, match, out)
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (logcat, tag "ShortsWatch")
    // ------------------------------------------------------------------

    /** Logs everything relevant so detection can be tuned on the device. */
    private fun dumpRelevant(root: AccessibilityNodeInfo?) {
        try {
            root ?: return
            val sb = StringBuilder()
            collectRelevant(root, 0, sb)
            Log.i(TAG, "TREE:\n$sb")
        } catch (e: Exception) {
            Log.w(TAG, "dump error: ${e.message}")
        }
    }

    private fun collectRelevant(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        n ?: return
        val b = Rect().also { n.getBoundsInScreen(it) }
        val t = n.text?.toString() ?: ""
        val cd = n.contentDescription?.toString() ?: ""
        val h = resources.displayMetrics.heightPixels
        val interesting = SHORTS_LABELS.any { t.contains(it, true) || cd.contains(it, true) } ||
            b.centerY() > h * 0.80f || (n.isClickable && b.height() > 0)
        if (interesting) {
            sb.append("  ".repeat(depth.coerceAtMost(12)))
                .append("t='").append(t).append("' cd='").append(cd).append("'")
                .append(" clk=").append(n.isClickable)
                .append(" vis=").append(n.isVisibleToUser)
                .append(" b=").append(b)
                .append('\n')
        }
        for (i in 0 until n.childCount.coerceAtMost(60)) {
            collectRelevant(n.getChild(i), depth + 1, sb)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopTabPolling()
        OverlayService.setYouTubeForeground(false)
        OverlayService.setInShortsPlayer(false)
        super.onDestroy()
    }
}
