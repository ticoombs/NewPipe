package org.schabi.newpipe.database.feed.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import java.time.OffsetDateTime
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity

@Dao
abstract class FeedDAO {
    @Query("DELETE FROM feed")
    abstract fun deleteAll(): Int

    @Query(
        """
        SELECT s.*, sst.progress_time
        FROM streams s

        LEFT JOIN stream_state sst
        ON s.uid = sst.stream_id
        
        LEFT JOIN stream_history sh
        ON s.uid = sh.stream_id
        
        INNER JOIN feed f
        ON s.uid = f.stream_id

        LEFT JOIN feed_group_subscription_join fgs
        ON (
            :groupId <> ${FeedGroupEntity.GROUP_ALL_ID}
            AND fgs.subscription_id = f.subscription_id
        )

        WHERE (
            :groupId = ${FeedGroupEntity.GROUP_ALL_ID}
            OR fgs.group_id = :groupId
        )
        AND (
            :includePlayed
            OR sh.stream_id IS NULL
            OR sst.stream_id IS NULL
            OR sst.progress_time < s.duration * 1000 - ${StreamStateEntity.PLAYBACK_FINISHED_END_MILLISECONDS}
            OR sst.progress_time < s.duration * 1000 * 3 / 4
            OR s.stream_type = 'LIVE_STREAM'
            OR s.stream_type = 'AUDIO_LIVE_STREAM'
        )
        AND (
            :includePartiallyPlayed
            OR sh.stream_id IS NULL
            OR sst.stream_id IS NULL
            OR (sst.progress_time <= ${StreamStateEntity.PLAYBACK_SAVE_THRESHOLD_START_MILLISECONDS}
            AND sst.progress_time <= s.duration * 1000 / 4)
            OR (sst.progress_time >= s.duration * 1000 - ${StreamStateEntity.PLAYBACK_FINISHED_END_MILLISECONDS}
            AND sst.progress_time >= s.duration * 1000 * 3 / 4)
        )
        AND (
            :uploadDateBefore IS NULL
            OR s.upload_date IS NULL
            OR s.upload_date < :uploadDateBefore
        )

        ORDER BY 
            CASE :orderByDiscoveryDate
                WHEN 1 THEN f.discovery_date IS NULL
                ELSE s.upload_date IS NULL
            END DESC,
            CASE :orderByDiscoveryDate
                WHEN 1 THEN f.discovery_date
                ELSE s.upload_date
            END DESC,
            s.uploader ASC
        LIMIT 500
        """
    )
    abstract fun getStreams(
        groupId: Long,
        includePlayed: Boolean,
        includePartiallyPlayed: Boolean,
        uploadDateBefore: OffsetDateTime?,
        orderByDiscoveryDate: Boolean
    ): Maybe<List<StreamWithState>>

    @Query(
        """
        DELETE FROM feed
        WHERE feed.stream_id IN (SELECT uid from (
              SELECT s.uid,
              (SELECT MAX(upload_date)
                    FROM streams s1
                    INNER JOIN feed f1
                    ON s1.uid = f1.stream_id
                    WHERE f1.subscription_id = f.subscription_id) max_upload_date
              FROM streams s
              INNER JOIN feed f
              ON s.uid = f.stream_id
        
              WHERE s.upload_date < :offsetDateTime
              AND   s.upload_date <> max_upload_date))
        """
    )
    abstract fun unlinkStreamsOlderThan(offsetDateTime: OffsetDateTime)

    @Query(
        """
        DELETE FROM feed
        
        WHERE feed.subscription_id = :subscriptionId

        AND feed.stream_id IN (
            SELECT s.uid FROM streams s

            INNER JOIN feed f
            ON s.uid = f.stream_id

            WHERE s.stream_type = "LIVE_STREAM" OR s.stream_type = "AUDIO_LIVE_STREAM"
        )
        """
    )
    abstract fun unlinkOldLivestreams(subscriptionId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(feedEntity: FeedEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertAll(entities: List<FeedEntity>): List<Long>

    @Query(
        """
        SELECT MIN(sui.last_updated) FROM subscription_update_info sui

        INNER JOIN feed_group_subscription_join fgs
        ON fgs.subscription_id = sui.subscription_id AND fgs.group_id = :groupId
        """
    )
    abstract fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime?>>

    @Query("SELECT MIN(last_updated) FROM subscription_update_info")
    abstract fun oldestSubscriptionUpdateFromAll(): Flowable<List<OffsetDateTime?>>

    @Query("SELECT COUNT(*) FROM subscription_update_info WHERE last_updated IS NULL")
    abstract fun notLoadedCount(): Flowable<Long>

    @Query(
        """
        SELECT COUNT(*) FROM subscriptions s
        
        INNER JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id AND fgs.group_id = :groupId

        LEFT JOIN subscription_update_info sui
        ON s.uid = sui.subscription_id 

        WHERE sui.last_updated IS NULL
        """
    )
    abstract fun notLoadedCountForGroup(groupId: Long): Flowable<Long>

    @Query(
        """
        SELECT s.* FROM subscriptions s

        LEFT JOIN subscription_update_info sui
        ON s.uid = sui.subscription_id 

        WHERE sui.last_updated IS NULL OR sui.last_updated < :outdatedThreshold
        """
    )
    abstract fun getAllOutdated(outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>

    @Query(
        """
        SELECT s.* FROM subscriptions s

        INNER JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id AND fgs.group_id = :groupId

        LEFT JOIN subscription_update_info sui
        ON s.uid = sui.subscription_id

        WHERE sui.last_updated IS NULL OR sui.last_updated < :outdatedThreshold
        """
    )
    abstract fun getAllOutdatedForGroup(groupId: Long, outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>

    @Query(
        """
        SELECT s.* FROM subscriptions s

        LEFT JOIN subscription_update_info sui
        ON s.uid = sui.subscription_id

        WHERE 
            (sui.last_updated IS NULL OR sui.last_updated < :outdatedThreshold)
            AND s.notification_mode = :notificationMode
        """
    )
    abstract fun getOutdatedWithNotificationMode(
        outdatedThreshold: OffsetDateTime,
        @NotificationMode notificationMode: Int
    ): Flowable<List<SubscriptionEntity>>

    @Query(
        """
        SELECT s.* FROM subscriptions s
        LEFT JOIN subscription_update_info sui ON s.uid = sui.subscription_id
        WHERE (sui.next_update IS NULL OR sui.next_update <= :windowUpper)
        AND (sui.last_updated IS NULL OR sui.last_updated < :outdatedThreshold)
        ORDER BY s.name COLLATE NOCASE ASC
    """
    )
    abstract fun getAllDueForUpdate(windowUpper: OffsetDateTime, outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>

    @Query(
        """
        SELECT s.* FROM subscriptions s
        INNER JOIN feed_group_subscription_join fgs ON s.uid = fgs.subscription_id
        LEFT JOIN subscription_update_info sui ON s.uid = sui.subscription_id
        WHERE fgs.group_id = :groupId 
        AND (sui.next_update IS NULL OR sui.next_update <= :windowUpper)
        AND (sui.last_updated IS NULL OR sui.last_updated < :outdatedThreshold)
        ORDER BY s.name COLLATE NOCASE ASC
    """
    )
    abstract fun getAllDueForUpdateByGroup(groupId: Long, windowUpper: OffsetDateTime, outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertUpdateInfo(updateInfo: SubscriptionUpdateInfoEntity): Long

    @Query(
        """
        UPDATE subscription_update_info 
        SET fetch_interval = :interval, next_update = :nextUpdate
        WHERE subscription_id = :subscriptionId
    """
    )
    abstract fun setFetchIntervalForSubscription(subscriptionId: Long, interval: Int, nextUpdate: OffsetDateTime)

    @Query("SELECT * FROM subscription_update_info WHERE subscription_id = :subscriptionId")
    abstract fun getUpdateInfo(subscriptionId: Long): SubscriptionUpdateInfoEntity?

    @Query(
        """
        SELECT s.* FROM streams s
        INNER JOIN feed f ON s.uid = f.stream_id
        WHERE f.subscription_id = :subscriptionId
        ORDER BY 
            CASE :orderByDiscoveryDate
                WHEN 1 THEN f.discovery_date
                ELSE s.upload_date
            END DESC
        LIMIT :limit
        """
    )
    abstract fun getStreamsForSubscription(
        subscriptionId: Long,
        limit: Int,
        orderByDiscoveryDate: Boolean
    ): Flowable<List<StreamEntity>>
}
