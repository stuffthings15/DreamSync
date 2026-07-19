package com.nathanw.somnicue;

/** Compatibility aliases. The shared module is the protocol authority. */
final class Protocol {
    static final String PATH_CONFIG = com.nathanw.somnicue.shared.Protocol.PATH_CONFIG_PREFIX;
    static final String PATH_STOP = com.nathanw.somnicue.shared.Protocol.PATH_STOP;
    static final String PATH_DEEP_CUE = com.nathanw.somnicue.shared.Protocol.PATH_CUE;
    static final String PATH_LUCID_CUE = com.nathanw.somnicue.shared.Protocol.PATH_CUE;
    static final String PATH_SUMMARY = com.nathanw.somnicue.shared.Protocol.PATH_SUMMARY_PREFIX;
    static final String PATH_ACK = com.nathanw.somnicue.shared.Protocol.PATH_ACK;
    private Protocol() {}
}
