package com.nathanw.somnicue.shared;

public interface TimeProvider {
    long elapsedRealtimeMs();
    long utcMs();
}
