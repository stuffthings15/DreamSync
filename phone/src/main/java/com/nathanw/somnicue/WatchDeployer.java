package com.nathanw.somnicue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Installs the bundled wear APK onto a watch over Wi-Fi ADB (the "Wear Installer"
 * technique). Requires ADB debugging + Debug over Wi-Fi enabled on the watch and
 * both devices on the same network. Watches that only offer the newer
 * pairing-code wireless debugging (e.g. Pixel Watch) are not supported here.
 */
final class WatchDeployer {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int SOCKET_TIMEOUT_MS = 120_000;
    private static final String REMOTE_APK = "/data/local/tmp/dreamsync-wear.apk";
    private static final String LOG_FILE = "watch-installer-error-log.txt";
    private static final long MAX_LOG_BYTES = 512L * 1024L;
    private static final Object LOG_LOCK = new Object();

    interface StatusListener {
        void onStatus(String message);
    }

    static final String WEAR_APK_ASSET = "dreamsync-wear.apk";

    private WatchDeployer() {}

    static void deploy(Context context, String host, int port, StatusListener listener) {
        record(context, "--- INSTALL ATTEMPT ---");
        record(context, "Phone=" + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android=" + Build.VERSION.RELEASE + ", SDK=" + Build.VERSION.SDK_INT
                + ", app=" + appVersion(context));
        record(context, "Target=" + host + ":" + port);
        try {
            File apk = extractWearApk(context);
            record(context, "Bundled APK bytes=" + apk.length());
            report(context, listener, "Step 1/4 - Connecting to the watch at " + host + ":" + port
                    + ". Look at the watch and tap Allow (tick \"Always allow\").");
            AdbKeyPair keyPair = loadOrCreateKeyPair(context);
            // dadb connects lazily. A zero socket timeout can leave an install
            // or authorization read waiting forever, with the UI stuck.
            try (Dadb dadb = Dadb.Companion.create(
                    host, port, keyPair, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)) {
                AdbShellResponse probe = dadb.shell("echo dreamsync-ready");
                if (probe.getExitCode() != 0) {
                    throw new IOException("Watch connection check failed: " + probe.getAllOutput());
                }
                report(context, listener, "Step 2/4 - Connected. Uploading the watch app...");
                try {
                    dadb.push(apk, REMOTE_APK, 0644, apk.lastModified() / 1000L);
                    report(context, listener, "Step 3/4 - Upload complete. Installing on the watch...");
                    AdbShellResponse install = dadb.shell("pm install -r \"" + REMOTE_APK + "\"");
                    String installOutput = install.getAllOutput() == null
                            ? "" : install.getAllOutput().trim();
                    record(context, "pm install exit=" + install.getExitCode()
                            + ", output=" + installOutput);
                    if (!installOutput.contains("Success")) {
                        throw new IOException("Watch package installer: " + installOutput);
                    }
                } finally {
                    try {
                        dadb.shell("rm -f \"" + REMOTE_APK + "\"");
                    } catch (Exception ignored) {
                        // Best-effort cleanup. Preserve the real install error.
                    }
                }
                report(context, listener, "Step 4/4 - Done! Watch app installed. Open Dream Sync on the watch.");
            }
        } catch (Exception error) {
            String friendly = friendlyError(error, host);
            record(context, "FAILED: " + friendly);
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            record(context, trace.toString().trim());
            listener.onStatus(friendly);
        }
    }

    static String exportLog(Context context) {
        synchronized (LOG_LOCK) {
            File file = new File(context.getFilesDir(), LOG_FILE);
            if (!file.isFile()) return "No watch installation attempts have been logged yet.\n";
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException error) {
                return "Could not read installer log: " + error + "\n";
            }
        }
    }

    private static void report(Context context, StatusListener listener, String message) {
        record(context, message);
        listener.onStatus(message);
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception error) {
            return context.getPackageName() + " (version unavailable)";
        }
    }

    private static void record(Context context, String message) {
        synchronized (LOG_LOCK) {
            File file = new File(context.getFilesDir(), LOG_FILE);
            if (file.isFile() && file.length() > MAX_LOG_BYTES) {
                // Keep one previous diagnostic file instead of growing forever.
                File old = new File(context.getFilesDir(), LOG_FILE + ".old");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            String line = Instant.now() + " | " + message + System.lineSeparator();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Logging must never hide the real installer result.
            }
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
            return "Install timed out. Wake the watch, accept its debugging prompt, turn Debug over Wi-Fi off/on, confirm the new IP:port, then retry.";
        }
        if (raw.contains("refused")) {
            return "The watch refused the connection. Turn Debug over Wi-Fi off and on again, confirm the port, then retry.";
        }
        if (raw.contains("closed") || raw.contains("reset") || raw.contains("auth")) {
            return "The watch didn't accept debugging. On the watch, tap Allow on the prompt (tick \"Always allow\"), then retry.";
        }
        if (raw.contains("unreachable") || raw.contains("no route") || raw.contains("connect")) {
            return "No route to " + host + ". The watch may be on a different network or asleep - wake it, confirm the address, and retry.";
        }
        return "Install failed: " + (message == null ? error.getClass().getSimpleName() : message)
                + " - check the address, that Debug over Wi-Fi is on, and that both devices share a network.";
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
