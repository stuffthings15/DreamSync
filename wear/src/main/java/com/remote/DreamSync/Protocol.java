package com.remote.DreamSync;

/** Compatibility aliases. The shared module is the protocol authority. */
final class Protocol {
    static final String PATH_CONFIG = com.remote.DreamSync.shared.Protocol.PATH_CONFIG_PREFIX;
    static final String PATH_STOP = com.remote.DreamSync.shared.Protocol.PATH_STOP;
    static final String PATH_DEEP_CUE = com.remote.DreamSync.shared.Protocol.PATH_CUE;
    static final String PATH_LUCID_CUE = com.remote.DreamSync.shared.Protocol.PATH_CUE;
    static final String PATH_SUMMARY = com.remote.DreamSync.shared.Protocol.PATH_SUMMARY_PREFIX;
    static final String PATH_ACK = com.remote.DreamSync.shared.Protocol.PATH_ACK;
    private Protocol() {}
}
