package com.nathanw.somnicue;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {SessionEntity.class, EventEntity.class, MorningSurveyEntity.class},
        version = 1,
        exportSchema = true
)
public abstract class PhoneDatabase extends RoomDatabase {
    private static volatile PhoneDatabase instance;
    public abstract PhoneDao dao();

    public static PhoneDatabase get(Context context) {
        if (instance == null) {
            synchronized (PhoneDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(), PhoneDatabase.class,
                            "dreamsync-phone.db").build();
                }
            }
        }
        return instance;
    }
}
