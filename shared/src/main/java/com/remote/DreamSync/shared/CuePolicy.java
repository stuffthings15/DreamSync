package com.remote.DreamSync.shared;

public final class CuePolicy {
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private boolean disabled;
    private String disabledReason = "";

    public boolean canRequestDeep(int maximum, boolean signalReady) {
        return !disabled && signalReady && deepCues < maximum;
    }

    public boolean canRequestLucid(int maximum, boolean cardiacReady) {
        return !disabled && cardiacReady && lucidCues < maximum;
    }

    public void onAudioStarted(String cueType) {
        if ("DEEP".equals(cueType)) deepCues++;
        else if ("LUCID".equals(cueType)) lucidCues++;
    }

    public double onCueLinkedArousal(double currentGain) {
        cueLinkedArousals++;
        disable("cue-linked arousal");
        return Math.max(0.005, currentGain * 0.70);
    }

    public void disable(String reason) {
        disabled = true;
        disabledReason = reason == null ? "" : reason;
    }

    public int getDeepCues() { return deepCues; }
    public int getLucidCues() { return lucidCues; }
    public int getCueLinkedArousals() { return cueLinkedArousals; }
    public boolean isDisabled() { return disabled; }
    public String getDisabledReason() { return disabledReason; }
}
