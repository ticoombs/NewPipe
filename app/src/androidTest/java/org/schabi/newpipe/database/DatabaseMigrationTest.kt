package org.schabi.newpipe.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    companion object {
        private const val DEFAULT_SERVICE_ID = 0
        private const val DEFAULT_URL = "https://www.youtube.com/watch?v=cDphUib5iG4"
        private const val DEFAULT_TITLE = "Test Title"
        private const val DEFAULT_NAME = "Test Name"
        private val DEFAULT_TYPE = StreamType.VIDEO_STREAM
        private const val DEFAULT_DURATION = 480L
        private const val DEFAULT_UPLOADER_NAME = "Uploader Test"
        private const val DEFAULT_THUMBNAIL = "https://example.com/example.jpg"

        private const val DEFAULT_SECOND_SERVICE_ID = 1
        private const val DEFAULT_SECOND_URL = "https://www.youtube.com/watch?v=ncQU6iBn5Fc"

        private const val DEFAULT_THIRD_SERVICE_ID = 2
        private const val DEFAULT_THIRD_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }

    @get:Rule
    val testHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrateDatabaseFrom2to3() {
        val databaseInV2 = testHelper.createDatabase(AppDatabase.DATABASE_NAME, Migrations.DB_VER_2)

        databaseInV2.run {
            insert(
                "streams",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", DEFAULT_SERVICE_ID)
                    put("url", DEFAULT_URL)
                    put("title", DEFAULT_TITLE)
                    put("stream_type", DEFAULT_TYPE.name)
                    put("duration", DEFAULT_DURATION)
                    put("uploader", DEFAULT_UPLOADER_NAME)
                    put("thumbnail_url", DEFAULT_THUMBNAIL)
                }
            )
            insert(
                "streams",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", DEFAULT_SECOND_SERVICE_ID)
                    put("url", DEFAULT_SECOND_URL)
                }
            )
            insert(
                "streams",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", DEFAULT_SERVICE_ID)
                }
            )
            close()
        }

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_3,
            true,
            Migrations.MIGRATION_2_3
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_4,
            true,
            Migrations.MIGRATION_3_4
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_5,
            true,
            Migrations.MIGRATION_4_5
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_6,
            true,
            Migrations.MIGRATION_5_6
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_7,
            true,
            Migrations.MIGRATION_6_7
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_8,
            true,
            Migrations.MIGRATION_7_8
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_9,
            true,
            Migrations.MIGRATION_8_9
        )

        val migratedDatabaseV3 = getMigratedDatabase()
        val listFromDB = migratedDatabaseV3.streamDAO().getAll().blockingFirst()

        // Only expect 2, the one with the null url will be ignored
        assertEquals(2, listFromDB.size)

        val streamFromMigratedDatabase = listFromDB[0]
        assertEquals(DEFAULT_SERVICE_ID, streamFromMigratedDatabase.serviceId)
        assertEquals(DEFAULT_URL, streamFromMigratedDatabase.url)
        assertEquals(DEFAULT_TITLE, streamFromMigratedDatabase.title)
        assertEquals(DEFAULT_TYPE, streamFromMigratedDatabase.streamType)
        assertEquals(DEFAULT_DURATION, streamFromMigratedDatabase.duration)
        assertEquals(DEFAULT_UPLOADER_NAME, streamFromMigratedDatabase.uploader)
        assertEquals(DEFAULT_THUMBNAIL, streamFromMigratedDatabase.thumbnailUrl)
        assertNull(streamFromMigratedDatabase.viewCount)
        assertNull(streamFromMigratedDatabase.textualUploadDate)
        assertNull(streamFromMigratedDatabase.uploadDate)
        assertNull(streamFromMigratedDatabase.isUploadDateApproximation)

        val secondStreamFromMigratedDatabase = listFromDB[1]
        assertEquals(DEFAULT_SECOND_SERVICE_ID, secondStreamFromMigratedDatabase.serviceId)
        assertEquals(DEFAULT_SECOND_URL, secondStreamFromMigratedDatabase.url)
        assertEquals("", secondStreamFromMigratedDatabase.title)
        // Should fallback to VIDEO_STREAM
        assertEquals(StreamType.VIDEO_STREAM, secondStreamFromMigratedDatabase.streamType)
        assertEquals(0, secondStreamFromMigratedDatabase.duration)
        assertEquals("", secondStreamFromMigratedDatabase.uploader)
        assertEquals("", secondStreamFromMigratedDatabase.thumbnailUrl)
        assertNull(secondStreamFromMigratedDatabase.viewCount)
        assertNull(secondStreamFromMigratedDatabase.textualUploadDate)
        assertNull(secondStreamFromMigratedDatabase.uploadDate)
        assertNull(secondStreamFromMigratedDatabase.isUploadDateApproximation)
    }

    @Test
    fun migrateDatabaseFrom7to8() {
        val databaseInV7 = testHelper.createDatabase(AppDatabase.DATABASE_NAME, Migrations.DB_VER_7)

        val defaultSearch1 = " abc "
        val defaultSearch2 = " abc"

        val serviceId = DEFAULT_SERVICE_ID // YouTube
        // Use id different to YouTube because two searches with the same query
        // but different service are considered not equal.
        val otherServiceId = ServiceList.SoundCloud.serviceId

        databaseInV7.run {
            insert(
                "search_history", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", serviceId)
                    put("search", defaultSearch1)
                }
            )
            insert(
                "search_history", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", serviceId)
                    put("search", defaultSearch2)
                }
            )
            insert(
                "search_history", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", otherServiceId)
                    put("search", defaultSearch1)
                }
            )
            insert(
                "search_history", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", otherServiceId)
                    put("search", defaultSearch2)
                }
            )
            close()
        }

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME, Migrations.DB_VER_8,
            true, Migrations.MIGRATION_7_8
        )

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME, Migrations.DB_VER_9,
            true, Migrations.MIGRATION_8_9
        )

        val migratedDatabaseV8 = getMigratedDatabase()
        val listFromDB = migratedDatabaseV8.searchHistoryDAO().getAll().blockingFirst()

        assertEquals(2, listFromDB.size)
        assertEquals("abc", listFromDB[0].search)
        assertEquals("abc", listFromDB[1].search)
        assertNotEquals(listFromDB[0].serviceId, listFromDB[1].serviceId)
    }

    @Test
    fun migrateDatabaseFrom8to9() {
        val databaseInV8 = testHelper.createDatabase(AppDatabase.DATABASE_NAME, Migrations.DB_VER_8)

        val localUid1: Long
        val localUid2: Long
        val remoteUid1: Long
        val remoteUid2: Long
        databaseInV8.run {
            localUid1 = insert(
                "playlists", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("name", DEFAULT_NAME + "1")
                    put("is_thumbnail_permanent", false)
                    put("thumbnail_stream_id", -1)
                }
            )
            localUid2 = insert(
                "playlists", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("name", DEFAULT_NAME + "2")
                    put("is_thumbnail_permanent", false)
                    put("thumbnail_stream_id", -1)
                }
            )
            delete(
                "playlists", "uid = ?",
                Array(1) { localUid1 }
            )
            remoteUid1 = insert(
                "remote_playlists", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", DEFAULT_SERVICE_ID)
                    put("url", DEFAULT_URL)
                }
            )
            remoteUid2 = insert(
                "remote_playlists", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("service_id", DEFAULT_SECOND_SERVICE_ID)
                    put("url", DEFAULT_SECOND_URL)
                }
            )
            delete(
                "remote_playlists", "uid = ?",
                Array(1) { remoteUid2 }
            )
            close()
        }

        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_9,
            true,
            Migrations.MIGRATION_8_9
        )

        val migratedDatabaseV9 = getMigratedDatabase()
        var localListFromDB = migratedDatabaseV9.playlistDAO().getAll().blockingFirst()
        var remoteListFromDB = migratedDatabaseV9.playlistRemoteDAO().getAll().blockingFirst()

        assertEquals(1, localListFromDB.size)
        assertEquals(localUid2, localListFromDB[0].uid)
        assertEquals(-1, localListFromDB[0].displayIndex)
        assertEquals(1, remoteListFromDB.size)
        assertEquals(remoteUid1, remoteListFromDB[0].uid)
        assertEquals(-1, remoteListFromDB[0].displayIndex)

        val localUid3 = migratedDatabaseV9.playlistDAO().insert(
            PlaylistEntity(
                name = "${DEFAULT_NAME}3",
                isThumbnailPermanent = false,
                thumbnailStreamId = -1,
                displayIndex = -1
            )
        )
        val remoteUid3 = migratedDatabaseV9.playlistRemoteDAO().insert(
            PlaylistRemoteEntity(
                serviceId = DEFAULT_THIRD_SERVICE_ID,
                orderingName = DEFAULT_NAME,
                url = DEFAULT_THIRD_URL,
                thumbnailUrl = DEFAULT_THUMBNAIL,
                uploader = DEFAULT_UPLOADER_NAME,
                displayIndex = -1,
                streamCount = 10
            )
        )

        localListFromDB = migratedDatabaseV9.playlistDAO().getAll().blockingFirst()
        remoteListFromDB = migratedDatabaseV9.playlistRemoteDAO().getAll().blockingFirst()
        assertEquals(2, localListFromDB.size)
        assertEquals(localUid3, localListFromDB[1].uid)
        assertEquals(-1, localListFromDB[1].displayIndex)
        assertEquals(2, remoteListFromDB.size)
        assertEquals(remoteUid3, remoteListFromDB[1].uid)
        assertEquals(-1, remoteListFromDB[1].displayIndex)
    }

    @Test
    @Throws(IOException::class)
    fun migrateDatabaseFrom9to10() {
        val databaseInV9 = testHelper.createDatabase(AppDatabase.DATABASE_NAME, Migrations.DB_VER_9)

        // Insert test data in version 9
        databaseInV9.run {
            // Insert a subscription
            insert(
                "subscriptions", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("uid", 1)
                    put("service_id", 0)
                    put("url", "https://youtube.com/test")
                    put("name", "Test Channel")
                    put("avatar_url", "")
                    put("subscriber_count", 1000)
                    put("description", "")
                    put("notification_mode", 0)
                }
            )

            // Insert feed_last_updated entry
            insert(
                "feed_last_updated", SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("subscription_id", 1)
                    put("last_updated", System.currentTimeMillis())
                }
            )

            close()
        }

        // Run migration
        testHelper.runMigrationsAndValidate(
            AppDatabase.DATABASE_NAME,
            Migrations.DB_VER_10,
            true,
            Migrations.MIGRATION_9_10
        )

        // Verify data preserved and structure correct
        val migratedDb = getMigratedDatabase()
        val cursor = migratedDb.query(SimpleSQLiteQuery("SELECT * FROM subscription_update_info WHERE subscription_id = 1"))

        cursor.use {
            assertTrue("Update info should exist", it.moveToFirst())

            val subscriptionIdIndex = it.getColumnIndex("subscription_id")
            val lastUpdatedIndex = it.getColumnIndex("last_updated")
            val nextUpdateIndex = it.getColumnIndex("next_update")
            val fetchIntervalIndex = it.getColumnIndex("fetch_interval")
            val updateStrategyIndex = it.getColumnIndex("update_strategy")

            assertEquals("subscription_id should be 1", 1, it.getLong(subscriptionIdIndex))
            assertFalse("last_updated should be preserved", it.isNull(lastUpdatedIndex))
            assertTrue("next_update should be NULL initially", it.isNull(nextUpdateIndex))
            assertEquals("Default interval should be 7", 7, it.getInt(fetchIntervalIndex))
            assertEquals("Default strategy should be 0", 0, it.getInt(updateStrategyIndex))
        }

        // Verify old table was dropped
        val tablesCursor = migratedDb.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='feed_last_updated'"))
        tablesCursor.use {
            assertFalse("feed_last_updated table should be dropped", it.moveToFirst())
        }
    }

    private fun getMigratedDatabase(): AppDatabase {
        val database: AppDatabase = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .build()
        testHelper.closeWhenFinished(database)
        return database
    }
}
