package com.dali951.noshorts

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws a solid box over the Shorts tab in YouTube's bottom navigation bar.
 * The box blocks touches, so the Shorts tab cannot be seen or clicked.
 *
 * v1.2 behavior:
 * - visibility AND position are driven by the REAL "Shorts" tab bounds read
 *   from YouTube's accessibility tree (set by ShortsWatchAccessibilityService).
 *   The box appears only while that icon is actually visible, follows it, and
 *   disappears when the bar hides, YouTube closes, or another app opens.
 * - preview mode shows the box anywhere for tuning and always stops when told.
 */
class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null
        private var previewMode = false

        var isRunning = false
            private set

        /** Called by the accessibility service whenever the foreground app changes. */
        fun setYouTubeForeground(isYouTube: Boolean) {
            instance?.onYouTubeForeground(isYouTube)
        }

        /** Called by the accessibility service with the real bounds of the Shorts tab (or null). */
        fun setShortsTabRect(rect: Rect?) {
            instance?.onShortsTabRect(rect)
        }

        /** Called by ScreenCaptureService with the color under the bar strip. */
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
    private var adaptiveColor: Int? = null
    private var lastRenderedColor = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        instance = this
        isRunning = true
        createBox()
    }

    private fun createBox() {
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
    }

    private fun onYouTubeForeground(isYouTube: Boolean) {
        youtubeVisible = isYouTube
        recompute()
    }

    private fun onShortsTabRect(rect: Rect?) {
        shortsTabRect = rect
        recompute()
    }

    private fun onAdaptiveColor(color: Int?) {
        adaptiveColor = color
        applyColor()
    }

    /**
     * The box shows only while the Shorts tab icon is actually on screen.
     * "Keep after closing YouTube" (v1.1) is gone — the box never floats
     * over other apps or video content anymore.
     */
    private fun shouldShow(): Boolean = youtubeVisible && shortsTabRect != null

    private fun recompute() {
        val v = box ?: return
        val show = shouldShow() || previewMode
        // Let the color sampler know whether the strip it reads is the real
        // nav bar or video content (bar hidden → box hidden → no sampling).
        ScreenCaptureService.setBoxVisible(show)
        if (!show) {
            v.visibility = View.GONE
            return
        }
        positionBox(v)
        applyColor()
    }

    private fun positionBox(v: View) {
        val d = resources.displayMetrics.density
        val wPx = resources.displayMetrics.widthPixels
        val hPx = resources.displayMetrics.heightPixels

        val boxW = (Prefs.boxWidthDp * d).toInt()
        val boxH = (Prefs.boxHeightDp * d).toInt()
        val bottomOff = (Prefs.bottomOffsetDp * d).toInt()
        val shift = (Prefs.xShiftDp * d).toInt()

        val rect = shortsTabRect
        val centerX: Int
        val width: Int
        if (rect != null) {
            // Follow the real icon: center on its node, never narrower than it.
            val nodePad = (8 * d).toInt()
            centerX = rect.centerX() + shift
            width = maxOf(boxW, rect.width() + 2 * nodePad)
        } else {
            // Fallback for preview while YouTube is closed: 2nd of 5 tabs.
            centerX = (wPx * 0.30f).toInt() + shift
            width = boxW
        }

        boxParams?.let { p ->
            p.width = width
            p.height = boxH
            p.x = centerX - width / 2
            p.y = hPx - bottomOff - boxH
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
        try {
            box?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // already removed
        }
        super.onDestroy()
    }
}
