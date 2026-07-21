package com.remote.DreamSync;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {SessionEntity.class, EventEntity.class, MorningSurveyEntity.class},
        version = 4,
        exportSchema = true
)
public abstract class PhoneDatabase extends RoomDatabase {
    private static volatile PhoneDatabase instance;
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN watchCapabilityJson TEXT");
            db.execSQL("ALTER TABLE morning_surveys ADD COLUMN disturbanceRating INTEGER NOT NULL DEFAULT 0");
        }
    };
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("UPDATE sessions SET mode='EXPERIMENTAL_AUDIO' WHERE mode='DEEP'");
        }
    };
    public abstract PhoneDao dao();

    public static PhoneDatabase get(Context context) {
        if (instance == null) {
            synchronized (PhoneDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(), PhoneDatabase.class,
                            "dreamsync-phone.db")
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return instance;
    }
}
