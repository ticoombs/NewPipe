/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.service

import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class UploadCadenceAnalyzer(private val clock: () -> OffsetDateTime) {

    data class Prediction(
        val nextCheck: OffsetDateTime,
        val intervalDays: Int,
        val pattern: Pattern,
        val detectedWeekday: DayOfWeek?,
        val confidence: Float,
        val backoffMultiplier: Float
    )

    enum class Pattern { NEW, WEEKDAY_PERIODIC, ACTIVE_EWMA, DORMANT }

    fun predict(
        uploadDates: List<OffsetDateTime>,
        prevBackoffMultiplier: Float = 1.0f
    ): Prediction {
        val now = clock()

        if (uploadDates.size < MINIMUM_SAMPLE_SIZE) {
            return Prediction(
                nextCheck = now.plusDays(1),
                intervalDays = 1,
                pattern = Pattern.NEW,
                detectedWeekday = null,
                confidence = 0.0f,
                backoffMultiplier = 1.0f
            )
        }

        val sortedDates = uploadDates.sortedDescending()
        val sample = sortedDates.take(min(MAXIMUM_WEEKDAY_SAMPLE_SIZE, sortedDates.size))
        val weekdayPrediction = predictWeekdayPeriodic(now, sample)
        if (weekdayPrediction != null) {
            return weekdayPrediction
        }

        return predictActiveOrDormant(now, sortedDates, prevBackoffMultiplier)
    }

    private fun predictWeekdayPeriodic(
        now: OffsetDateTime,
        sample: List<OffsetDateTime>
    ): Prediction? {
        val zoneId = ZoneId.systemDefault()
        val weekdays = sample.map { it.atZoneSameInstant(zoneId).dayOfWeek }
        val weekdayCounts = weekdays.groupingBy { it }.eachCount()
        val sortedWeekdayCounts = weekdayCounts.entries.sortedWith(
            compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }
                .thenBy { entry -> weekdays.indexOf(entry.key) }
        )
        val topWeekday = sortedWeekdayCounts[0]
        val secondWeekday = sortedWeekdayCounts.getOrNull(1)
        val topTwoCount = topWeekday.value + (secondWeekday?.value ?: 0)
        val skewRatio = topTwoCount.toFloat() / sample.size

        // A 70% top-two skew catches weekly and Tue+Thu schedules while rejecting noisy channels.
        if (skewRatio < WEEKDAY_SKEW_THRESHOLD || sample.size < MINIMUM_WEEKDAY_SAMPLE_SIZE) {
            return null
        }

        val medianHour = sample.map { it.atZoneSameInstant(zoneId).hour }
            .sorted()[sample.size / 2]
        val nextCheck = findNextWeekday(
            now = now,
            primary = topWeekday.key,
            secondary = secondWeekday?.key,
            hour = medianHour
        )
        val intervalDays = ChronoUnit.DAYS.between(
            now.toLocalDate(),
            nextCheck.toLocalDate()
        ).toInt().coerceAtLeast(1).coerceIn(MINIMUM_INTERVAL_DAYS, MAXIMUM_INTERVAL_DAYS)

        return Prediction(
            nextCheck = nextCheck,
            intervalDays = intervalDays,
            pattern = Pattern.WEEKDAY_PERIODIC,
            detectedWeekday = topWeekday.key,
            confidence = skewRatio.coerceIn(0.0f, 1.0f),
            backoffMultiplier = 1.0f
        )
    }

    private fun predictActiveOrDormant(
        now: OffsetDateTime,
        sortedDates: List<OffsetDateTime>,
        prevBackoffMultiplier: Float
    ): Prediction {
        val intervals = sortedDates.windowed(2)
            .map { (newer, older) ->
                ChronoUnit.DAYS.between(older.toLocalDate(), newer.toLocalDate()).toInt()
            }
            .filter { it > 0 }

        if (intervals.isEmpty()) {
            return Prediction(
                nextCheck = now.plusDays(1),
                intervalDays = 1,
                pattern = Pattern.ACTIVE_EWMA,
                detectedWeekday = null,
                confidence = 0.5f,
                backoffMultiplier = 1.0f
            )
        }

        val predictedDays = calculateEwma(intervals).roundToInt()
        val daysSinceLatest = ChronoUnit.DAYS.between(
            sortedDates[0].toLocalDate(),
            now.toLocalDate()
        ).toInt()

        if (daysSinceLatest > DORMANT_MULTIPLIER * predictedDays) {
            val newBackoff = prevBackoffMultiplier * BACKOFF_GROWTH_FACTOR
            val dormantInterval = (predictedDays * newBackoff).roundToInt()
                .coerceIn(MINIMUM_INTERVAL_DAYS, MAXIMUM_INTERVAL_DAYS)

            return Prediction(
                nextCheck = now.plusDays(dormantInterval.toLong()),
                intervalDays = dormantInterval,
                pattern = Pattern.DORMANT,
                detectedWeekday = null,
                confidence = 0.3f,
                backoffMultiplier = newBackoff
            )
        }

        val finalInterval = predictedDays.coerceIn(MINIMUM_INTERVAL_DAYS, MAXIMUM_INTERVAL_DAYS)
        return Prediction(
            nextCheck = now.plusDays(finalInterval.toLong()),
            intervalDays = finalInterval,
            pattern = Pattern.ACTIVE_EWMA,
            detectedWeekday = null,
            confidence = calculateConfidence(intervals),
            backoffMultiplier = 1.0f
        )
    }

    private fun calculateEwma(intervals: List<Int>): Double {
        // α=0.3 weights recent gaps enough to react without overfitting a single missed upload.
        var smoothedInterval = intervals[0].toDouble()
        for (index in 1 until intervals.size) {
            smoothedInterval = EWMA_ALPHA * intervals[index] + (1.0 - EWMA_ALPHA) * smoothedInterval
        }
        return smoothedInterval
    }

    private fun calculateConfidence(intervals: List<Int>): Float {
        val mean = intervals.map { it.toDouble() }.average()
        val stddev = sqrt(intervals.map { (it - mean).pow(2) }.average())
        return max(0.0, 1.0 - stddev / mean).toFloat().coerceIn(0.0f, 1.0f)
    }

    private fun findNextWeekday(
        now: OffsetDateTime,
        primary: DayOfWeek,
        secondary: DayOfWeek?,
        hour: Int
    ): OffsetDateTime {
        val zoneId = ZoneId.systemDefault()
        val localNow = now.atZoneSameInstant(zoneId)
        var candidateDate = localNow.toLocalDate().plusDays(1)

        while (candidateDate.dayOfWeek != primary && candidateDate.dayOfWeek != secondary) {
            candidateDate = candidateDate.plusDays(1)
        }

        return candidateDate.atTime(hour, 0)
            .atZone(zoneId)
            .toOffsetDateTime()
    }

    companion object {
        private const val MINIMUM_SAMPLE_SIZE = 3
        private const val MINIMUM_WEEKDAY_SAMPLE_SIZE = 5
        private const val MAXIMUM_WEEKDAY_SAMPLE_SIZE = 20
        private const val MINIMUM_INTERVAL_DAYS = 1
        private const val MAXIMUM_INTERVAL_DAYS = 14
        private const val DORMANT_MULTIPLIER = 3
        private const val BACKOFF_GROWTH_FACTOR = 1.5f
        private const val WEEKDAY_SKEW_THRESHOLD = 0.70f
        private const val EWMA_ALPHA = 0.3
    }
}
