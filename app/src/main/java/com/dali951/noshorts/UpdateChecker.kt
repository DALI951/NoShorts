package com.dali951.noshorts

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Same update system as task-tracker:
 * - checks GitHub Releases API for the latest release
 * - compares versions (dot-separated ints, missing parts = 0)
 * - shows Install Now / Later (10 min) / Never dialog with changelog
 * - throttled to one check per 30 minutes
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "DALI951/NoShorts"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_DISMISSED = "update_dismissed"
    private const val KEY_RETRY_AT = "update_retry_at"
    private const val KEY_RETRY_VERSION = "update_retry_version"
    private const val KEY_RETRY_URL = "update_retry_url"
    private const val KEY_RETRY_BODY = "update_retry_body"
    private const val KEY_CACHED_VERSION = "update_cached_version"
    private const val KEY_CACHED_BODY = "update_cached_body"

    private const val THROTTLE_MS = 30 * 60 * 1000L
    private const val RETRY_DELAY_MS = 10 * 60 * 1000L

    data class ReleaseInfo(val version: String, val changelog: String, val apkUrl: String?)

    /** Auto-check a few seconds after app launch. */
    fun checkOnLaunch(activity: Activity) {
        Handler(Looper.getMainLooper()).postDelayed({ check(activity, force = false) }, 2000)
    }

    /** Latest version known from the last successful check ("" if never). */
    fun cachedLatestVersion(ctx: Context): String =
        Prefs.prefs(ctx).getString(KEY_CACHED_VERSION, "") ?: ""

    /** After "Later", retry when the app comes back and 10 minutes passed. */
    fun checkPendingRetry(activity: Activity) {
        val prefs = Prefs.prefs(activity)
        val retryAt = prefs.getLong(KEY_RETRY_AT, 0)
        if (retryAt == 0L || System.currentTimeMillis() < retryAt) return
        val version = prefs.getString(KEY_RETRY_VERSION, "") ?: return
        val url = prefs.getString(KEY_RETRY_URL, "") ?: return
        val body = prefs.getString(KEY_RETRY_BODY, "") ?: return
        prefs.edit()
            .remove(KEY_RETRY_AT).remove(KEY_RETRY_VERSION)
            .remove(KEY_RETRY_URL).remove(KEY_RETRY_BODY)
            .apply()
        if (prefs.getString(KEY_DISMISSED, null) == version) return
        activity.runOnUiThread { showUpdateDialog(activity, version, url, body) }
    }

    fun check(activity: Activity, force: Boolean, onDone: ((String?) -> Unit)? = null) {
        val prefs = Prefs.prefs(activity)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - last < THROTTLE_MS) return
        }
        Thread {
            try {
                val info = fetchLatest() ?: throw RuntimeException("no release")
                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putString(KEY_CACHED_VERSION, info.version)
                    .putString(KEY_CACHED_BODY, info.changelog)
                    .apply()

                if (compareVersions(info.version, BuildConfig.VERSION_NAME) <= 0) {
                    // Already up to date — only say so when the user asked (button).
                    if (force) toast(activity, "You're up to date (v${info.version})")
                    onDone?.invoke(info.version)
                    return@Thread
                }
                if (!force && prefs.getString(KEY_DISMISSED, null) == info.version) {
                    onDone?.invoke(info.version)
                    return@Thread
                }
                val apkUrl = info.apkUrl ?: return@Thread

                onDone?.invoke(info.version)
                activity.runOnUiThread { showUpdateDialog(activity, info.version, apkUrl, info.changelog) }
            } catch (e: Exception) {
                Log.w(TAG, "check failed: ${e.message}")
                if (force) toast(activity, "Could not check updates — check your internet")
                onDone?.invoke(null)
            }
        }.start()
    }

    private fun toast(activity: Activity, msg: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** "What's new" — latest release changelog, cached or freshly fetched. */
    fun showWhatsNew(activity: Activity) {
        val prefs = Prefs.prefs(activity)
        val cachedVersion = prefs.getString(KEY_CACHED_VERSION, "")
        val cachedBody = prefs.getString(KEY_CACHED_BODY, "")
        if (!cachedVersion.isNullOrEmpty()) {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("What's new in v$cachedVersion")
                    .setMessage(cachedBody ?: "No changelog.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        Thread {
            try {
                val info = fetchLatest() ?: return@Thread
                prefs.edit()
                    .putString(KEY_CACHED_VERSION, info.version)
                    .putString(KEY_CACHED_BODY, info.changelog)
                    .apply()
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("What's new in v${info.version}")
                        .setMessage(info.changelog)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Could not fetch release info", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun fetchLatest(): ReleaseInfo? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "NoShorts/${BuildConfig.VERSION_NAME} (DALI951)")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) return null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val tag = json.optString("tag_name", "").removePrefix("v")
            val body = json.optString("body", "")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            return ReleaseInfo(tag, body, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val av = if (i < aParts.size) aParts[i] else 0
            val bv = if (i < bParts.size) bParts[i] else 0
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun showUpdateDialog(activity: Activity, version: String, apkUrl: String, body: String) {
        val prefs = Prefs.prefs(activity)
        AlertDialog.Builder(activity)
            .setTitle("NoShorts v$version available")
            .setMessage(
                if (body.isBlank()) "A new version is ready to install." else body.trim()
            )
            .setPositiveButton("Install") { _, _ ->
                UpdateManager.start(activity, apkUrl, version)
            }
            .setNeutralButton("Later") { _, _ ->
                prefs.edit()
                    .putLong(KEY_RETRY_AT, System.currentTimeMillis() + RETRY_DELAY_MS)
                    .putString(KEY_RETRY_VERSION, version)
                    .putString(KEY_RETRY_URL, apkUrl)
                    .putString(KEY_RETRY_BODY, body)
                    .apply()
            }
            .setNegativeButton("Never") { _, _ ->
                prefs.edit()
                    .putString(KEY_DISMISSED, version)
                    .remove(KEY_RETRY_AT)
                    .apply()
            }
            .setCancelable(false)
            .show()
    }
}
