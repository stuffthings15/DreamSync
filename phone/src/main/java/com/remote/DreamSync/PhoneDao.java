package com.remote.DreamSync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PhoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSession(SessionEntity session);
    @Insert long insertEvent(EventEntity event);
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSurvey(MorningSurveyEntity survey);
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    SessionEntity session(String sessionId);
    @Query("SELECT * FROM morning_surveys WHERE sessionId = :sessionId LIMIT 1")
    MorningSurveyEntity survey(String sessionId);
    @Query("SELECT * FROM sessions ORDER BY startUtcMs DESC")
    List<SessionEntity> sessions();
    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY id")
    List<EventEntity> events(String sessionId);
    @Query("SELECT COUNT(*) FROM events WHERE cueId = :cueId AND type IN ('CUE_ACCEPTED','AUDIO_STARTED','AUDIO_COMPLETED')")
    int playedCueCount(String cueId);
    @Query("SELECT type FROM events WHERE cueId = :cueId ORDER BY id DESC LIMIT 1")
    String latestCueState(String cueId);
    @Query("DELETE FROM sessions WHERE sessionId = :sessionId") void deleteSession(String sessionId);
    @Query("DELETE FROM events WHERE sessionId = :sessionId") void deleteEvents(String sessionId);
    @Query("DELETE FROM morning_surveys WHERE sessionId = :sessionId") void deleteSurvey(String sessionId);
    @Query("DELETE FROM sessions") void deleteAllSessions();
    @Query("DELETE FROM events") void deleteAllEvents();
    @Query("DELETE FROM morning_surveys") void deleteAllSurveys();
}
