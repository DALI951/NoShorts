package com.dali951.noshorts

import android.content.Context
import android.content.SharedPreferences

/** All settings live in one SharedPreferences file. */
object Prefs {
    private const val FILE = "noshorts"
    private lateinit var sp: SharedPreferences

    fun prefs(ctx: Context): SharedPreferences {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        return sp
    }

    fun init(ctx: Context) {
        prefs(ctx)
    }

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    var clickerEnabled: Boolean
        get() = sp.getBoolean("clicker_enabled", true)
        set(v) = sp.edit().putBoolean("clicker_enabled", v).apply()

    var boxColor: Int
        get() = sp.getInt("box_color", 0xFF0F0F0F.toInt())
        set(v) = sp.edit().putInt("box_color", v).apply()

    var boxWidthDp: Float
        get() = sp.getFloat("box_w", 76f)
        set(v) = sp.edit().putFloat("box_w", v).apply()

    var boxHeightDp: Float
        get() = sp.getFloat("box_h", 96f)
        set(v) = sp.edit().putFloat("box_h", v).apply()

    var bottomOffsetDp: Float
        get() = sp.getFloat("box_bottom", 0f)
        set(v) = sp.edit().putFloat("box_bottom", v).apply()

    var xShiftDp: Float
        get() = sp.getFloat("box_shift", 0f)
        set(v) = sp.edit().putFloat("box_shift", v).apply()

    var adaptiveEnabled: Boolean
        get() = sp.getBoolean("adaptive_enabled", true)
        set(v) = sp.edit().putBoolean("adaptive_enabled", v).apply()

    var keepOutsideYouTube: Boolean
        get() = sp.getBoolean("keep_outside", true)
        set(v) = sp.edit().putBoolean("keep_outside", v).apply()

    var autoExitShorts: Boolean
        get() = sp.getBoolean("auto_exit", true)
        set(v) = sp.edit().putBoolean("auto_exit", v).apply()
}
