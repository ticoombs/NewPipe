package org.schabi.newpipe.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-based regression tests for the discovery_date migration chain
 * (10 -> 11 -> 12 -> 13).
 *
 * We build a v10 SQLite database from raw SQL (matching the v10 Room schema),
 * apply the migrations directly, and assert on the resulting data. This avoids
 * MigrationTestHelper, which depends on Android assets that are not available
 * to Robolectric unit tests in this build configuration.
 *
 * Why these tests exist:
 *  - MIGRATION_10_11 must add discovery_date AND backfill from streams.upload_date
 *    so pre-existing rows do not collapse to alphabetical-by-uploader order when
 *    "order by discovery date" is enabled.
 *  - MIGRATION_11_12 re-runs the same backfill for users who upgraded to v11
 *    before the backfill was added.
 *  - MIGRATION_12_13 is intentionally a no-op (a previous "clamp discovery_date
 *    to upload_date" approach was reverted because it destroyed the
 *    "newly seen by us" semantic of discovery_date).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedDiscoveryDateMigrationTest {

    private lateinit var openHelper: SupportSQLiteOpenHelper
    private lateinit var dbFile: File

    @After
    fun tearDown() {
        if (this::openHelper.isInitialized) {
            openHelper.close()
        }
        if (this::dbFile.isInitialized && dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun migration10to11_addsDiscoveryDateAndBackfillsFromUploadDate() {
        val uploadDateMillis = 1_700_000_000_000L
        val db = createV10Database()
        seedSubscription(db, uid = 1L)
        seedStream(db, uid = 10L, uploadDateMillis = uploadDateMillis)
        seedStream(db, uid = 11L, uploadDateMillis = null)
        seedFeedV10(db, streamId = 10L, subscriptionId = 1L)
        seedFeedV10(db, streamId = 11L, subscriptionId = 1L)

        Migrations.MIGRATION_10_11.migrate(db)

        // Row whose stream had an upload_date should be backfilled to that value.
        assertEquals(uploadDateMillis, queryDiscoveryDate(db, streamId = 10L))
        // Row whose stream had no upload_date stays NULL (no source value).
        assertNull(queryDiscoveryDate(db, streamId = 11L))
    }

    @Test
    fun migration11to12_backfillsNullDiscoveryDates() {
        val uploadDateMillis = 1_700_000_000_000L
        val preExistingDiscoveryDate = 1_650_000_000_000L

        val db = createV10Database()
        seedSubscription(db, uid = 1L)
        seedStream(db, uid = 20L, uploadDateMillis = uploadDateMillis)
        seedStream(db, uid = 21L, uploadDateMillis = uploadDateMillis)
        seedFeedV10(db, streamId = 20L, subscriptionId = 1L)
        seedFeedV10(db, streamId = 21L, subscriptionId = 1L)

        // Move to v11 by adding the column WITHOUT the backfill, simulating a
        // user who upgraded before MIGRATION_10_11 had the backfill.
        db.execSQL("ALTER TABLE feed ADD COLUMN discovery_date INTEGER")
        // Pre-existing discovery_date on one row must be preserved by 11->12.
        db.execSQL(
            "UPDATE feed SET discovery_date = ? WHERE stream_id = ?",
            arrayOf<Any>(preExistingDiscoveryDate, 21L)
        )

        Migrations.MIGRATION_11_12.migrate(db)

        assertEquals(uploadDateMillis, queryDiscoveryDate(db, streamId = 20L))
        assertEquals(preExistingDiscoveryDate, queryDiscoveryDate(db, streamId = 21L))
    }

    @Test
    fun migration12to13_isNoOpAndPreservesDiscoveryDate() {
        val discoveryDate = 1_700_000_000_000L

        val db = createV10Database()
        seedSubscription(db, uid = 1L)
        seedStream(db, uid = 30L, uploadDateMillis = 1_600_000_000_000L)
        seedFeedV10(db, streamId = 30L, subscriptionId = 1L)

        // Walk forward to v12.
        Migrations.MIGRATION_10_11.migrate(db)
        Migrations.MIGRATION_11_12.migrate(db)
        // Override with our test value (10->11 backfilled to upload_date).
        db.execSQL(
            "UPDATE feed SET discovery_date = ? WHERE stream_id = ?",
            arrayOf<Any>(discoveryDate, 30L)
        )

        Migrations.MIGRATION_12_13.migrate(db)

        // Discovery date must NOT be touched: this guards against the reverted
        // "clamp discovery_date to upload_date" approach that destroyed the
        // "newly seen by us" semantic.
        assertEquals(discoveryDate, queryDiscoveryDate(db, streamId = 30L))
    }

    // ---------------------------------------------------------------------
    // V10 schema construction
    // ---------------------------------------------------------------------

    private fun createV10Database(): SupportSQLiteDatabase {
        dbFile = File.createTempFile("feed-migration-test-", ".db").apply { delete() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Subset of v10 schema sufficient for discovery_date migrations.
                db.execSQL(V10_SUBSCRIPTIONS_CREATE)
                db.execSQL(V10_STREAMS_CREATE)
                db.execSQL(V10_FEED_CREATE)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(callback)
            .build()
        openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        return openHelper.writableDatabase
    }

    private fun seedSubscription(db: SupportSQLiteDatabase, uid: Long) {
        db.insert(
            "subscriptions",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("uid", uid)
                put("service_id", 0)
                put("url", "https://example.com/sub/$uid")
                put("name", "Sub $uid")
                put("avatar_url", "")
                put("subscriber_count", 0L)
                put("description", "")
                put("notification_mode", 0)
            }
        )
    }

    private fun seedStream(
        db: SupportSQLiteDatabase,
        uid: Long,
        uploadDateMillis: Long?
    ) {
        db.insert(
            "streams",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("uid", uid)
                put("service_id", 0)
                put("url", "https://example.com/v/$uid")
                put("title", "Stream $uid")
                put("stream_type", "VIDEO_STREAM")
                put("duration", 60L)
                put("uploader", "Uploader")
                put("uploader_url", "")
                put("thumbnail_url", "")
                if (uploadDateMillis != null) {
                    put("upload_date", uploadDateMillis)
                } else {
                    putNull("upload_date")
                }
            }
        )
    }

    private fun seedFeedV10(
        db: SupportSQLiteDatabase,
        streamId: Long,
        subscriptionId: Long
    ) {
        db.insert(
            "feed",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("stream_id", streamId)
                put("subscription_id", subscriptionId)
            }
        )
    }

    private fun queryDiscoveryDate(db: SupportSQLiteDatabase, streamId: Long): Long? {
        db.query(
            SimpleSQLiteQuery(
                "SELECT discovery_date FROM feed WHERE stream_id = ?",
                arrayOf<Any>(streamId)
            )
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw AssertionError("No feed row for stream_id=$streamId")
            }
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    companion object {
        // V10 schema (extracted from app/schemas/.../10.json).
        private const val V10_SUBSCRIPTIONS_CREATE =
            "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`service_id` INTEGER NOT NULL, " +
                "`url` TEXT, `name` TEXT, `avatar_url` TEXT, " +
                "`subscriber_count` INTEGER, `description` TEXT, " +
                "`notification_mode` INTEGER NOT NULL)"

        private const val V10_STREAMS_CREATE =
            "CREATE TABLE IF NOT EXISTS `streams` (" +
                "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`service_id` INTEGER NOT NULL, `url` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `stream_type` TEXT NOT NULL, " +
                "`duration` INTEGER NOT NULL, `uploader` TEXT NOT NULL, " +
                "`uploader_url` TEXT, `thumbnail_url` TEXT, " +
                "`view_count` INTEGER, `textual_upload_date` TEXT, " +
                "`upload_date` INTEGER, " +
                "`is_upload_date_approximation` INTEGER)"

        private const val V10_FEED_CREATE =
            "CREATE TABLE IF NOT EXISTS `feed` (" +
                "`stream_id` INTEGER NOT NULL, " +
                "`subscription_id` INTEGER NOT NULL, " +
                "PRIMARY KEY(`stream_id`, `subscription_id`), " +
                "FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) " +
                "ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, " +
                "FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) " +
                "ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)"
    }
}
