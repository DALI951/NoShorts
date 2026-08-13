package com.dali951.noshorts

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var previewActive = false
    private var setupShownThisSession = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var btnPreview: Button
    private lateinit var dlBanner: LinearLayout
    private lateinit var dlProgress: ProgressBar
    private lateinit var dlText: TextView

    // chips: id -> color
    private val chipColors = listOf(
        R.id.chipDark to 0xFF0F0F0F.toInt(),
        R.id.chipLight to 0xFFFFFFFF.toInt(),
        R.id.chipBlack to 0xFF000000.toInt(),
        R.id.chipDark2 to 0xFF121212.toInt(),
        R.id.chipDark3 to 0xFF1F1F1F.toInt(),
        R.id.chipDark4 to 0xFF282828.toInt()
    )

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                UpdateManager.onDownloadComplete(this@MainActivity, id)
                updateDownloadBanner()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        val swOverlay = findViewById<SwitchMaterial>(R.id.swOverlay)
        val swClicker = findViewById<SwitchMaterial>(R.id.swClicker)
        val swAdaptive = findViewById<SwitchMaterial>(R.id.swAdaptive)
        val swAutoExit = findViewById<SwitchMaterial>(R.id.swAutoExit)
        val sbWidth = findViewById<SeekBar>(R.id.sbWidth)
        val sbHeight = findViewById<SeekBar>(R.id.sbHeight)
        val sbBottom = findViewById<SeekBar>(R.id.sbBottom)
        val sbShift = findViewById<SeekBar>(R.id.sbShift)
        btnPreview = findViewById(R.id.btnPreview)
        dlBanner = findViewById(R.id.dlBanner)
        dlProgress = findViewById(R.id.dlProgress)
        dlText = findViewById(R.id.dlText)
        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        val txtLatest = findViewById<TextView>(R.id.txtLatest)

        // ---- load saved values ----
        swOverlay.isChecked = Prefs.overlayEnabled
        swClicker.isChecked = Prefs.clickerEnabled
        swAdaptive.isChecked = Prefs.adaptiveEnabled
        swAutoExit.isChecked = Prefs.autoExitShorts
        sbWidth.progress = Prefs.boxWidthDp.toInt().coerceIn(0, 140)
        sbHeight.progress = Prefs.boxHeightDp.toInt().coerceIn(0, 140)
        sbBottom.progress = Prefs.bottomOffsetDp.toInt().coerceIn(0, 60)
        sbShift.progress = (Prefs.xShiftDp.toInt() + 120).coerceIn(0, 240)
        updateSliderLabels()

        // ---- chips ----
        selectChipForColor(Prefs.boxColor)
        chipColors.forEach { (id, color) ->
            val chip = findViewById<Chip>(id)
            chip.setOnClickListener {
                chipColors.forEach { (otherId, _) ->
                    findViewById<Chip>(otherId).isChecked = otherId == id
                }
                Prefs.boxColor = color
                refreshBox()
            }
        }

        // ---- switches ----
        swOverlay.setOnCheckedChangeListener { _, checked ->
            Prefs.overlayEnabled = checked
            if (checked) {
                startService(Intent(this, OverlayService::class.java))
            } else {
                OverlayService.setPreview(false)
                stopService(Intent(this, OverlayService::class.java))
            }
        }

        swClicker.setOnCheckedChangeListener { _, checked -> Prefs.clickerEnabled = checked }

        swAdaptive.setOnCheckedChangeListener { _, checked ->
            Prefs.adaptiveEnabled = checked
            OverlayService.refresh()
        }

        swAutoExit.setOnCheckedChangeListener { _, checked -> Prefs.autoExitShorts = checked }

        // ---- sliders ----
        sbWidth.setOnSeekBarChangeListener(simpleSeek { Prefs.boxWidthDp = it.toFloat(); updateSliderLabels(); refreshBox() })
        sbHeight.setOnSeekBarChangeListener(simpleSeek { Prefs.boxHeightDp = it.toFloat(); updateSliderLabels(); refreshBox() })
        sbBottom.setOnSeekBarChangeListener(simpleSeek { Prefs.bottomOffsetDp = it.toFloat(); updateSliderLabels(); refreshBox() })
        sbShift.setOnSeekBarChangeListener(simpleSeek { Prefs.xShiftDp = (it - 120).toFloat(); updateSliderLabels(); refreshBox() })

        btnPreview.setOnClickListener {
            if (previewActive) {
                OverlayService.setPreview(false)
                previewActive = false
                btnPreview.text = "Preview box on this screen"
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return@setOnClickListener
                }
                startService(Intent(this, OverlayService::class.java))
                OverlayService.setPreview(true)
                previewActive = true
                btnPreview.text = "Stop preview"
            }
        }

        // ---- setup buttons (TextViews acting as buttons) ----
        findViewById<TextView>(R.id.btnOverlayPerm).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<TextView>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<TextView>(R.id.btnCapture).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ---- update card ----
        txtVersion.text = "Version ${BuildConfig.VERSION_NAME}"
        updateLatestLabel(null)
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            UpdateChecker.check(this, force = true) { latest -> updateLatestLabel(latest) }
        }
        findViewById<TextView>(R.id.btnWhatsNew).setOnClickListener {
            UpdateChecker.showWhatsNew(this)
        }

        // ---- open YouTube ----
        findViewById<Button>(R.id.btnYouTube).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.youtube.com")
                        setPackage("com.google.android.youtube")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
            }
        }

        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )

        updateStatus()
        updateDownloadBanner()
        startDownloadPoller()

        UpdateChecker.checkOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateLatestLabel(null)
        UpdateChecker.checkPendingRetry(this)
        // Restart the overlay if it was killed while the box should be active.
        if (Prefs.overlayEnabled && !OverlayService.isRunning) {
            try {
                startService(Intent(this, OverlayService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
        // First-run setup: ask for the permissions from the start, one at a
        // time, until everything is granted.
        if (!Prefs.setupDone && !setupShownThisSession) {
            setupShownThisSession = true
            handler.postDelayed({ showNextSetupStep() }, 400)
        }
    }

    // ------------------------------------------------------------------
    // First-run setup
    // ------------------------------------------------------------------

    /** Walks through the missing permissions one dialog at a time. */
    private fun showNextSetupStep() {
        when {
            !Settings.canDrawOverlays(this) -> showSetupDialog(
                "Overlay permission",
                "Lets NoShorts draw the box over the Shorts tab. Tap Continue, then turn on \"Display over other apps\" for NoShorts.",
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            !isAccessibilityEnabled() -> showSetupDialog(
                "Accessibility service",
                "Lets NoShorts see YouTube's screen, hide the Shorts tab and press Back to leave Shorts. Tap Continue, then enable NoShorts.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            !isBatteryExempt() -> showSetupDialog(
                "Battery optimization",
                "Keeps the blocking working while you use other apps. Tap Continue and choose \"Don't optimize\".",
                try {
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                } catch (e: Exception) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
            )
            else -> Prefs.setupDone = true
        }
    }

    private fun showSetupDialog(title: String, desc: String, intent: Intent) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(desc)
            .setPositiveButton("Continue") { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open that setting", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private val downloadPoller = object : Runnable {
        override fun run() {
            updateDownloadBanner()
            handler.postDelayed(this, 1000)
        }
    }

    private fun startDownloadPoller() {
        handler.removeCallbacks(downloadPoller)
        handler.postDelayed(downloadPoller, 1000)
    }

    private fun updateDownloadBanner() {
        val pct = UpdateManager.progress(this)
        val downloading = UpdateManager.isDownloading()
        dlBanner.visibility = if (downloading) View.VISIBLE else View.GONE
        if (downloading) {
            dlProgress.progress = pct.toInt().coerceIn(0, 100)
            val version = UpdateManager.downloadingVersion ?: ""
            dlText.text = String.format(Locale.US, "v%s · %d%%", version, pct.toInt())
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun updateLatestLabel(latest: String?) {
        val tv = findViewById<TextView>(R.id.txtLatest)
        val v = latest ?: UpdateChecker.cachedLatestVersion(this)
        tv.text = if (v.isEmpty()) "Latest: unknown — tap Check for updates" else "Latest: v$v"
    }

    private fun refreshBox() {
        OverlayService.refresh()
    }

    private fun updateSliderLabels() {
        findViewById<TextView>(R.id.lblWidth).text = "${Prefs.boxWidthDp.toInt()} dp"
        findViewById<TextView>(R.id.lblHeight).text = "${Prefs.boxHeightDp.toInt()} dp"
        findViewById<TextView>(R.id.lblBottom).text = "${Prefs.bottomOffsetDp.toInt()} dp"
        findViewById<TextView>(R.id.lblShift).text = "${Prefs.xShiftDp.toInt()} dp"
    }

    private fun selectChipForColor(color: Int) {
        chipColors.forEach { (id, c) ->
            findViewById<Chip>(id).isChecked = c == color
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals("$packageName/${ShortsWatchAccessibilityService::class.java.name}", ignoreCase = true) }
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = isAccessibilityEnabled()
        val batteryOk = isBatteryExempt()
        val captureOk = accessOk && Prefs.adaptiveEnabled

        findViewById<TextView>(R.id.statusOverlay).let { tv ->
            tv.text = if (overlayOk) "Overlay permission: OK" else "Overlay permission: needed"
            tv.setTextColor(if (overlayOk) getColorCompat(R.color.status_ok) else getColorCompat(R.color.status_warn))
        }
        findViewById<TextView>(R.id.statusAccess).let { tv ->
            tv.text = if (accessOk) "Accessibility service: on" else "Accessibility service: off"
            tv.setTextColor(if (accessOk) getColorCompat(R.color.status_ok) else getColorCompat(R.color.status_warn))
        }
        findViewById<TextView>(R.id.statusBattery).let { tv ->
            tv.text = if (batteryOk) "Battery: excluded from optimization" else "Battery: not excluded"
            tv.setTextColor(if (batteryOk) getColorCompat(R.color.status_ok) else getColorCompat(R.color.status_warn))
        }
        findViewById<TextView>(R.id.statusCapture).let { tv ->
            tv.text = if (captureOk) "Color match (a11y): on" else "Color match (a11y): off"
            tv.setTextColor(if (captureOk) getColorCompat(R.color.status_ok) else getColorCompat(R.color.status_warn))
        }
    }

    private fun getColorCompat(colorRes: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(colorRes) else resources.getColor(colorRes)
}
