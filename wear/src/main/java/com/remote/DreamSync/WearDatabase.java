package com.remote.DreamSync;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {WearSessionEntity.class, WearEventEntity.class, NightBaselineEntity.class},
        version = 3,
        exportSchema = true
)
public abstract class WearDatabase extends RoomDatabase {
    private static volatile WearDatabase instance;
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN awakeningCount INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN wakeAfterSleepOnsetMs INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN longestUninterruptedBoutMs INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN sleepPhase TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN estimatedSleepOnsetElapsedMs INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN baselineNights INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN sensorSource TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN capabilityJson TEXT");
            db.execSQL("CREATE TABLE IF NOT EXISTS night_baselines ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "sessionId TEXT, completedUtcMs INTEGER NOT NULL, "
                    + "restingHeartRate REAL NOT NULL, medianIbiMs REAL NOT NULL, "
                    + "ibiRmssdMs REAL NOT NULL, skinTemperatureC REAL NOT NULL, "
                    + "sleepOnsetMinutes REAL NOT NULL, valid INTEGER NOT NULL)");
        }
    };
    public abstract WearDao dao();

    public static WearDatabase get(Context context) {
        if (instance == null) {
            synchronized (WearDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(), WearDatabase.class,
                            "dreamsync-wear.db")
                            .addMigrations(MIGRATION_2_3)
                            .build();
                }
            }
        }
        return instance;
    }
}
