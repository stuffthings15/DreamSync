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
public final class PhoneDatabaseMigrationTest {
    private static final String TEST_DB = "phone-migration-test";

    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PhoneDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    @Test public void migration2To4PreservesSessionAndLabelsLegacyDeepAudio()
            throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 2);
        database.execSQL("INSERT INTO sessions (sessionId, mode, state, startUtcMs, "
                + "endUtcMs, summaryJson, watchLogPath, configurationJson, "
                + "calibrationRecord, protocolVersion, waveformVersion) "
                + "VALUES ('s1','DEEP','RUNNING',1,0,'','','{}','',1,'deep-pair-v1')");
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DB, 4, true,
                PhoneDatabase.MIGRATION_2_3, PhoneDatabase.MIGRATION_3_4);
        try (Cursor cursor = database.query(
                "SELECT sessionId, watchCapabilityJson, mode FROM sessions WHERE sessionId='s1'")) {
            cursor.moveToFirst();
            assertEquals("s1", cursor.getString(0));
            assertEquals(null, cursor.getString(1));
            assertEquals("EXPERIMENTAL_AUDIO", cursor.getString(2));
        }
        try (Cursor cursor = database.query(
                "SELECT disturbanceRating FROM morning_surveys LIMIT 1")) {
            assertEquals(0, cursor.getCount());
        }
        database.close();
    }
}
