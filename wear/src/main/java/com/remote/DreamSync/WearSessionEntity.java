package com.remote.DreamSync;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessions")
public class WearSessionEntity {
    @PrimaryKey @NonNull public String sessionId = "";
    public String mode = "PROTECT_ONLY";
    public String state = "PREFLIGHT";
    public long startUtcMs;
    public long startElapsedMs;
    public long endUtcMs;
    public int deepCues;
    public int lucidCues;
    public int cueLinkedArousals;
    public int awakeningCount;
    public long wakeAfterSleepOnsetMs;
    public long longestUninterruptedBoutMs;
    public double gain;
    public String sleepPhase = "ARMED";
    public long estimatedSleepOnsetElapsedMs;
    public int baselineNights;
    public String sensorSource = "STANDARD_WEAR_OS";
    public String capabilityJson = "{}";
    public String terminationReason = "";
}
