package com.dali951.noshorts

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws a solid box over the Shorts tab in YouTube's bottom navigation bar.
 * The box blocks touches, so the Shorts tab cannot be seen or clicked.
 *
 * v1.3 behavior:
 * - visibility AND position are driven by the REAL "Shorts" tab bounds read
 *   from YouTube's accessibility tree (set by ShortsWatchAccessibilityService).
 *   The box is centered on the tab's actual bounds (both axes), appears only
 *   while that icon is actually visible, follows it, and disappears when the
 *   bar hides, a video goes fullscreen, the Shorts player opens, YouTube
 *   closes, or another app opens.
 * - preview mode shows the box anywhere for tuning and always stops when told.
 */
class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null
        private var previewMode = false

        var isRunning = false
            private set

        /** True while the box is actually on screen (for diagnostics). */
        var boxVisible = false
            private set

        /** Screen Y of the box center (for the color sampler); null when hidden. */
        val boxCenterY: Int?
            get() = instance?.boxCenterY

        /** True while the Shorts player is open — the box is suppressed. */
        var inShortsPlayer = false
            private set

        /** Called by the accessibility service whenever the foreground app changes. */
        fun setYouTubeForeground(isYouTube: Boolean) {
            instance?.onYouTubeForeground(isYouTube)
        }

        /** Called by the accessibility service with the real bounds of the Shorts tab (or null). */
        fun setShortsTabRect(rect: Rect?) {
            instance?.onShortsTabRect(rect)
        }

        /** Called by the accessibility service when the Shorts player opens/closes. */
        fun setInShortsPlayer(inPlayer: Boolean) {
            inShortsPlayer = inPlayer
            instance?.onInShortsPlayer(inPlayer)
        }

        /** Called by the accessibility service with the sampled color under the bar strip. */
        fun setAdaptiveColor(color: Int?) {
            instance?.onAdaptiveColor(color)
        }

        /** Re-evaluate visibility/color after settings changed from the UI. */
        fun refresh() {
            instance?.recompute()
        }

        /** Preview mode: show the box on whatever app is open (for tuning). */
        fun setPreview(show: Boolean) {
            previewMode = show
            instance?.recompute()
        }
    }

    private var wm: WindowManager? = null
    private var box: View? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private var youtubeVisible = false
    private var shortsTabRect: Rect? = null
    private var shortsPlayerOpen = false
    private var adaptiveColor: Int? = null
    private var lastRenderedColor = 0

    /** Screen Y of the box center (for the color sampler); null when hidden. */
    var boxCenterY: Int? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        instance = this
        isRunning = true
        createBox()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    private fun createBox() {
        try {
            val view = View(this)
            view.background = ColorDrawable(Prefs.boxColor)
            lastRenderedColor = Prefs.boxColor

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            boxParams = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            box = view
            wm?.addView(view, boxParams)
        } catch (e: Exception) {
            // Missing "Display over other apps" permission — don't crash,
            // just stop; MainActivity re-starts it once the user grants it.
            Log.w("Overlay", "createBox failed: ${e.message}")
            stopSelf()
        }
    }

    private fun onYouTubeForeground(isYouTube: Boolean) {
        youtubeVisible = isYouTube
        recompute()
    }

    private fun onShortsTabRect(rect: Rect?) {
        shortsTabRect = rect
        recompute()
    }

    private fun onInShortsPlayer(inPlayer: Boolean) {
        if (shortsPlayerOpen == inPlayer) return
        shortsPlayerOpen = inPlayer
        recompute()
    }

    private fun onAdaptiveColor(color: Int?) {
        adaptiveColor = color
        applyColor()
    }

    /**
     * The box shows only while the Shorts tab icon is actually on screen and
     * no Shorts player is covering it. "Keep after closing YouTube" (v1.1) is
     * gone — the box never floats over other apps or video content anymore.
     */
    private fun shouldShow(): Boolean = youtubeVisible && shortsTabRect != null && !shortsPlayerOpen

    private fun recompute() {
        val v = box ?: return
        val show = shouldShow() || previewMode
        boxVisible = show
        if (!show) {
            boxCenterY = null
            v.visibility = View.GONE
            return
        }
        positionBox(v)
        applyColor()
    }

    private fun positionBox(v: View) {
        val d = resources.displayMetrics.density

        val boxW = (Prefs.boxWidthDp * d).toInt()
        val boxH = (Prefs.boxHeightDp * d).toInt()
        val bottomOff = (Prefs.bottomOffsetDp * d).toInt()
        val shift = (Prefs.xShiftDp * d).toInt()

        val rect = shortsTabRect
        val centerX: Int
        val centerY: Int
        val width: Int
        val height: Int
        // Real tab node: cover the icon AND its label generously (the node is
        // often just the label pill — nudge up so the icon above it is hidden
        // too). Wide slivers (bar hiding mid-scroll) and the 2px estimate
        // marker fall to the fixed-size path so the box never stretches.
        val nodeMode = rect != null && rect.width() > (8 * d).toInt() &&
            rect.width() <= rect.height() * 3
        if (nodeMode) {
            val nodePad = (24 * d).toInt()
            centerX = rect!!.centerX() + shift
            centerY = rect!!.centerY() + bottomOff - (12 * d).toInt()
            width = maxOf(boxW, rect!!.width() + 2 * nodePad)
            height = maxOf(boxH, rect!!.height() + 2 * nodePad)
        } else {
            // Estimate marker / fallback / weird node: fixed-size box.
            val wPx = resources.displayMetrics.widthPixels
            val hPx = resources.displayMetrics.heightPixels
            centerX = (rect?.centerX() ?: (wPx * 0.30f).toInt()) + shift
            centerY = (rect?.centerY() ?: (hPx - bottomOff - boxH / 2)) + bottomOff
            width = boxW
            height = boxH
        }

        boxCenterY = centerY
        boxParams?.let { p ->
            p.width = width
            p.height = height
            p.x = centerX - width / 2
            p.y = centerY - height / 2
            p.gravity = Gravity.TOP or Gravity.START
        }

        v.visibility = View.VISIBLE
        try {
            wm?.updateViewLayout(v, boxParams)
        } catch (e: Exception) {
            // window may be detached; ignore
        }
    }

    private fun applyColor() {
        val v = box ?: return
        if (v.visibility != View.VISIBLE) return
        val color = if (Prefs.adaptiveEnabled) adaptiveColor ?: Prefs.boxColor else Prefs.boxColor
        if (color != lastRenderedColor) {
            lastRenderedColor = color
            v.background = ColorDrawable(color)
            try {
                wm?.updateViewLayout(v, boxParams)
            } catch (e: Exception) {
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reposition after rotation
        recompute()
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        previewMode = false
        boxVisible = false
        boxCenterY = null
        try {
            box?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // already removed
        }
        super.onDestroy()
    }
}
