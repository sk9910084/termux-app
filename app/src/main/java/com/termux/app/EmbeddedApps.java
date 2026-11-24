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
import java.nio.charset.StandardCharsets;

/**
 * Embedded apps (AnyModelBot + Ovis + web terminal) that run inside Termux.
 *
 * On first launch after the Termux bootstrap is ready, a setup script is run
 * in a background shell: it installs Node.js, clones AnyModelBot and Ovis,
 * builds them and starts their servers. On later launches the servers are
 * (re)started. The web UI is shown in {@link AppWebActivity}.
 */
public final class EmbeddedApps {

    private static final String LOG_TAG = "EmbeddedApps";

    public static final String EMBEDDED_DIR_PATH =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.anymodelbot";

    private static final String SETUP_SCRIPT_ASSET = "embedded-setup.sh";
    private static final String BRIDGE_ASSET = "terminal-bridge.js";

    private static final String SETUP_SCRIPT_PATH = EMBEDDED_DIR_PATH + "/embedded-setup.sh";
    private static final String START_SCRIPT_PATH = EMBEDDED_DIR_PATH + "/start-servers.sh";
    private static final String BRIDGE_PATH = EMBEDDED_DIR_PATH + "/terminal/bridge.js";
    private static final String SETUP_DONE_MARKER = EMBEDDED_DIR_PATH + "/setup-done";
    private static final String WEB_URL_FILE = EMBEDDED_DIR_PATH + "/web-url.txt";
    private static final String STATUS_FILE = EMBEDDED_DIR_PATH + "/status.txt";
    private static final String XIRO_KEY_FILE = EMBEDDED_DIR_PATH + "/xkiro-key.txt";

    /** Open the web UI at most once per app process launch. */
    private static boolean sWebViewOpened = false;

    private EmbeddedApps() {}

    /** Copy bundled assets, run setup (first run) or restart servers, open web UI. */
    public static void ensureEmbeddedApps(Activity activity) {
        try {
            new File(EMBEDDED_DIR_PATH).mkdirs();
            new File(EMBEDDED_DIR_PATH + "/terminal").mkdirs();
            copyAsset(activity, SETUP_SCRIPT_ASSET, new File(SETUP_SCRIPT_PATH), true);
            copyAsset(activity, BRIDGE_ASSET, new File(BRIDGE_PATH), false);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to copy embedded assets: " + e);
            return;
        }

        if (new File(SETUP_DONE_MARKER).exists()) {
            Logger.logInfo(LOG_TAG, "Setup already done, (re)starting servers");
            runBackgroundScript(activity, START_SCRIPT_PATH);
        } else {
            Logger.logInfo(LOG_TAG, "First run, starting embedded setup");
            runBackgroundScript(activity, SETUP_SCRIPT_PATH);
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

    /** (Re)start the servers (used after the API key is saved). */
    public static void restartServers(Context context) {
        runBackgroundScript(context, START_SCRIPT_PATH);
    }

    public static void openWebView(Activity activity) {
        ActivityUtils.startActivity(activity, new Intent(activity, AppWebActivity.class));
    }

    /** Full AnyModelBot web URL with token, or null while servers are starting. */
    public static String readAnymodelbotUrl() {
        return readSmallFile(new File(WEB_URL_FILE));
    }

    /** Current setup/server status text, or null. */
    public static String readStatus() {
        return readSmallFile(new File(STATUS_FILE));
    }

    public static File getXkiroKeyFile() {
        return new File(XIRO_KEY_FILE);
    }

    public static String readKeyFile() {
        return readSmallFile(new File(XIRO_KEY_FILE));
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
