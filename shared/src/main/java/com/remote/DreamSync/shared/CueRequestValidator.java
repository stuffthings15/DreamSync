package com.remote.DreamSync.shared;

/** Fail-closed validation for a transient cue request before any playback claim. */
public final class CueRequestValidator {
    public static final class Result {
        public final boolean valid;
        public final String reason;
        public final String cueType;
        public final float gain;

        private Result(boolean valid, String reason, String cueType, float gain) {
            this.valid = valid;
            this.reason = reason;
            this.cueType = cueType;
            this.gain = gain;
        }
    }

    public Result validate(ProtocolEnvelope envelope) {
        if (envelope == null) return fail("missing envelope");
        if (!Protocol.TYPE_CUE_REQUEST.equals(envelope.messageType)) {
            return fail("wrong message type");
        }
        if (blank(envelope.messageId) || blank(envelope.sessionId)
                || blank(envelope.cueId) || blank(envelope.senderDeviceId)
                || blank(envelope.targetDeviceId)) {
            return fail("missing protocol identity");
        }
        String cueType = envelope.payload.getOrDefault("cueType", "");
        if (!"DEEP".equals(cueType) && !"LUCID".equals(cueType)) {
            return fail("unsupported cue type");
        }
        String rawGain = envelope.payload.get("gain");
        if (rawGain == null || rawGain.length() > 16) return fail("missing or invalid gain");
        final float gain;
        try {
            gain = Float.parseFloat(rawGain);
        } catch (NumberFormatException error) {
            return fail("missing or invalid gain");
        }
        if (!Float.isFinite(gain) || gain < 0.005f || gain > 0.10f) {
            return fail("gain outside safe range");
        }
        return new Result(true, "ok", cueType, gain);
    }

    private static Result fail(String reason) {
        return new Result(false, reason, "", 0f);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 128;
    }
}
