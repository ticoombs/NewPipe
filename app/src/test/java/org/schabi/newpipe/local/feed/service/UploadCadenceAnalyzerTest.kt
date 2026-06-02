/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.service

import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.local.feed.service.UploadCadenceAnalyzer.Pattern

class UploadCadenceAnalyzerTest {
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun predict_lessThanThreeSamples_returnsNewStateAnd24h() {
        listOf(
            emptyList(),
            listOf(NOW.minusDays(1)),
            listOf(NOW.minusDays(1), NOW.minusDays(2))
        ).forEach { dates ->
            val prediction = analyzer().predict(dates)

            assertEquals(Pattern.NEW, prediction.pattern)
            assertEquals(1, prediction.intervalDays)
            assertNextCheckApproximately(NOW.plusDays(1), prediction.nextCheck)
        }
    }

    @Test
    fun predict_fiveTuesdayUploads_predictsNextTuesday() {
        val dates = listOf(
            date("2026-05-05T18:00:00Z"),
            date("2026-04-28T18:00:00Z"),
            date("2026-04-21T18:00:00Z"),
            date("2026-04-14T18:00:00Z"),
            date("2026-04-07T18:00:00Z")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.WEEKDAY_PERIODIC, prediction.pattern)
        assertEquals(DayOfWeek.TUESDAY, prediction.detectedWeekday)
        assertEquals(DayOfWeek.TUESDAY, prediction.nextCheck.utcDayOfWeek())
        assertEquals(date("2026-05-19T18:00:00Z"), prediction.nextCheck)
        assertEquals(4, prediction.intervalDays)
        assertTrue(prediction.confidence >= 0.7f)
    }

    @Test
    fun predict_tueAndThuPattern_predictsNextMatching() {
        val dates = listOf(
            date("2026-05-12T12:00:00Z"),
            date("2026-05-07T12:00:00Z"),
            date("2026-05-05T12:00:00Z"),
            date("2026-04-30T12:00:00Z"),
            date("2026-04-28T12:00:00Z"),
            date("2026-04-23T12:00:00Z"),
            date("2026-04-21T12:00:00Z"),
            date("2026-04-16T12:00:00Z")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.WEEKDAY_PERIODIC, prediction.pattern)
        assertEquals(DayOfWeek.TUESDAY, prediction.detectedWeekday)
        assertTrue(
            prediction.nextCheck.utcDayOfWeek() in setOf(
                DayOfWeek.TUESDAY,
                DayOfWeek.THURSDAY
            )
        )
        assertEquals(date("2026-05-19T12:00:00Z"), prediction.nextCheck)
        assertEquals(4, prediction.intervalDays)
    }

    @Test
    fun predict_noisyWeekday_fallsToEwma() {
        val dates = listOf(
            date("2026-05-14T12:00:00Z"),
            date("2026-05-12T12:00:00Z"),
            date("2026-05-09T12:00:00Z"),
            date("2026-05-06T12:00:00Z"),
            date("2026-05-03T12:00:00Z"),
            date("2026-05-01T12:00:00Z"),
            date("2026-04-28T12:00:00Z"),
            date("2026-04-25T12:00:00Z")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.ACTIVE_EWMA, prediction.pattern)
        assertEquals(3, prediction.intervalDays)
    }

    @Test
    fun predict_ewmaPinnedValue_intervals_7_7_7_14_7() {
        val dates = listOf(
            date("2026-05-14T00:30:00+14:00"),
            date("2026-05-07T12:00:00Z"),
            date("2026-04-30T23:30:00-12:00"),
            date("2026-04-23T00:30:00+14:00"),
            date("2026-04-09T12:00:00Z"),
            date("2026-04-02T23:30:00-12:00")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.ACTIVE_EWMA, prediction.pattern)
        assertEquals(8, prediction.intervalDays)
        assertEquals(NOW.plusDays(8), prediction.nextCheck)
    }

    @Test
    fun predict_dormantWhenLatestUploadOld() {
        val prediction = analyzer().predict(staleWeeklyDates(), prevBackoffMultiplier = 1.0f)

        assertEquals(Pattern.DORMANT, prediction.pattern)
        assertEquals(1.5f, prediction.backoffMultiplier, FLOAT_DELTA)
        assertEquals(11, prediction.intervalDays)
        assertEquals(NOW.plusDays(11), prediction.nextCheck)
        assertEquals(0.3f, prediction.confidence, FLOAT_DELTA)
    }

    @Test
    fun predict_dormantEscalates_1_to_1_5_to_2_25() {
        val dates = staleWeeklyDates()

        val first = analyzer().predict(dates, prevBackoffMultiplier = 1.0f)
        val second = analyzer().predict(dates, prevBackoffMultiplier = first.backoffMultiplier)
        val third = analyzer().predict(dates, prevBackoffMultiplier = second.backoffMultiplier)

        assertEquals(1.5f, first.backoffMultiplier, FLOAT_DELTA)
        assertEquals(2.25f, second.backoffMultiplier, FLOAT_DELTA)
        assertEquals(3.375f, third.backoffMultiplier, FLOAT_DELTA)
        assertEquals(11, first.intervalDays)
        assertEquals(14, second.intervalDays)
        assertEquals(14, third.intervalDays)
    }

    @Test
    fun predict_dormantResetsOnNewUpload() {
        val dates = listOf(
            NOW.minusDays(1),
            NOW.minusDays(60),
            NOW.minusDays(67),
            NOW.minusDays(74)
        )

        val prediction = analyzer().predict(dates, prevBackoffMultiplier = 2.25f)

        assertTrue(prediction.pattern != Pattern.DORMANT)
        assertEquals(Pattern.ACTIVE_EWMA, prediction.pattern)
        assertEquals(1.0f, prediction.backoffMultiplier, FLOAT_DELTA)
        assertEquals(14, prediction.intervalDays)
    }

    @Test
    fun predict_clampedToMaxFourteenDays() {
        val dates = listOf(
            NOW.minusDays(1),
            NOW.minusDays(31),
            NOW.minusDays(61),
            NOW.minusDays(91),
            NOW.minusDays(121)
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.ACTIVE_EWMA, prediction.pattern)
        assertEquals(14, prediction.intervalDays)
    }

    @Test
    fun predict_clampedToMinOneDay() {
        val dates = listOf(
            date("2026-05-14T08:00:00Z"),
            date("2026-05-14T12:00:00Z"),
            date("2026-05-14T18:00:00Z"),
            date("2026-05-14T22:00:00Z")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.ACTIVE_EWMA, prediction.pattern)
        assertEquals(1, prediction.intervalDays)
        assertEquals(NOW.plusDays(1), prediction.nextCheck)
    }

    @Test
    fun predict_weekdayTzBoundary_localVsUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val newYorkZone = ZoneId.of("America/New_York")
        val dates = listOf(
            date("2026-05-11T03:30:00Z"),
            date("2026-05-04T03:30:00Z"),
            date("2026-04-27T03:30:00Z"),
            date("2026-04-20T03:30:00Z"),
            date("2026-04-13T03:30:00Z")
        )

        val prediction = analyzer().predict(dates)

        assertEquals(Pattern.WEEKDAY_PERIODIC, prediction.pattern)
        assertEquals(DayOfWeek.SUNDAY, prediction.detectedWeekday)
        assertEquals(DayOfWeek.SUNDAY, prediction.nextCheck.atZoneSameInstant(newYorkZone).dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, prediction.nextCheck.utcDayOfWeek())
    }

    @Test
    fun predict_emptyList_returnsNewState() {
        val prediction = analyzer().predict(emptyList())

        assertEquals(Pattern.NEW, prediction.pattern)
        assertEquals(1, prediction.intervalDays)
        assertEquals(1.0f, prediction.backoffMultiplier, FLOAT_DELTA)
        assertNextCheckApproximately(NOW.plusDays(1), prediction.nextCheck)
    }

    private fun analyzer(): UploadCadenceAnalyzer {
        return UploadCadenceAnalyzer { NOW }
    }

    private fun date(value: String): OffsetDateTime {
        return OffsetDateTime.parse(value)
    }

    private fun staleWeeklyDates(): List<OffsetDateTime> {
        return listOf(
            NOW.minusDays(60),
            NOW.minusDays(67),
            NOW.minusDays(74),
            NOW.minusDays(81)
        )
    }

    private fun assertNextCheckApproximately(
        expected: OffsetDateTime,
        actual: OffsetDateTime
    ) {
        val differenceSeconds = ChronoUnit.SECONDS.between(expected, actual)
        assertTrue(
            "Expected $actual to be within one minute of $expected",
            differenceSeconds in -60L..60L
        )
    }

    private fun OffsetDateTime.utcDayOfWeek(): DayOfWeek {
        return atZoneSameInstant(ZoneOffset.UTC).dayOfWeek
    }

    companion object {
        private val NOW = OffsetDateTime.parse("2026-05-15T12:00:00Z")
        private const val FLOAT_DELTA = 0.0001f
    }
}
