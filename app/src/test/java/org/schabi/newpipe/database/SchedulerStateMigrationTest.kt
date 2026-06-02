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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-based regression tests for MIGRATION_13_14, which adds the
 * smart-scheduler state columns (backoff_multiplier, detected_pattern,
 * detected_weekday, confidence) to subscription_update_info.
 *
 * We build a v13 SQLite database from raw SQL, apply the migration directly,
 * and assert on the resulting schema/data. MigrationTestHelper is intentionally
 * NOT used because it depends on Android assets that are not available to
 * Robolectric unit tests in this build configuration (see AGENTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SchedulerStateMigrationTest {

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
    fun schema_changes_apply() {
        val db = createV13Database()

        Migrations.MIGRATION_13_14.migrate(db)

        val columns = pragmaTableColumns(db, "subscription_update_info")
        assertTrue("backoff_multiplier missing", columns.containsKey("backoff_multiplier"))
        assertTrue("detected_pattern missing", columns.containsKey("detected_pattern"))
        assertTrue("detected_weekday missing", columns.containsKey("detected_weekday"))
        assertTrue("confidence missing", columns.containsKey("confidence"))
        // Type sanity (SQLite reports declared types).
        assertEquals("REAL", columns["backoff_multiplier"]?.type)
        assertEquals("INTEGER", columns["detected_pattern"]?.type)
        assertEquals("INTEGER", columns["detected_weekday"]?.type)
        assertEquals("REAL", columns["confidence"]?.type)
        // Nullability: detected_weekday must be nullable; the others NOT NULL.
        assertEquals(true, columns["detected_weekday"]?.nullable)
        assertEquals(false, columns["backoff_multiplier"]?.nullable)
        assertEquals(false, columns["detected_pattern"]?.nullable)
        assertEquals(false, columns["confidence"]?.nullable)
    }

    @Test
    fun existing_rows_preserved() {
        val db = createV13Database()
        seedSubscription(db, uid = 1L)
        seedUpdateInfoV13(
            db,
            subscriptionId = 1L,
            lastUpdated = 1_700_000_000_000L,
            nextUpdate = 1_700_604_800_000L,
            fetchInterval = 14,
            updateStrategy = 1
        )

        Migrations.MIGRATION_13_14.migrate(db)

        db.query(
            SimpleSQLiteQuery(
                "SELECT last_updated, next_update, fetch_interval, update_strategy " +
                    "FROM subscription_update_info WHERE subscription_id = ?",
                arrayOf<Any>(1L)
            )
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1_700_000_000_000L, c.getLong(0))
            assertEquals(1_700_604_800_000L, c.getLong(1))
            assertEquals(14, c.getInt(2))
            assertEquals(1, c.getInt(3))
        }
    }

    @Test
    fun defaults_backfilled() {
        val db = createV13Database()
        seedSubscription(db, uid = 2L)
        seedUpdateInfoV13(
            db,
            subscriptionId = 2L,
            lastUpdated = null,
            nextUpdate = null,
            fetchInterval = 7,
            updateStrategy = 0
        )

        Migrations.MIGRATION_13_14.migrate(db)

        db.query(
            SimpleSQLiteQuery(
                "SELECT backoff_multiplier, detected_pattern, detected_weekday, confidence " +
                    "FROM subscription_update_info WHERE subscription_id = ?",
                arrayOf<Any>(2L)
            )
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1.0f, c.getFloat(0), 0.0001f)
            assertEquals(0, c.getInt(1))
            assertTrue("detected_weekday should be NULL", c.isNull(2))
            assertNull(if (c.isNull(2)) null else c.getInt(2))
            assertEquals(0.0f, c.getFloat(3), 0.0001f)
        }
    }

    // ---------------------------------------------------------------------
    // V13 schema construction
    // ---------------------------------------------------------------------

    private fun createV13Database(): SupportSQLiteDatabase {
        dbFile = File.createTempFile("scheduler-state-migration-test-", ".db")
            .apply { delete() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(V13_SUBSCRIPTIONS_CREATE)
                db.execSQL(V13_SUBSCRIPTION_UPDATE_INFO_CREATE)
            }
            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = Unit
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

    private fun seedUpdateInfoV13(
        db: SupportSQLiteDatabase,
        subscriptionId: Long,
        lastUpdated: Long?,
        nextUpdate: Long?,
        fetchInterval: Int,
        updateStrategy: Int
    ) {
        db.insert(
            "subscription_update_info",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("subscription_id", subscriptionId)
                if (lastUpdated != null) {
                    put("last_updated", lastUpdated)
                } else {
                    putNull("last_updated")
                }
                if (nextUpdate != null) {
                    put("next_update", nextUpdate)
                } else {
                    putNull("next_update")
                }
                put("fetch_interval", fetchInterval)
                put("update_strategy", updateStrategy)
            }
        )
    }

    private data class ColumnInfo(val type: String, val nullable: Boolean)

    private fun pragmaTableColumns(
        db: SupportSQLiteDatabase,
        table: String
    ): Map<String, ColumnInfo> {
        val result = mutableMapOf<String, ColumnInfo>()
        db.query(SimpleSQLiteQuery("PRAGMA table_info(`$table`)")).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val typeIdx = c.getColumnIndexOrThrow("type")
            val notNullIdx = c.getColumnIndexOrThrow("notnull")
            while (c.moveToNext()) {
                val name = c.getString(nameIdx)
                val type = c.getString(typeIdx)
                val nullable = c.getInt(notNullIdx) == 0
                result[name] = ColumnInfo(type, nullable)
            }
        }
        return result
    }

    companion object {
        private const val V13_SUBSCRIPTIONS_CREATE =
            "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`service_id` INTEGER NOT NULL, " +
                "`url` TEXT, `name` TEXT, `avatar_url` TEXT, " +
                "`subscriber_count` INTEGER, `description` TEXT, " +
                "`notification_mode` INTEGER NOT NULL)"

        private const val V13_SUBSCRIPTION_UPDATE_INFO_CREATE =
            "CREATE TABLE IF NOT EXISTS `subscription_update_info` (" +
                "`subscription_id` INTEGER NOT NULL, " +
                "`last_updated` INTEGER, " +
                "`next_update` INTEGER, " +
                "`fetch_interval` INTEGER NOT NULL, " +
                "`update_strategy` INTEGER NOT NULL, " +
                "PRIMARY KEY(`subscription_id`), " +
                "FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) " +
                "ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)"
    }
}
