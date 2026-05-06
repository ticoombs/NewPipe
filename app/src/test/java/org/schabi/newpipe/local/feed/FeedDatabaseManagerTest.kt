package org.schabi.newpipe.local.feed

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Robolectric-driven manager-level tests pinning down behavioural fixes for the
 * "What's New" feed regressions documented in commits 9422a8010..c977eeb3d.
 *
 * Bugs covered:
 *   #3 removeOrphansOrOlderStreams must NOT unlink old feed entries (was the
 *      root cause of the "2-month bug").
 *   #4 First refresh after subscribing only links the single most-recent video
 *      to "What's New".
 *   #6 Live streams (null uploadDate) are treated as the most-recent item on
 *      first refresh.
 *   #7 Re-discovery via upsertAll preserves the original discoveryDate
 *      end-to-end (because OnConflictStrategy.IGNORE keeps the existing row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedDatabaseManagerTest {
    private val serviceId = ServiceList.YouTube.serviceId

    private lateinit var db: AppDatabase
    private lateinit var manager: FeedDatabaseManager
    private val ANCIENT: OffsetDateTime = OffsetDateTime.parse("2000-01-01T00:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = FeedDatabaseManager(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------------------
    // Bug #4: First refresh after subscribing must only insert ONE feed row,
    // pointing at the channel's latest video. The full back-catalogue still goes
    // into the streams table so the channel page renders, but What's New does
    // not get flooded.
    // ---------------------------------------------------------------------------
    @Test
    fun upsertAll_firstRefresh_linksOnlyLatestVideo() {
        seedSubscription(subId = 1, channelSuffix = "1")

        val items = listOf(
            videoItem(url = "v=old", title = "old-video", upload = "2024-01-01T00:00:00Z"),
            videoItem(url = "v=mid", title = "mid-video", upload = "2024-03-01T00:00:00Z"),
            videoItem(url = "v=new", title = "newest-video", upload = "2024-05-01T00:00:00Z")
        )

        manager.upsertAll(subscriptionId = 1, items = items, oldestAllowedDate = ANCIENT)

        // Feed contains exactly one entry – the latest by upload date.
        // Feed contains exactly one entry – the latest by upload date.
        val feedRows = db.feedDAO().getStreamsForSubscription(1, 10, false).blockingFirst()
        assertThat(feedRows).hasSize(1)
        assertThat(feedRows.single().title).isEqualTo("newest-video")
    }

    // ---------------------------------------------------------------------------
    // Bug #6: LIVE_STREAM items have null uploadDate. On first refresh they must
    // beat any dated VOD (treated as "happening now").
    // ---------------------------------------------------------------------------
    @Test
    fun upsertAll_firstRefresh_liveStreamWinsAsLatest() {
        seedSubscription(subId = 1, channelSuffix = "1")

        val items = listOf(
            videoItem(url = "v=vod", title = "recent-vod", upload = "2024-05-01T00:00:00Z"),
            liveItem(url = "v=live", title = "live-now")
        )

        manager.upsertAll(subscriptionId = 1, items = items, oldestAllowedDate = ANCIENT)

        val feedRows = db.feedDAO().getStreamsForSubscription(1, 10, false).blockingFirst()
        assertThat(feedRows).hasSize(1)
        assertThat(feedRows.single().title).isEqualTo("live-now")
    }

    // ---------------------------------------------------------------------------
    // Bug #4 (second refresh): once getUpdateInfo(subId) is non-null, every new
    // item is linked into the feed. Re-running the same items is a no-op for the
    // feed (IGNORE on PK).
    // ---------------------------------------------------------------------------
    @Test
    fun upsertAll_secondRefresh_linksAllItems() {
        seedSubscription(subId = 1, channelSuffix = "1")

        // First refresh – only newest is linked.
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=a", "video-a", "2024-01-01T00:00:00Z"),
                videoItem("v=b", "video-b", "2024-02-01T00:00:00Z")
            )
        )
        assertThat(db.feedDAO().getStreamsForSubscription(1, 10, false).blockingFirst())
            .hasSize(1)

        // Second refresh – channel published a new video AND we get the back-catalogue
        // again. Existing items are no-op (IGNORE), new item is linked.
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=a", "video-a", "2024-01-01T00:00:00Z"),
                videoItem("v=b", "video-b", "2024-02-01T00:00:00Z"),
                videoItem("v=c", "video-c-new", "2024-03-01T00:00:00Z")
            )
        )

        val feedRows = db.feedDAO().getStreamsForSubscription(1, 10, true).blockingFirst()
        assertThat(feedRows.map { it.title })
            .`as`("All items linked on subsequent refreshes (back-catalogue + new)")
            .containsExactlyInAnyOrder("video-a", "video-b", "video-c-new")
    }

    // ---------------------------------------------------------------------------
    // Bug #3: removeOrphansOrOlderStreams must not unlink any feed entries –
    // historically it called feedTable.unlinkStreamsOlderThan(13 weeks) which
    // re-surfaced old videos as "new" on the next refresh.
    // ---------------------------------------------------------------------------
    @Test
    fun removeOrphansOrOlderStreams_doesNotUnlinkOldFeedEntries() {
        seedSubscription(subId = 1, channelSuffix = "1")

        // Insert with a way-old upload date but recent discovery (simulating the
        // "I subscribed yesterday, channel posts old archive video" path).
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=old", "old-but-discovered", "2020-01-01T00:00:00Z")
            )
        )
        // Mark the subscription as already-refreshed so the next upsertAll links
        // every item (not just latest).
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=old", "old-but-discovered", "2020-01-01T00:00:00Z"),
                videoItem("v=now", "fresh-video", "2024-05-01T00:00:00Z")
            )
        )

        val before = db.feedDAO().getStreamsForSubscription(1, 10, false).blockingFirst()
        assertThat(before.map { it.title })
            .containsExactlyInAnyOrder("old-but-discovered", "fresh-video")

        // The fix: removeOrphansOrOlderStreams MUST NOT unlink anything.
        manager.removeOrphansOrOlderStreams()

        val after = db.feedDAO().getStreamsForSubscription(1, 10, false).blockingFirst()
        assertThat(after.map { it.title })
            .`as`("Old feed entries must not be unlinked – preserves history")
            .containsExactlyInAnyOrder("old-but-discovered", "fresh-video")
    }

    // ---------------------------------------------------------------------------
    // Bug #7 / #5 end-to-end: re-discovering an item via upsertAll must preserve
    // the original discovery_date so the item does NOT bubble back to the top
    // of "What's New" (which orders by discovery_date DESC).
    // ---------------------------------------------------------------------------
    @Test
    fun upsertAll_rediscovery_preservesOriginalDiscoveryDate() {
        seedSubscription(subId = 1, channelSuffix = "1")

        // First refresh links only the newest – manually link the older one too
        // so we can assert the ordering survives a rediscovery.
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=a", "video-a", "2024-01-01T00:00:00Z"),
                videoItem("v=b", "video-b", "2024-02-01T00:00:00Z")
            )
        )
        // Second refresh seeds video-a into the feed too.
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=a", "video-a", "2024-01-01T00:00:00Z"),
                videoItem("v=b", "video-b", "2024-02-01T00:00:00Z")
            )
        )

        val firstOrdering = manager.getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayedStreams = true,
            includePartiallyPlayedStreams = true,
            includeFutureStreams = true
        ).blockingGet()!!.map { it.stream.title }

        // Sleep a measurable amount so that, were rediscovery to overwrite the
        // discovery_date, the order would visibly change.
        Thread.sleep(50)

        // Third refresh: same items again. discovery_date must NOT be overwritten.
        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(
                videoItem("v=a", "video-a", "2024-01-01T00:00:00Z"),
                videoItem("v=b", "video-b", "2024-02-01T00:00:00Z")
            )
        )

        val secondOrdering = manager.getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayedStreams = true,
            includePartiallyPlayedStreams = true,
            includeFutureStreams = true
        ).blockingGet()!!.map { it.stream.title }

        assertThat(secondOrdering)
            .`as`("Rediscovery must not reorder the feed")
            .isEqualTo(firstOrdering)
    }

    // ---------------------------------------------------------------------------
    // Sanity: upsertAll stamps SubscriptionUpdateInfoEntity (used by first-refresh
    // detection). Without this, the first-refresh-only-latest logic short-circuits
    // forever.
    // ---------------------------------------------------------------------------
    @Test
    fun upsertAll_writesSubscriptionUpdateInfo() {
        seedSubscription(subId = 1, channelSuffix = "1")
        val before = db.feedDAO().getUpdateInfo(1)
        assertThat(before).isNull()

        manager.upsertAll(
            oldestAllowedDate = ANCIENT,
            subscriptionId = 1,
            items = listOf(videoItem("v=x", "x", "2024-01-01T00:00:00Z"))
        )

        val after = db.feedDAO().getUpdateInfo(1)
        assertThat(after).isNotNull
        assertThat(after!!.lastUpdated)
            .isCloseTo(OffsetDateTime.now(), within(5, ChronoUnit.MINUTES))
    }

    // -------------------- helpers --------------------

    private fun seedSubscription(subId: Long, channelSuffix: String) {
        val sub = SubscriptionEntity.from(
            ChannelInfo(
                serviceId,
                channelSuffix,
                "https://youtube.com/channel/$channelSuffix",
                "https://youtube.com/channel/$channelSuffix",
                "channel-$channelSuffix"
            )
        )
        sub.uid = subId
        db.subscriptionDAO().insertAll(listOf(sub))
    }

    private fun videoItem(url: String, title: String, upload: String): StreamInfoItem {
        val item = StreamInfoItem(serviceId, "https://youtube.com/watch?$url", title, StreamType.VIDEO_STREAM)
        item.uploaderName = "uploader"
        item.uploadDate = DateWrapper(OffsetDateTime.parse(upload), false)
        return item
    }

    private fun liveItem(url: String, title: String): StreamInfoItem {
        val item = StreamInfoItem(serviceId, "https://youtube.com/watch?$url", title, StreamType.LIVE_STREAM)
        item.uploaderName = "uploader"
        return item
    }

    private fun within(amount: Long, unit: ChronoUnit) = org.assertj.core.api.Assertions.within(amount, unit)
}
