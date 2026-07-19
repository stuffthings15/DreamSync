package com.nathanw.somnicue.shared;

public final class CuePolicy {
    private int deepCues;
    private int lucidCues;
    private int cueLinkedArousals;
    private boolean disabled;

    public boolean canRequestDeep(int maximum, boolean signalReady, boolean activeCondition) {
        return !disabled && signalReady && activeCondition && deepCues < maximum;
    }

    public boolean canRequestLucid(int maximum, boolean cardiacReady, boolean activeCondition) {
        return !disabled && cardiacReady && activeCondition && lucidCues < maximum;
    }

    public void onAudioStarted(String cueType) {
        if ("DEEP".equals(cueType)) deepCues++;
        else if ("LUCID".equals(cueType)) lucidCues++;
    }

    public double onCueLinkedArousal(double currentGain) {
        cueLinkedArousals++;
        if (cueLinkedArousals >= 3) disabled = true;
        return Math.max(0.005, currentGain * 0.70);
    }

    public int getDeepCues() { return deepCues; }
    public int getLucidCues() { return lucidCues; }
    public int getCueLinkedArousals() { return cueLinkedArousals; }
    public boolean isDisabled() { return disabled; }
}
