/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.notifications

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.rxjava3.RxWorker
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.local.feed.service.FeedLoadManager
import org.schabi.newpipe.local.feed.service.FeedLoadService

/*
 * Worker which checks for new streams of all subscribed channels
 * in intervals which can be set by the user in the settings.
 * This worker updates ALL subscriptions in the background using smart scheduling.
 */
class FeedAutoUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : RxWorker(appContext, workerParams) {

    private val feedLoadManager = FeedLoadManager(appContext)

    override fun createWork(): Single<Result> {
        // Always use smart scheduling for auto-update
        return feedLoadManager.startLoading(
            groupId = FeedGroupEntity.GROUP_ALL_ID,
            ignoreOutdatedThreshold = false,
            useSmartScheduling = true
        )
            .doOnSubscribe { showLoadingFeedForegroundNotification() }
            .map {
                // Successfully updated all feeds
                return@map Result.success()
            }
            .doOnError { throwable ->
                Log.e(TAG, "Error while auto-updating feeds", throwable)
                ErrorUtil.createNotification(
                    applicationContext,
                    ErrorInfo(throwable, UserAction.NEW_STREAMS_NOTIFICATIONS, "feed auto-update worker")
                )
            }
            .onErrorReturnItem(Result.failure())
    }

    private fun showLoadingFeedForegroundNotification() {
        val notification = NotificationCompat.Builder(
            applicationContext,
            applicationContext.getString(R.string.notification_channel_id)
        ).setOngoing(true)
            .setProgress(-1, -1, true)
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(applicationContext.getString(R.string.feed_notification_loading))
            .build()
        // ServiceInfo constants are not used below Android Q, so 0 is set here
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        setForegroundAsync(ForegroundInfo(FeedLoadService.NOTIFICATION_ID, notification, serviceType))
    }

    companion object {

        private val TAG = FeedAutoUpdateWorker::class.java.simpleName
        private const val WORK_TAG = App.PACKAGE_NAME + "_feed_auto_update"

        private fun isAutoUpdateEnabled(context: Context): Boolean {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val autoUpdateEnabled = sharedPreferences.getBoolean(
                context.getString(R.string.feed_auto_update_enabled_key),
                false
            )
            val smartSchedulingEnabled = sharedPreferences.getBoolean(
                context.getString(R.string.feed_smart_update_scheduling_key),
                false
            )
            return autoUpdateEnabled && smartSchedulingEnabled
        }

        /**
         * Schedules a task for the [FeedAutoUpdateWorker]
         * if the auto-update is enabled AND smart scheduling is enabled,
         * otherwise [cancel]s all scheduled tasks.
         */
        @JvmStatic
        fun initialize(context: Context) {
            if (isAutoUpdateEnabled(context)) {
                schedule(context)
            } else {
                cancel(context)
            }
        }

        /**
         * @param context the context to use
         * @param options configuration options for the scheduler
         * @param force Force the scheduler to use the new options
         * by replacing the previously used worker.
         */
        @JvmStatic
        fun schedule(context: Context, options: FeedAutoUpdateScheduleOptions, force: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (options.isRequireNonMeteredNetwork) {
                        NetworkType.UNMETERED
                    } else {
                        NetworkType.CONNECTED
                    }
                ).build()

            // Calculate initial delay to the next scheduled time
            val currentTime = java.util.Calendar.getInstance()
            val targetTime = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, options.hourOfDay)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                // If the target time has already passed today, schedule for tomorrow
                if (before(currentTime)) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }
            val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

            val request = PeriodicWorkRequest.Builder(
                FeedAutoUpdateWorker::class.java,
                1,
                TimeUnit.DAYS
            ).setConstraints(constraints)
                .addTag(WORK_TAG)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_TAG,
                    if (force) {
                        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                    } else {
                        ExistingPeriodicWorkPolicy.KEEP
                    },
                    request
                )
        }

        @JvmStatic
        fun schedule(context: Context) = schedule(context, FeedAutoUpdateScheduleOptions.from(context))

        /**
         * Check for new streams immediately
         */
        @JvmStatic
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<FeedAutoUpdateWorker>()
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Cancels all current work related to the [FeedAutoUpdateWorker].
         */
        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        }
    }
}
