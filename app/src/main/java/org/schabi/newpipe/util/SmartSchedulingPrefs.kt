package org.schabi.newpipe.util

import android.content.Context
import androidx.preference.PreferenceManager

object SmartSchedulingPrefs {
    fun isEnabled(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean("pref_smart_scheduling_enabled", true)
}
