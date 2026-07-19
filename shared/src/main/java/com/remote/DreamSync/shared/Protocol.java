package com.remote.DreamSync.shared;

public final class Protocol {
    public static final int VERSION = 1;

    public static final String PHONE_CAPABILITY = "dreamsync_phone_audio_v1";
    public static final String WATCH_CAPABILITY = "dreamsync_watch_sensing_v1";

    public static final String TYPE_CONFIG = "CONFIG";
    public static final String TYPE_CONFIG_ACK = "CONFIG_ACK";
    public static final String TYPE_STOP = "STOP";
    public static final String TYPE_CUE_REQUEST = "CUE_REQUEST";
    public static final String TYPE_CUE_ACCEPTED = "CUE_ACCEPTED";
    public static final String TYPE_AUDIO_STARTED = "AUDIO_STARTED";
    public static final String TYPE_AUDIO_COMPLETED = "AUDIO_COMPLETED";
    public static final String TYPE_AUDIO_FAILED = "AUDIO_FAILED";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_SUMMARY = "SUMMARY";

    public static final String PATH_CONFIG_PREFIX = "/dreamsync/config/";
    public static final String PATH_STATE_PREFIX = "/dreamsync/state/";
    public static final String PATH_SUMMARY_PREFIX = "/dreamsync/summary/";
    public static final String PATH_CUE = "/dreamsync/cue";
    public static final String PATH_ACK = "/dreamsync/ack";
    public static final String PATH_STOP = "/dreamsync/stop";

    public static final long CUE_TTL_MS = 15_000L;
    public static final long CONTROL_TTL_MS = 120_000L;

    private Protocol() {}
}
