package org.schabi.newpipe.local.feed

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.FeedGroupIcon

class FeedDatabaseManager(context: Context) {
    private val database = NewPipeDatabase.getInstance(context)
    private val feedTable = database.feedDAO()
    private val feedGroupTable = database.feedGroupDAO()
    private val streamTable = database.streamDAO()
    private val context = context

    companion object {
        /**
         * Only items that are newer than this will be saved.
         */
        val FEED_OLDEST_ALLOWED_DATE: OffsetDateTime = LocalDate.now().minusWeeks(13)
            .atStartOfDay().atOffset(ZoneOffset.UTC)
    }

    fun groups() = feedGroupTable.getAll()

    fun database() = database

    fun getStreams(
        groupId: Long,
        includePlayedStreams: Boolean,
        includePartiallyPlayedStreams: Boolean,
        includeFutureStreams: Boolean
    ): Maybe<List<StreamWithState>> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val orderByValue = prefs.getString(
            context.getString(R.string.feed_order_by_key),
            context.getString(R.string.feed_order_by_default)
        )
        val orderByDiscoveryDate = orderByValue == "discovery_date"

        return feedTable.getStreams(
            groupId,
            includePlayedStreams,
            includePartiallyPlayedStreams,
            if (includeFutureStreams) null else OffsetDateTime.now(),
            orderByDiscoveryDate
        )
    }

    fun outdatedSubscriptions(outdatedThreshold: OffsetDateTime) = feedTable.getAllOutdated(outdatedThreshold)

    fun outdatedSubscriptionsWithNotificationMode(
        outdatedThreshold: OffsetDateTime,
        @NotificationMode notificationMode: Int
    ) = feedTable.getOutdatedWithNotificationMode(outdatedThreshold, notificationMode)

    fun notLoadedCount(groupId: Long = FeedGroupEntity.GROUP_ALL_ID): Flowable<Long> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.notLoadedCount()
            else -> feedTable.notLoadedCountForGroup(groupId)
        }
    }

    fun outdatedSubscriptionsForGroup(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        outdatedThreshold: OffsetDateTime
    ) = feedTable.getAllOutdatedForGroup(groupId, outdatedThreshold)

    fun markAsOutdated(subscriptionId: Long) = feedTable
        .upsertUpdateInfo(SubscriptionUpdateInfoEntity(subscriptionId, null, null, 7, 0))

    fun doesStreamExist(stream: StreamInfoItem): Boolean {
        return streamTable.exists(stream.serviceId, stream.url)
    }

    fun upsertAll(
        subscriptionId: Long,
        items: List<StreamInfoItem>,
        oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE
    ) {
        upsertStreamsToFeed(subscriptionId, items, oldestAllowedDate)

        feedTable.upsertUpdateInfo(
            SubscriptionUpdateInfoEntity(
                subscriptionId,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                7,
                0
            )
        )
    }

    fun upsertStreamsToFeed(
        subscriptionId: Long,
        items: List<StreamInfoItem>,
        oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE
    ) {
        val itemsToInsert = items.mapNotNull { stream ->
            val uploadDate = stream.uploadDate

            when {
                uploadDate == null && stream.streamType == StreamType.LIVE_STREAM -> stream
                uploadDate != null && uploadDate.offsetDateTime() >= oldestAllowedDate -> stream
                else -> null
            }
        }

        feedTable.unlinkOldLivestreams(subscriptionId)

        if (itemsToInsert.isNotEmpty()) {
            val now = OffsetDateTime.now()
            val streamEntities = itemsToInsert.map { StreamEntity(it) }
            val streamIds = streamTable.upsertAll(streamEntities)
            val feedEntities = itemsToInsert.zip(streamIds).map { (item, streamId) ->
                // Use min(now, uploadDate) so a video that was uploaded weeks/months
                // ago but only just surfaced in the extractor's response (e.g. due to
                // YouTube re-pinning, unlisting/relisting, or pagination) does NOT
                // bubble to the top of the discovery_date-ordered feed. Genuinely
                // new uploads still get a near-now discovery_date because their
                // uploadDate is close to now. Live streams (null uploadDate) fall
                // back to now() so they do appear at the top.
                val uploadDate = item.uploadDate?.offsetDateTime()
                val discoveryDate =
                    if (uploadDate != null && uploadDate.isBefore(now)) uploadDate else now
                FeedEntity(streamId, subscriptionId, discoveryDate)
            }

            feedTable.insertAll(feedEntities)
        }
    }

    /**
     * Remove streams from the database which are not linked / used by any table.
     *
     * Previously this also unlinked feed entries older than [FEED_OLDEST_ALLOWED_DATE],
     * but that caused the What's New feed to lose history: users could no longer scroll
     * back through older videos from their subscriptions. The date cap is now only applied
     * on initial import in [upsertAll] (to avoid flooding the feed when subscribing to a
     * new channel) and in FeedLoadManager.filterNewStreams; once an entry is in the feed
     * it stays there indefinitely.
     */
    fun removeOrphansOrOlderStreams() {
        streamTable.deleteOrphans()
    }

    fun clear() {
        feedTable.deleteAll()
        val deletedOrphans = streamTable.deleteOrphans()
        if (DEBUG) {
            Log.d(
                this::class.java.simpleName,
                "clear() → streamTable.deleteOrphans() → $deletedOrphans"
            )
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Feed Groups
    // /////////////////////////////////////////////////////////////////////////

    fun subscriptionIdsForGroup(groupId: Long): Flowable<List<Long>> {
        return feedGroupTable.getSubscriptionIdsFor(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>): Completable {
        return Completable
            .fromCallable { feedGroupTable.updateSubscriptionsForGroup(groupId, subscriptionIds) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroup(name: String, icon: FeedGroupIcon): Maybe<Long> {
        return Maybe.fromCallable { feedGroupTable.insert(FeedGroupEntity(0, name, icon)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun getGroup(groupId: Long): Maybe<FeedGroupEntity> {
        return feedGroupTable.getGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroup(feedGroupEntity: FeedGroupEntity): Completable {
        return Completable.fromCallable { feedGroupTable.update(feedGroupEntity) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun deleteGroup(groupId: Long): Completable {
        return Completable.fromCallable { feedGroupTable.delete(groupId) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupsOrder(groupIdList: List<Long>): Completable {
        var index = 0L
        val orderMap = groupIdList.associateBy({ it }, { index++ })

        return Completable.fromCallable { feedGroupTable.updateOrder(orderMap) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime?>> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.oldestSubscriptionUpdateFromAll()
            else -> feedTable.oldestSubscriptionUpdate(groupId)
        }
    }
}
