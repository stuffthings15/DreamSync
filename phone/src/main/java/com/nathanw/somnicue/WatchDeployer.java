package com.nathanw.somnicue;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Installs the bundled wear APK onto a watch over Wi-Fi ADB (the "Wear Installer"
 * technique). Requires ADB debugging + Debug over Wi-Fi enabled on the watch and
 * both devices on the same network. Watches that only offer the newer
 * pairing-code wireless debugging (e.g. Pixel Watch) are not supported here.
 */
final class WatchDeployer {
    interface StatusListener {
        void onStatus(String message);
    }

    static final String WEAR_APK_ASSET = "dreamsync-wear.apk";

    private WatchDeployer() {}

    static void deploy(Context context, String host, int port, StatusListener listener) {
        try {
            File apk = extractWearApk(context);
            listener.onStatus("Step 1/2 — Connecting to the watch at " + host + ":" + port
                    + ". Look at the watch and tap Allow (tick \"Always allow\").");
            AdbKeyPair keyPair = loadOrCreateKeyPair(context);
            try (Dadb dadb = Dadb.Companion.create(host, port, keyPair, 15_000, 0)) {
                listener.onStatus("Step 2/2 — Connected. Installing the watch app…");
                dadb.install(apk);
                listener.onStatus("Done! Watch app installed. Open Dream Sync on the watch.");
            }
        } catch (Exception error) {
            listener.onStatus(friendlyError(error, host));
        }
    }

    // Map the common failures to plain guidance instead of a raw stack message.
    // Order matters: "closed"/"reset" must be tested before the generic "connect"
    // catch-all, because dadb reports a rejected debugging handshake as
    // "Connection closed" and that string also contains "connect".
    private static String friendlyError(Exception error, String host) {
        String message = error.getMessage();
        String raw = message == null ? "" : message.toLowerCase();
        if (raw.contains("timeout") || raw.contains("timed out")) {
            return "Couldn't reach the watch (timed out). Check the phone and watch are on the SAME Wi-Fi, and that the address matches what the watch shows. Guest networks often block this.";
        }
        if (raw.contains("refused")) {
            return "The watch refused the connection. Turn Debug over Wi-Fi off and on again, confirm the port, then retry.";
        }
        if (raw.contains("closed") || raw.contains("reset") || raw.contains("auth")) {
            return "The watch didn't accept debugging. On the watch, tap Allow on the prompt (tick \"Always allow\"), then retry.";
        }
        if (raw.contains("unreachable") || raw.contains("no route") || raw.contains("connect")) {
            return "No route to " + host + ". The watch may be on a different network or asleep — wake it, confirm the address, and retry.";
        }
        return "Install failed: " + (message == null ? error.getClass().getSimpleName() : message)
                + " — check the address, that Debug over Wi-Fi is on, and that both devices share a network.";
    }

    private static File extractWearApk(Context context) throws IOException {
        File out = new File(context.getCacheDir(), WEAR_APK_ASSET);
        try (InputStream in = context.getAssets().open(WEAR_APK_ASSET);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
        return out;
    }

    // The key pair is persisted so the watch's "always allow from this device"
    // choice keeps working across deploys.
    private static AdbKeyPair loadOrCreateKeyPair(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "adb");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create key directory");
        }
        File privateKey = new File(dir, "adbkey");
        File publicKey = new File(dir, "adbkey.pub");
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.Companion.generate(privateKey, publicKey);
        }
        return AdbKeyPair.Companion.read(privateKey, publicKey);
    }
}
