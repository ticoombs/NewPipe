package org.schabi.newpipe.database.feed.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionDAO
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedDAOSchedulerStateTest {
    private lateinit var db: AppDatabase
    private lateinit var feedDAO: FeedDAO
    private lateinit var streamDAO: StreamDAO
    private lateinit var subscriptionDAO: SubscriptionDAO

    private val serviceId = ServiceList.YouTube.serviceId

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedDAO = db.feedDAO()
        streamDAO = db.streamDAO()
        subscriptionDAO = db.subscriptionDAO()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getRecentUploadDates_returnsNewestFirst_excludesLive_excludesNull_respectsLimit() {
        seedSubscription(subId = 1, channelSuffix = "1")

        val oldestVod = insertStreamWithUploadDate(100, "oldest-vod", "2024-01-01T00:00:00Z")
        val middleVod = insertStreamWithUploadDate(101, "middle-vod", "2024-02-01T00:00:00Z")
        val newestVod = insertStreamWithUploadDate(102, "newest-vod", "2024-03-01T00:00:00Z")
        val live = insertStreamWithUploadDate(
            103,
            "live",
            "2024-04-01T00:00:00Z",
            StreamType.LIVE_STREAM
        )
        val nullUpload = insertStream(104, "null-upload", uploadDate = null)

        val discoveryDate = OffsetDateTime.parse("2024-05-01T00:00:00Z")
        feedDAO.insertAll(
            listOf(
                FeedEntity(oldestVod, 1, discoveryDate),
                FeedEntity(middleVod, 1, discoveryDate),
                FeedEntity(newestVod, 1, discoveryDate),
                FeedEntity(live, 1, discoveryDate),
                FeedEntity(nullUpload, 1, discoveryDate)
            )
        )

        val now = OffsetDateTime.parse("2024-06-01T00:00:00Z")
        val result = feedDAO.getRecentUploadDates(1, 3, now)

        assertThat(result)
            .containsExactly(
                OffsetDateTime.parse("2024-03-01T00:00:00Z"),
                OffsetDateTime.parse("2024-02-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z")
            )
    }

    @Test
    fun setSchedulerStateForSubscription_writesAllSixFields() {
        seedSubscription(subId = 1, channelSuffix = "1")
        feedDAO.upsertUpdateInfo(
            SubscriptionUpdateInfoEntity(
                subscriptionId = 1,
                lastUpdated = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                nextUpdate = OffsetDateTime.parse("2024-01-02T00:00:00Z")
            )
        )

        val nextUpdate = OffsetDateTime.parse("2024-07-01T12:00:00Z")
        feedDAO.setSchedulerStateForSubscription(1, 7, nextUpdate, 1.5f, 2, 3, 0.8f)

        val updateInfo = feedDAO.getUpdateInfo(1)

        assertThat(updateInfo).isNotNull
        assertThat(updateInfo!!.fetchInterval).isEqualTo(7)
        assertThat(updateInfo.nextUpdate).isEqualTo(nextUpdate)
        assertThat(updateInfo.backoffMultiplier).isEqualTo(1.5f)
        assertThat(updateInfo.detectedPattern).isEqualTo(2)
        assertThat(updateInfo.detectedWeekday).isEqualTo(3)
        assertThat(updateInfo.confidence).isEqualTo(0.8f)
    }

    @Test
    fun setSchedulerStateForSubscription_persistsNullDetectedWeekday() {
        seedSubscription(subId = 1, channelSuffix = "1")
        feedDAO.upsertUpdateInfo(
            SubscriptionUpdateInfoEntity(
                subscriptionId = 1,
                lastUpdated = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                nextUpdate = OffsetDateTime.parse("2024-01-02T00:00:00Z")
            )
        )

        val nextUpdate = OffsetDateTime.parse("2024-08-01T12:00:00Z")
        feedDAO.setSchedulerStateForSubscription(1, 7, nextUpdate, 1.0f, 0, null, 0.0f)

        val updateInfo = feedDAO.getUpdateInfo(1)

        assertThat(updateInfo).isNotNull
        assertThat(updateInfo!!.detectedWeekday).isNull()
        assertThat(updateInfo.fetchInterval).isEqualTo(7)
        assertThat(updateInfo.nextUpdate).isEqualTo(nextUpdate)
        assertThat(updateInfo.backoffMultiplier).isEqualTo(1.0f)
        assertThat(updateInfo.detectedPattern).isEqualTo(0)
        assertThat(updateInfo.confidence).isEqualTo(0.0f)
    }

    private fun seedSubscription(subId: Long, channelSuffix: String) {
        val subscription = SubscriptionEntity.from(
            ChannelInfo(
                serviceId,
                channelSuffix,
                "https://youtube.com/channel/$channelSuffix",
                "https://youtube.com/channel/$channelSuffix",
                "channel-$channelSuffix"
            )
        )
        subscription.uid = subId
        subscriptionDAO.insertAll(listOf(subscription))
    }

    private fun insertStream(
        uid: Long,
        title: String,
        uploadDate: OffsetDateTime?,
        type: StreamType = StreamType.VIDEO_STREAM
    ): Long {
        val entity = StreamEntity(
            uid = uid,
            serviceId = serviceId,
            url = "https://youtube.com/watch?v=$uid",
            title = title,
            streamType = type,
            duration = 1000,
            uploader = "uploader-$uid",
            uploaderUrl = "https://youtube.com/channel/x",
            thumbnailUrl = "https://i.ytimg.com/vi/$uid/hqdefault.jpg",
            viewCount = 100,
            textualUploadDate = uploadDate?.toString(),
            uploadDate = uploadDate
        )
        streamDAO.insertAll(listOf(entity))
        return uid
    }

    private fun insertStreamWithUploadDate(
        uid: Long,
        title: String,
        uploadDateIso: String,
        type: StreamType = StreamType.VIDEO_STREAM
    ): Long = insertStream(uid, title, OffsetDateTime.parse(uploadDateIso), type)
}
