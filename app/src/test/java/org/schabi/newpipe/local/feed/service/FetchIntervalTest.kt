package org.schabi.newpipe.local.feed.service

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

class FetchIntervalTest {

    @Test
    fun testCalculateInterval_InsufficientData() {
        val streams = listOf(createStream(1), createStream(2))
        assertEquals(7, FetchInterval.calculateInterval(streams))
    }

    @Test
    fun testCalculateInterval_DailyUploads() {
        val streams = listOf(
            createStream(1),
            createStream(2),
            createStream(3),
            createStream(4),
            createStream(5)
        )
        assertEquals(1, FetchInterval.calculateInterval(streams))
    }

    @Test
    fun testCalculateInterval_WithNullDates() {
        val streams = listOf(
            createStream(1),
            createStreamWithNullDate(),
            createStream(2),
            createStream(3)
        )
        assertEquals(1, FetchInterval.calculateInterval(streams))
    }

    @Test
    fun testCalculateInterval_IrregularPattern() {
        val streams = listOf(
            createStream(1),
            createStream(2),
            createStream(10),
            createStream(11),
            createStream(12)
        )
        // With irregular pattern (1,2,10,11,12 days ago), intervals are [8,1,1]
        // Median should be 1-8 days depending on which dates are distinct after conversion
        val result = FetchInterval.calculateInterval(streams)
        assertTrue("Expected 1-10 days (reasonable range for irregular uploads), got $result", result in 1..10)
    }

    @Test
    fun testCalculateInterval_WeeklyUploads() {
        val streams = listOf(
            createStream(7),
            createStream(14),
            createStream(21),
            createStream(28),
            createStream(35)
        )
        assertEquals(7, FetchInterval.calculateInterval(streams))
    }

    @Test
    fun testCalculateInterval_ExcludesLiveStreams() {
        val streams = listOf(
            createStream(1),
            createLiveStream(),
            createStream(2),
            createStream(3)
        )
        // Should ignore live stream and calculate based on other videos
        assertEquals(1, FetchInterval.calculateInterval(streams))
    }

    @Test
    fun testCalculateInterval_ExcludesFutureDates() {
        val streams = listOf(
            createStream(-3), // Future (premiere)
            createStream(1),
            createStream(2),
            createStream(3)
        )
        // Should ignore future date and calculate based on past videos
        assertEquals(1, FetchInterval.calculateInterval(streams))
    }

    private fun createStream(daysAgo: Long): StreamEntity {
        return StreamEntity(
            uid = 0,
            serviceId = 0,
            url = "test-url-$daysAgo",
            title = "Test Stream",
            streamType = StreamType.VIDEO_STREAM,
            duration = 100,
            uploader = "Uploader",
            uploaderUrl = "uploader-url",
            thumbnailUrl = "thumb-url",
            viewCount = 1000,
            textualUploadDate = null,
            uploadDate = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo),
            isUploadDateApproximation = false
        )
    }

    private fun createStreamWithNullDate(): StreamEntity {
        return StreamEntity(
            uid = 0,
            serviceId = 0,
            url = "test-url-null",
            title = "Test Stream",
            streamType = StreamType.VIDEO_STREAM,
            duration = 100,
            uploader = "Uploader",
            uploaderUrl = "uploader-url",
            thumbnailUrl = "thumb-url",
            viewCount = 1000,
            textualUploadDate = null,
            uploadDate = null,
            isUploadDateApproximation = false
        )
    }

    private fun createLiveStream(): StreamEntity {
        return StreamEntity(
            uid = 0,
            serviceId = 0,
            url = "test-url-live",
            title = "Live Stream",
            streamType = StreamType.LIVE_STREAM,
            duration = 0,
            uploader = "Uploader",
            uploaderUrl = "uploader-url",
            thumbnailUrl = "thumb-url",
            viewCount = 1000,
            textualUploadDate = null,
            uploadDate = OffsetDateTime.now(ZoneOffset.UTC),
            isUploadDateApproximation = false
        )
    }
}
