package com.remote.DreamSync;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "night_baselines")
public class NightBaselineEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public String sessionId = "";
    public long completedUtcMs;
    public double restingHeartRate = Double.NaN;
    public double medianIbiMs = Double.NaN;
    public double ibiRmssdMs = Double.NaN;
    public double skinTemperatureC = Double.NaN;
    public double sleepOnsetMinutes = Double.NaN;
    public boolean valid;
}
