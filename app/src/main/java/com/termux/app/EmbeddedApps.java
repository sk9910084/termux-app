package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.activities.AppWebActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Embedded apps (AnyModelBot + web terminal) that run inside Termux.
 *
 * Antigem-shape (v2): the AnyModelBot server is a prebuilt bundle made on a
 * computer (src + ui + dist catalog + node_modules, tsx included) and
 * published as a GitHub release. On first run this class downloads the bundle
 * once, with visible progress and a SHA-256 check (like AntiGem's Dots
 * bundle), then a setup script extracts it, installs Node.js and starts the
 * servers. No npm install happens on the phone. The WebView shows the setup
 * progress + log while that runs, then the app tabs.
 *
 * Ovis is NOT set up in v2: its server is only a bridge to herdr-core, and
 * no herdr-core binary is bundled yet, so the server cannot run. The Ovis tab
 * says so instead of showing a dead page.
 */
public final class EmbeddedApps {

    private static final String LOG_TAG = "EmbeddedApps";

    public static final String EMBEDDED_DIR_PATH =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.anymodelbot";

    /** Prebuilt AnyModelBot bundle (release on the fork). */
    private static final String BUNDLE_URL =
        "https://github.com/sk9910084/termux-app/releases/download/anymodelbot-bundle-v0.1.47/anymodelbot-bundle-v0.1.47.tgz";
    private static final String BUNDLE_SHA256 =
        "f61abe3f42da9b96d08a7d1cba22e6202700c35147fbe1e99253443a2831c515";
    private static final String BUNDLE_FILE = "anymodelbot-bundle-v0.1.47.tgz";

    private static final String SETUP_SCRIPT_ASSET = "embedded-setup.sh";
    private static final String BRIDGE_ASSET = "terminal-bridge.js";
    private static final String STOP_SCRIPT_ASSET = "stop-servers.sh";

    private static final String SETUP_SCRIPT_PATH = EMBEDDED_DIR_PATH + "/embedded-setup.sh";
    private static final String START_SCRIPT_PATH = EMBEDDED_DIR_PATH + "/start-servers.sh";
    private static final String STOP_SCRIPT_PATH = EMBEDDED_DIR_PATH + "/stop-servers.sh";
    private static final String BRIDGE_PATH = EMBEDDED_DIR_PATH + "/terminal/bridge.js";
    private static final String SETUP_DONE_MARKER = EMBEDDED_DIR_PATH + "/setup-done";
    private static final String SETUP_LOG = EMBEDDED_DIR_PATH + "/setup.log";
    private static final String SETUP_PROGRESS = EMBEDDED_DIR_PATH + "/setup-progress.txt";
    private static final String WEB_URL_FILE = EMBEDDED_DIR_PATH + "/web-url.txt";
    private static final String STATUS_FILE = EMBEDDED_DIR_PATH + "/status.txt";
    private static final String TERMINAL_UP_FILE = EMBEDDED_DIR_PATH + "/terminal-up";
    private static final String OVIS_UP_FILE = EMBEDDED_DIR_PATH + "/ovis-up";
    private static final String XIRO_KEY_FILE = EMBEDDED_DIR_PATH + "/xkiro-key.txt";
    private static final String XIRO_BASE_URL_FILE = EMBEDDED_DIR_PATH + "/xkiro-base-url.txt";
    private static final String XIRO_MODEL_FILE = EMBEDDED_DIR_PATH + "/xkiro-model.txt";

    /** Default model endpoint + model (Xkiro free), used when no file is stored yet. */
    public static final String DEFAULT_BASE_URL = "https://api.xkiro.com/v1";
    public static final String DEFAULT_MODEL = "mistralai/mistral-small-2603";

    /** Open the web UI at most once per app process launch. */
    private static boolean sWebViewOpened = false;
    /** True while a bundle download / setup run is in flight (do not start a second one). */
    private static boolean sSetupRunning = false;

    private EmbeddedApps() {}

    /** Copy bundled assets, run setup (first run) or restart servers, open web UI. */
    public static void ensureEmbeddedApps(Activity activity) {
        try {
            new File(EMBEDDED_DIR_PATH).mkdirs();
            new File(EMBEDDED_DIR_PATH + "/terminal").mkdirs();
            copyAsset(activity, SETUP_SCRIPT_ASSET, new File(SETUP_SCRIPT_PATH), true);
            copyAsset(activity, STOP_SCRIPT_ASSET, new File(STOP_SCRIPT_PATH), true);
            copyAsset(activity, BRIDGE_ASSET, new File(BRIDGE_PATH), false);
            copyAsset(activity, "terminal/jquery.min.js",
                new File(EMBEDDED_DIR_PATH + "/terminal/jquery.min.js"), false);
            copyAsset(activity, "terminal/jquery.terminal.min.js",
                new File(EMBEDDED_DIR_PATH + "/terminal/jquery.terminal.min.js"), false);
            copyAsset(activity, "terminal/jquery.terminal.min.css",
                new File(EMBEDDED_DIR_PATH + "/terminal/jquery.terminal.min.css"), false);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to copy embedded assets: " + e);
            return;
        }

        if (new File(SETUP_DONE_MARKER).exists()) {
            Logger.logInfo(LOG_TAG, "Setup already done, (re)starting servers");
            runBackgroundScript(activity, START_SCRIPT_PATH);
        } else {
            Logger.logInfo(LOG_TAG, "First run, downloading bundle then running setup");
            startSetup(activity.getApplicationContext());
        }

        if (!sWebViewOpened) {
            sWebViewOpened = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    openWebView(activity);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to open web UI: " + e);
                }
            }, 800);
        }
    }

    /** Start (or restart) the first-run setup: download the bundle, then run setup. */
    public static synchronized void startSetup(Context context) {
        if (sSetupRunning) {
            Logger.logInfo(LOG_TAG, "Setup already running, ignoring second start");
            return;
        }
        sSetupRunning = true;
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                downloadBundleThenSetup(appContext);
            } finally {
                synchronized (EmbeddedApps.class) {
                    sSetupRunning = false;
                }
            }
        }).start();
    }

    /** Stop the servers (used by the toolbar Stop action). */
    public static void stopServers(Context context) {
        runBackgroundScript(context, STOP_SCRIPT_PATH);
    }

    /** Download the prebuilt bundle (with progress + SHA-256 check), then run setup. */
    private static void downloadBundleThenSetup(Context context) {
        File bundle = new File(EMBEDDED_DIR_PATH, BUNDLE_FILE);
        try {
            if (bundle.exists() && sha256Of(bundle).equals(BUNDLE_SHA256)) {
                appendLog("Bundle already downloaded and verified.");
            } else {
                if (bundle.exists()) bundle.delete();
                appendLog("Downloading AnyModelBot bundle (~96 MB, one time)...");
                writeStatus("downloading bundle...");
                downloadWithProgress(BUNDLE_URL, bundle);
                String actual = sha256Of(bundle);
                if (!actual.equals(BUNDLE_SHA256)) {
                    bundle.delete();
                    throw new IOException("bundle checksum mismatch (got " + actual + ")");
                }
                appendLog("Bundle downloaded and verified.");
            }
            writeProgress(-1, -1); // download done
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Bundle download failed: " + e);
            appendLog("Bundle download failed: " + e.getMessage()
                + " — setup will try the source build instead.");
            writeStatus("error: bundle download failed, trying source build");
        }
        runBackgroundScript(context, SETUP_SCRIPT_PATH);
    }

    /** Download a URL to a file, writing "downloaded total" bytes to the progress file. */
    private static void downloadWithProgress(String urlString, File dest) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            long downloaded = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    writeProgress(downloaded, total);
                }
            }
            appendLog("Downloaded " + downloaded + " bytes"
                + (total > 0 ? " of " + total : "") + ".");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sha256Of(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed: " + e.getMessage());
        }
    }

    private static synchronized void appendLog(String line) {
        try (FileOutputStream out = new FileOutputStream(new File(SETUP_LOG), true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void writeStatus(String status) {
        try (FileOutputStream out = new FileOutputStream(new File(STATUS_FILE))) {
            out.write(status.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Progress file holds "downloaded total" (bytes), or "-1 -1" when the download is done. */
    private static synchronized void writeProgress(long downloaded, long total) {
        try (FileOutputStream out = new FileOutputStream(new File(SETUP_PROGRESS))) {
            out.write((downloaded + " " + total).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** (Re)start the servers (used after the API key is saved). */
    public static void restartServers(Context context) {
        runBackgroundScript(context, START_SCRIPT_PATH);
    }

    public static void openWebView(Activity activity) {
        ActivityUtils.startActivity(activity, new Intent(activity, AppWebActivity.class));
    }

    /** True once the first-run setup finished (marker file exists). */
    public static boolean isSetupDone() {
        return new File(SETUP_DONE_MARKER).exists();
    }

    /** Full AnyModelBot web URL with token, or null while servers are starting. */
    public static String readAnymodelbotUrl() {
        return readSmallFile(new File(WEB_URL_FILE));
    }

    /** Current setup/server status text, or null. */
    public static String readStatus() {
        return readSmallFile(new File(STATUS_FILE));
    }

    /** Download progress as {downloaded, total} bytes, or null when no download is running. */
    public static long[] readSetupProgress() {
        String text = readSmallFile(new File(SETUP_PROGRESS));
        if (text == null) return null;
        String[] parts = text.split(" ");
        if (parts.length != 2) return null;
        try {
            long downloaded = Long.parseLong(parts[0]);
            long total = Long.parseLong(parts[1]);
            if (downloaded < 0) return null; // download done
            return new long[]{downloaded, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Last ~4000 characters of the setup log, or null. */
    public static String readSetupLogTail() {
        File log = new File(SETUP_LOG);
        try {
            if (!log.exists()) return null;
            long len = log.length();
            long start = Math.max(0, len - 4000);
            try (FileInputStream in = new FileInputStream(log)) {
                if (in.skip(start) < start) return null;
                byte[] buf = new byte[(int) (len - start)];
                int read = 0;
                int n;
                while (read < buf.length && (n = in.read(buf, read, buf.length - read)) != -1) read += n;
                String text = new String(buf, 0, read, StandardCharsets.UTF_8).trim();
                return text.isEmpty() ? null : text;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the terminal bridge answered its health check. */
    public static boolean isTerminalUp() {
        return new File(TERMINAL_UP_FILE).exists();
    }

    /** True when the Ovis server answered its health check (not bundled in v2). */
    public static boolean isOvisUp() {
        return new File(OVIS_UP_FILE).exists();
    }

    public static File getXkiroKeyFile() {
        return new File(XIRO_KEY_FILE);
    }

    public static String readKeyFile() {
        return readSmallFile(new File(XIRO_KEY_FILE));
    }

    /** Stored model endpoint, or the default when nothing is stored yet. */
    public static String readBaseUrl() {
        String value = readSmallFile(new File(XIRO_BASE_URL_FILE));
        return value != null ? value : DEFAULT_BASE_URL;
    }

    /** Stored model, or the default when nothing is stored yet. */
    public static String readModel() {
        String value = readSmallFile(new File(XIRO_MODEL_FILE));
        return value != null ? value : DEFAULT_MODEL;
    }

    private static boolean writeTextFile(File file, String value) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(false, false);
        return true;
    }

    public static boolean writeBaseUrl(String baseUrl) {
        return writeTextFile(new File(XIRO_BASE_URL_FILE), baseUrl);
    }

    public static boolean writeModel(String model) {
        return writeTextFile(new File(XIRO_MODEL_FILE), model);
    }

    public static boolean writeKeyFile(String key) {
        File file = new File(XIRO_KEY_FILE);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(key.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(false, false);
        return true;
    }

    private static String readSmallFile(File file) {
        try {
            if (!file.exists()) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                String text = sb.toString().trim();
                return text.isEmpty() ? null : text;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyAsset(Context context, String assetName, File dest, boolean executable) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        dest.setExecutable(executable, true);
        dest.setReadable(true, true);
        dest.setWritable(true, true);
    }

    /** Run a script in a background (AppShell) shell via TermuxService. */
    private static void runBackgroundScript(Context context, String scriptPath) {
        try {
            Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
                new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(scriptPath).build());
            execIntent.setClass(context, TermuxService.class);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER, Runner.APP_SHELL.getName());
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Embedded apps");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(execIntent);
            } else {
                context.startService(execIntent);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to start background script " + scriptPath + ": " + e);
        }
    }
}
