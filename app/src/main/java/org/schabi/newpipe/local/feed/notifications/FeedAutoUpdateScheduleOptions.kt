package org.schabi.newpipe.local.feed.notifications

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import java.util.concurrent.TimeUnit

/**
 * Information for the Scheduler which auto-updates all feeds.
 * See [FeedAutoUpdateWorker]
 */
data class FeedAutoUpdateScheduleOptions(
    val interval: Long,
    val isRequireNonMeteredNetwork: Boolean
) {

    companion object {

        @JvmStatic
        fun from(context: Context): FeedAutoUpdateScheduleOptions {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            return FeedAutoUpdateScheduleOptions(
                interval = TimeUnit.SECONDS.toMillis(
                    preferences.getString(
                        context.getString(R.string.feed_auto_update_interval_key),
                        null
                    )?.toLongOrNull() ?: context.getString(
                        R.string.feed_auto_update_interval_default
                    ).toLong()
                ),
                isRequireNonMeteredNetwork = preferences.getString(
                    context.getString(R.string.feed_auto_update_network_key),
                    context.getString(R.string.feed_auto_update_network_default)
                ) == context.getString(R.string.feed_auto_update_network_wifi)
            )
        }
    }
}
