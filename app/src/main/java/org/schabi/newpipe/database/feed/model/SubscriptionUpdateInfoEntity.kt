/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.database.feed.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.OffsetDateTime
import org.schabi.newpipe.database.subscription.SubscriptionEntity

@Entity(
    tableName = SubscriptionUpdateInfoEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = [SubscriptionEntity.SUBSCRIPTION_UID],
            childColumns = [SubscriptionUpdateInfoEntity.SUBSCRIPTION_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true
        )
    ]
)
data class SubscriptionUpdateInfoEntity(
    @PrimaryKey
    @ColumnInfo(name = SUBSCRIPTION_ID)
    val subscriptionId: Long,

    @ColumnInfo(name = LAST_UPDATED)
    val lastUpdated: OffsetDateTime?,

    @ColumnInfo(name = NEXT_UPDATE)
    val nextUpdate: OffsetDateTime?,

    @ColumnInfo(name = FETCH_INTERVAL)
    val fetchInterval: Int = DEFAULT_FETCH_INTERVAL,

    @ColumnInfo(name = UPDATE_STRATEGY)
    val updateStrategy: Int = UPDATE_STRATEGY_SMART,

    @ColumnInfo(name = BACKOFF_MULTIPLIER)
    val backoffMultiplier: Float = DEFAULT_BACKOFF_MULTIPLIER,

    @ColumnInfo(name = DETECTED_PATTERN)
    val detectedPattern: Int = PATTERN_NEW,

    @ColumnInfo(name = DETECTED_WEEKDAY)
    val detectedWeekday: Int? = null,

    @ColumnInfo(name = CONFIDENCE)
    val confidence: Float = DEFAULT_CONFIDENCE
) {
    companion object {
        const val TABLE_NAME = "subscription_update_info"
        const val SUBSCRIPTION_ID = "subscription_id"
        const val LAST_UPDATED = "last_updated"
        const val NEXT_UPDATE = "next_update"
        const val FETCH_INTERVAL = "fetch_interval"
        const val UPDATE_STRATEGY = "update_strategy"
        const val BACKOFF_MULTIPLIER = "backoff_multiplier"
        const val DETECTED_PATTERN = "detected_pattern"
        const val DETECTED_WEEKDAY = "detected_weekday"
        const val CONFIDENCE = "confidence"

        // Default interval in days
        const val DEFAULT_FETCH_INTERVAL = 7

        // Update strategies
        const val UPDATE_STRATEGY_SMART = 0
        const val UPDATE_STRATEGY_ALWAYS = 1

        // Backoff/pattern defaults
        const val DEFAULT_BACKOFF_MULTIPLIER = 1.0f
        const val DEFAULT_CONFIDENCE = 0.0f

        // Detected upload patterns
        const val PATTERN_NEW = 0
        const val PATTERN_WEEKDAY_PERIODIC = 1
        const val PATTERN_ACTIVE_EWMA = 2
        const val PATTERN_DORMANT = 3
    }
}
