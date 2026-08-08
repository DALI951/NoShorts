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
 */
class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null
        private var previewMode = false

        /** Called by the accessibility service whenever the foreground app changes. */
        fun setYouTubeForeground(isYouTube: Boolean) {
            instance?.updateVisibility(isYouTube)
        }

        /** Preview mode: show the box on whatever app is open (for tuning). */
        fun setPreview(show: Boolean) {
            previewMode = show
            instance?.updateVisibility(show || instance?.youtubeVisible == true)
        }
    }

    private var wm: WindowManager? = null
    private var box: View? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private var youtubeVisible = false

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

    private fun updateVisibility(showYouTube: Boolean) {
        youtubeVisible = showYouTube
        val v = box ?: return
        val shouldShow = showYouTube || previewMode

        if (!shouldShow) {
            v.visibility = View.GONE
            return
        }

        val d = resources.displayMetrics.density
        val wPx = resources.displayMetrics.widthPixels
        val hPx = resources.displayMetrics.heightPixels

        val boxW = (Prefs.boxWidthDp * d).toInt()
        val boxH = (Prefs.boxHeightDp * d).toInt()
        val bottomOff = (Prefs.bottomOffsetDp * d).toInt()
        val shift = (Prefs.xShiftDp * d).toInt()

        // Shorts tab center = 30% of screen width (2nd of 5 equal items)
        val centerX = (wPx * 0.30f).toInt() + shift
        val left = centerX - boxW / 2

        boxParams?.let { p ->
            p.width = boxW
            p.height = boxH
            p.x = left
            p.y = hPx - bottomOff - boxH
            p.gravity = Gravity.TOP or Gravity.START
        }

        v.background = ColorDrawable(Prefs.boxColor)
        v.visibility = View.VISIBLE
        try {
            wm?.updateViewLayout(v, boxParams)
        } catch (e: Exception) {
            // window may be detached; ignore
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reposition after rotation
        updateVisibility(youtubeVisible)
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
