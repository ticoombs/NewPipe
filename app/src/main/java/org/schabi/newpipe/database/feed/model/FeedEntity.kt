package org.schabi.newpipe.database.feed.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.OffsetDateTime
import org.schabi.newpipe.database.feed.model.FeedEntity.Companion.FEED_TABLE
import org.schabi.newpipe.database.feed.model.FeedEntity.Companion.STREAM_ID
import org.schabi.newpipe.database.feed.model.FeedEntity.Companion.SUBSCRIPTION_ID
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity

@Entity(
    tableName = FEED_TABLE,
    primaryKeys = [STREAM_ID, SUBSCRIPTION_ID],
    indices = [Index(SUBSCRIPTION_ID)],
    foreignKeys = [
        ForeignKey(
            entity = StreamEntity::class,
            parentColumns = ["uid"],
            childColumns = [STREAM_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true
        ),
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = [SubscriptionEntity.SUBSCRIPTION_UID],
            childColumns = [SUBSCRIPTION_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true
        )
    ]
)
data class FeedEntity(
    @ColumnInfo(name = STREAM_ID)
    var streamId: Long,

    @ColumnInfo(name = SUBSCRIPTION_ID)
    var subscriptionId: Long,

    @ColumnInfo(name = DISCOVERY_DATE)
    var discoveryDate: OffsetDateTime? = null
) {

    companion object {
        const val FEED_TABLE = "feed"

        const val STREAM_ID = "stream_id"
        const val SUBSCRIPTION_ID = "subscription_id"
        const val DISCOVERY_DATE = "discovery_date"
    }

    constructor(streamId: Long, subscriptionId: Long) : this(streamId, subscriptionId, OffsetDateTime.now())
}
