package com.nathanw.somnicue;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {WearSessionEntity.class, WearEventEntity.class}, version = 1, exportSchema = true)
public abstract class WearDatabase extends RoomDatabase {
    private static volatile WearDatabase instance;
    public abstract WearDao dao();

    public static WearDatabase get(Context context) {
        if (instance == null) {
            synchronized (WearDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(), WearDatabase.class,
                            "dreamsync-wear.db").build();
                }
            }
        }
        return instance;
    }
}
