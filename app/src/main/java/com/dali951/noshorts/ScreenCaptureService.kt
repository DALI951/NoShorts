package com.dali951.noshorts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display

/**
 * Captures the screen (MediaProjection) and samples the color of the YouTube
 * bottom-bar strip at 70% width — the same strip our overlay box sits on,
 * but away from the box itself (30%). Feeds the color to OverlayService so
 * the box always matches what is behind it.
 *
 * Sampling happens ~2.5x/second on a small scaled frame — cheap enough.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val SCALE = 0.35f
        private const val INTERVAL_MS = 400L

        var running = false
            private set
        private var lastSample: Int? = null

        fun sampledColor(): Int? = lastSample

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSampleAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("capture", "Color matching", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_visibility)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(resultCode, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped")
                cleanup()
                stopSelf()
            }
        }, handler)

        val dm = resources.displayMetrics
        val scaledW = (dm.widthPixels * SCALE).toInt()
        val scaledH = (dm.heightPixels * SCALE).toInt()
        val reader = ImageReader.newInstance(scaledW, scaledH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ processFrame(reader) }, handler)

        virtualDisplay = p.createVirtualDisplay(
            "NoShortsSampler",
            scaledW, scaledH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        running = true
        Log.i(TAG, "capture started ${scaledW}x$scaledH")
    }

    private fun processFrame(reader: ImageReader) {
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < INTERVAL_MS) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val image: Image = try {
            reader.acquireLatestImage() ?: return
        } catch (e: Exception) {
            return
        }
        lastSampleAt = now
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val imgW = image.width
            val imgH = image.height

            val d = resources.displayMetrics.density
            val wReal = resources.displayMetrics.widthPixels
            val hReal = resources.displayMetrics.heightPixels
            // Sample the nav-bar strip at 70% width (Subscriptions area):
            // same background as under our box (30%), but away from the box itself.
            val bottomOff = (Prefs.bottomOffsetDp * d).toInt()
            val boxH = (Prefs.boxHeightDp * d).toInt()
            val yReal = hReal - bottomOff - boxH / 2
            val xReal = (wReal * 0.70f).toInt()
            val sx = (xReal * SCALE).toInt().coerceIn(2, imgW - 3)
            val sy = (yReal * SCALE).toInt().coerceIn(2, imgH - 3)

            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val off = (sy + dy) * rowStride + (sx + dx) * pixelStride
                    b += buffer.get(off).toInt() and 0xFF
                    g += buffer.get(off + 1).toInt() and 0xFF
                    r += buffer.get(off + 2).toInt() and 0xFF
                    n++
                }
            }
            val avg = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val prev = lastSample ?: avg
            lastSample = blend(prev, avg, 0.35f)
            OverlayService.setAdaptiveColor(lastSample)
        } catch (e: Exception) {
            Log.w(TAG, "sample error: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + ((bv - av) * t).toInt()).coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        running = false
        OverlayService.setAdaptiveColor(null)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
