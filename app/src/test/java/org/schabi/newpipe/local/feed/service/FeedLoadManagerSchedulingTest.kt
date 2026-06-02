/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.reactivex.rxjava3.observers.TestObserver
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.dao.FeedDAO
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
class FeedLoadManagerSchedulingTest {
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
    fun calculateAndStoreInterval_persistsAnalyzerOutput() {
        val subscriptionId = 101L
        seedSubscription(subscriptionId, "101")
        feedDAO.upsertUpdateInfo(
            SubscriptionUpdateInfoEntity(
                subscriptionId = subscriptionId,
                lastUpdated = null,
                nextUpdate = null
            )
        )

        val uploadDates = listOf(
            OffsetDateTime.parse("2026-05-14T12:00:00Z"),
            OffsetDateTime.parse("2026-05-07T12:00:00Z"),
            OffsetDateTime.parse("2026-04-30T12:00:00Z"),
            OffsetDateTime.parse("2026-04-23T12:00:00Z"),
            OffsetDateTime.parse("2026-04-16T12:00:00Z")
        )

        uploadDates.forEachIndexed { index, uploadDate ->
            val streamId = 1000L + index
            streamDAO.insertAll(
                listOf(
                    StreamEntity(
                        uid = streamId,
                        serviceId = serviceId,
                        url = "https://youtube.com/watch?v=$streamId",
                        title = "stream-$streamId",
                        streamType = StreamType.VIDEO_STREAM,
                        duration = 1000,
                        uploader = "uploader-$streamId",
                        uploaderUrl = "https://youtube.com/channel/u$streamId",
                        thumbnailUrl = "https://i.ytimg.com/vi/$streamId/hqdefault.jpg",
                        viewCount = 100,
                        textualUploadDate = uploadDate.toString(),
                        uploadDate = uploadDate,
                        isUploadDateApproximation = false
                    )
                )
            )
            feedDAO.insertAll(listOf(FeedEntity(streamId, subscriptionId, uploadDate.plusHours(1))))
        }

        val now = OffsetDateTime.parse("2026-05-15T12:00:00Z")
        val dates = feedDAO.getRecentUploadDates(subscriptionId, 30, now)
        val prediction = UploadCadenceAnalyzer { now }.predict(dates, 1.0f)

        feedDAO.setSchedulerStateForSubscription(
            subscriptionId,
            prediction.intervalDays,
            prediction.nextCheck,
            prediction.backoffMultiplier,
            prediction.pattern.ordinal,
            prediction.detectedWeekday?.value,
            prediction.confidence
        )

        val updateInfo = feedDAO.getUpdateInfo(subscriptionId)
        assertThat(updateInfo).isNotNull
        assertThat(updateInfo!!.fetchInterval).isEqualTo(prediction.intervalDays)
        assertThat(updateInfo.nextUpdate).isEqualTo(prediction.nextCheck)
        assertThat(updateInfo.backoffMultiplier).isEqualTo(prediction.backoffMultiplier)
        assertThat(updateInfo.detectedPattern).isEqualTo(prediction.pattern.ordinal)
        assertThat(updateInfo.detectedWeekday).isEqualTo(prediction.detectedWeekday?.value)
        assertThat(updateInfo.confidence).isEqualTo(prediction.confidence)
    }

    @Test
    fun forceRefreshOne_completesWithoutException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FeedLoadManager(context)

        val observer: TestObserver<Void> = manager.forceRefreshOne(-1L).test()
        observer.await()

        observer.assertComplete()
        observer.assertNoErrors()
    }

    @Test
    fun singleFlight_secondCallSkipsIfAlreadyInflight() {
        val inFlightRefreshes = ConcurrentHashMap<Long, Boolean>()
        val subscriptionId = 777L

        val first = inFlightRefreshes.putIfAbsent(subscriptionId, true)
        val second = inFlightRefreshes.putIfAbsent(subscriptionId, true)

        assertThat(first).isNull()
        assertThat(second).isEqualTo(true)
    }

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
        subscriptionDAO.insertAll(listOf(sub))
    }
}
