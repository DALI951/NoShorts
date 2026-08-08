package com.dali951.noshorts

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
 * Position logic: YouTube's bottom bar has 5 items (Home, Shorts, +, Subscriptions, You).
 * Item #2 (Shorts) is centered at 30% of the screen width. The bar sits directly
 * above the gesture/navigation inset at the bottom of the screen.
 *
 * v1.1 behavior:
 * - hides together with the bottom bar when YouTube hides it on scroll
 *   (driven by ShortsWatchAccessibilityService.setBarVisible)
 * - color follows the content behind it (sampled by ScreenCaptureService)
 * - optionally stays visible after YouTube closes (Prefs.keepOutsideYouTube)
 */
class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null
        private var previewMode = false

        /** Called by the accessibility service whenever the foreground app changes. */
        fun setYouTubeForeground(isYouTube: Boolean) {
            instance?.onYouTubeForeground(isYouTube)
        }

        /** Called by the accessibility service when YouTube's bottom bar shows/hides. */
        fun setBarVisible(visible: Boolean) {
            instance?.onBarVisible(visible)
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
    private var barVisible = true
    private var adaptiveColor: Int? = null
    private var lastRenderedColor = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        instance = this
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
        if (isYouTube) barVisible = true // corrected shortly after by the bar checker
        recompute()
    }

    private fun onBarVisible(visible: Boolean) {
        barVisible = visible
        recompute()
    }

    private fun onAdaptiveColor(color: Int?) {
        adaptiveColor = color
        applyColor()
    }

    private fun shouldShow(): Boolean =
        if (youtubeVisible) barVisible else Prefs.keepOutsideYouTube

    private fun recompute() {
        val v = box ?: return
        val show = shouldShow() || previewMode

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

        // Shorts tab center = 30% of screen width (2nd of 5 equal items)
        val centerX = (wPx * 0.30f).toInt() + shift

        boxParams?.let { p ->
            p.width = boxW
            p.height = boxH
            p.x = centerX - boxW / 2
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
        previewMode = false
        try {
            box?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // already removed
        }
        super.onDestroy()
    }
}
