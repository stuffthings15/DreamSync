package com.remote.DreamSync.shared;

public interface TimeProvider {
    long elapsedRealtimeMs();
    long utcMs();
}
