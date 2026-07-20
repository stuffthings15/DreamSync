package com.remote.DreamSync;

import static org.junit.Assert.assertEquals;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public final class WearDatabaseMigrationTest {
    private static final String TEST_DB = "wear-migration-test";

    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            WearDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    @Test public void migration2To3PreservesSessionAndAddsContinuityFields()
            throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 2);
        database.execSQL("INSERT INTO sessions (sessionId, mode, state, startUtcMs, "
                + "startElapsedMs, endUtcMs, deepCues, lucidCues, cueLinkedArousals, gain, "
                + "terminationReason) VALUES ('s1','DEEP','RUNNING',1,1,0,0,0,0,0.04,'')");
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DB, 3, true, WearDatabase.MIGRATION_2_3);
        try (Cursor cursor = database.query("SELECT sessionId, awakeningCount, "
                + "wakeAfterSleepOnsetMs FROM sessions WHERE sessionId='s1'")) {
            cursor.moveToFirst();
            assertEquals("s1", cursor.getString(0));
            assertEquals(0, cursor.getInt(1));
            assertEquals(0L, cursor.getLong(2));
        }
        database.close();
    }
}
