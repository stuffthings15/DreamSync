package com.remote.DreamSync.shared;

/** Rejects spoofed or incomplete broadcasts before boot recovery work begins. */
public final class BootActionGate {
    public static final String BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";

    public static boolean accepts(String action) {
        return BOOT_COMPLETED.equals(action) || PACKAGE_REPLACED.equals(action);
    }

    private BootActionGate() {}
}
