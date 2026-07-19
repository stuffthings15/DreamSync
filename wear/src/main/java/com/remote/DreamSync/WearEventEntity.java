package com.remote.DreamSync;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "events", indices = {@Index("sessionId"), @Index("cueId")})
public class WearEventEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public String sessionId = "";
    public String cueId = "";
    public String type = "";
    public long elapsedMs;
    public long utcMs;
    public String details = "";
}
