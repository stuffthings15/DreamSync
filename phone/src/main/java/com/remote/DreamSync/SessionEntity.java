package com.remote.DreamSync;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessions")
public class SessionEntity {
    @PrimaryKey @NonNull public String sessionId = "";
    public String mode = "DEEP";
    public String state = "PREFLIGHT";
    public long startUtcMs;
    public long endUtcMs;
    public String summaryJson = "";
    public String watchLogPath = "";
    public String configurationJson = "";
    public String calibrationRecord = "";
    public int protocolVersion = 1;
    public String waveformVersion = "";
}
