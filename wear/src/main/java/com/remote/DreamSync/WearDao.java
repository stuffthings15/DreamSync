package com.remote.DreamSync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface WearDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSession(WearSessionEntity session);
    @Insert long insertEvent(WearEventEntity event);
    @Query("UPDATE sessions SET state = 'PROCESS_INTERRUPTED', terminationReason = 'process restarted', endUtcMs = :nowUtc WHERE state IN ('RUNNING','WAITING_FOR_SLEEP','SLEEP_ONSET_ESTIMATED','CUE_WINDOW_ACTIVE','DEGRADED')")
    void markInterrupted(long nowUtc);
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    WearSessionEntity session(String sessionId);
}
