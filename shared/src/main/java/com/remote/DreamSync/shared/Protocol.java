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
    public static final String TYPE_AUTO_CONFIG = "AUTO_CONFIG";
    public static final String TYPE_AUTO_CONFIG_ACK = "AUTO_CONFIG_ACK";
    public static final String TYPE_AUTO_SESSION_START = "AUTO_SESSION_START";
    public static final String TYPE_AUTO_OFF = "AUTO_OFF";
    public static final String TYPE_DELETE_DATA = "DELETE_DATA";
    public static final String TYPE_DIAGNOSTIC = "DIAGNOSTIC";

    public static final String PATH_CONFIG_PREFIX = "/dreamsync/config/";
    public static final String PATH_STATE_PREFIX = "/dreamsync/state/";
    public static final String PATH_SUMMARY_PREFIX = "/dreamsync/summary/";
    public static final String PATH_CUE = "/dreamsync/cue";
    public static final String PATH_ACK = "/dreamsync/ack";
    public static final String PATH_STOP = "/dreamsync/stop";
    public static final String PATH_AUTO_CONFIG = "/dreamsync/auto/config";
    public static final String PATH_AUTO_STATE_PREFIX = "/dreamsync/auto/state/";
    public static final String PATH_AUTO_SESSION_PREFIX = "/dreamsync/auto/session/";
    public static final String PATH_DELETE_DATA_PREFIX = "/dreamsync/admin/delete/";
    public static final String PATH_DIAGNOSTIC_PREFIX = "/dreamsync/diagnostic/";

    public static final long CUE_TTL_MS = 15_000L;
    public static final long CONTROL_TTL_MS = 120_000L;
    public static final long AUTO_SESSION_TTL_MS = 300_000L;
    public static final long AUTO_CONFIG_TTL_MS = 10L * 365L * 24L * 60L * 60L * 1000L;
    public static final long DIAGNOSTIC_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private Protocol() {}
}
