package org.schabi.newpipe.local.feed

import androidx.annotation.StringRes
import org.schabi.newpipe.local.feed.item.StreamItem
import java.time.OffsetDateTime

sealed class FeedState {
    data class ProgressState(
        val currentProgress: Int = -1,
        val maxProgress: Int = -1,
        @StringRes val progressMessage: Int = 0,
        val skippedCount: Int = 0,
        val totalSubscriptions: Int = 0
    ) : FeedState()

    data class LoadedState(
        val items: List<StreamItem>,
        val oldestUpdate: OffsetDateTime?,
        val notLoadedCount: Long,
        val itemsErrors: List<Throwable>,
        val smartSchedulingStats: SmartSchedulingStats? = null
    ) : FeedState()

    data class ErrorState(
        val error: Throwable? = null
    ) : FeedState()
}

data class SmartSchedulingStats(
    val checked: Int, // Subscriptions checked
    val skipped: Int, // Subscriptions skipped
    val total: Int, // Total subscriptions
    val newVideos: Int // Total new videos found
)
