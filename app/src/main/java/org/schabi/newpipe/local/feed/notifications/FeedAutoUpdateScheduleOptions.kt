/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.notifications

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * Information for the Scheduler which auto-updates all feeds.
 * See [FeedAutoUpdateWorker]
 */
data class FeedAutoUpdateScheduleOptions(
    val hourOfDay: Int,
    val isRequireNonMeteredNetwork: Boolean
) {

    companion object {

        @JvmStatic
        fun from(context: Context): FeedAutoUpdateScheduleOptions {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            return FeedAutoUpdateScheduleOptions(
                hourOfDay = preferences.getString(
                    context.getString(R.string.feed_auto_update_time_key),
                    null
                )?.toIntOrNull() ?: context.getString(
                    R.string.feed_auto_update_time_default
                ).toInt(),
                isRequireNonMeteredNetwork = preferences.getString(
                    context.getString(R.string.feed_auto_update_network_key),
                    context.getString(R.string.feed_auto_update_network_default)
                ) == context.getString(R.string.feed_auto_update_network_wifi)
            )
        }
    }
}
