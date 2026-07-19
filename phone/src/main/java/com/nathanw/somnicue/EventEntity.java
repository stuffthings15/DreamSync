package com.nathanw.somnicue;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "events", indices = {@Index("sessionId"), @Index(value = "cueId")})
public class EventEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public String sessionId = "";
    public String cueId = "";
    public String type = "";
    public long monotonicMs;
    public long utcMs;
    public String details = "";
}
