package com.remote.DreamSync;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "morning_surveys")
public class MorningSurveyEntity {
    @PrimaryKey @NonNull public String sessionId = "";
    public int sleepQuality;
    public int restedness;
    public int rememberedAwakenings;
    public int disturbanceRating;
    public String cueAwareness = "UNSURE";
    public int dreamRecall;
    public String lucidDream = "UNSURE";
    public String adverseEffects = "";
    public String dreamNotes = "";
    public long submittedUtcMs;
}
