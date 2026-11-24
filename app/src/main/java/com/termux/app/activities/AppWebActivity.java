package com.termux.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.app.EmbeddedApps;

/**
 * Web UI for the embedded apps (AnyModelBot, Ovis, web terminal).
 * The servers run inside Termux on loopback; this activity only displays them.
 *
 * Antigem-shape (v2): while the first-run setup is going, a setup screen shows
 * the download progress, the current step and the live setup log — the servers'
 * tabs only load once their server actually answers, so a tab never shows a
 * bare "connection refused" page. Ovis is not bundled in v2 (it needs
 * herdr-core); its tab says so.
 */
public final class AppWebActivity extends AppCompatActivity {

    private static final String OVIS_URL = "http://127.0.0.1:7317";
    private static final String TERMINAL_URL = "http://127.0.0.1:3602";

    private WebView mWebView;
    private TextView mStatusView;
    private LinearLayout mSetupView;
    private ProgressBar mSetupProgress;
    private android.widget.ScrollView mSetupLogScroll;
    private TextView mSetupLogView;
    private Handler mHandler;
    private Runnable mPollRunnable;
    private String mCurrentTab = "anymodelbot";
    private boolean mAnymodelbotLoaded = false;
    private boolean mTerminalLoaded = false;
    private boolean mOvisLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_web);

        mWebView = findViewById(R.id.web_view);
        mStatusView = findViewById(R.id.text_status);
        mSetupView = findViewById(R.id.setup_view);
        mSetupProgress = findViewById(R.id.setup_progress);
        mSetupLogScroll = findViewById(R.id.setup_log_scroll);
        mSetupLogView = findViewById(R.id.text_setup_log);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(false);
        mWebView.setWebViewClient(new WebViewClient());

        Button anymodelbotButton = findViewById(R.id.btn_anymodelbot);
        Button ovisButton = findViewById(R.id.btn_ovis);
        Button terminalButton = findViewById(R.id.btn_terminal);
        Button keyButton = findViewById(R.id.btn_key);

        anymodelbotButton.setOnClickListener(v -> showTab("anymodelbot"));
        ovisButton.setOnClickListener(v -> showTab("ovis"));
        terminalButton.setOnClickListener(v -> showTab("terminal"));
        keyButton.setOnClickListener(v -> showKeyDialog());

        mHandler = new Handler(Looper.getMainLooper());
        mPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                poll();
                mHandler.postDelayed(this, 1000);
            }
        };

        showTab("anymodelbot");
    }

    /** One poll tick: setup screen while setup runs, otherwise the current tab. */
    private void poll() {
        if (!EmbeddedApps.isSetupDone()) {
            mSetupView.setVisibility(View.VISIBLE);
            mWebView.setVisibility(View.GONE);
            mStatusView.setVisibility(View.VISIBLE);
            String status = EmbeddedApps.readStatus();
            mStatusView.setText(status != null ? status : "Setting up...");
            long[] progress = EmbeddedApps.readSetupProgress();
            if (progress != null && progress[1] > 0) {
                mSetupProgress.setVisibility(View.VISIBLE);
                mSetupProgress.setIndeterminate(false);
                mSetupProgress.setProgress((int) (progress[0] * 100 / progress[1]));
            } else if (progress != null) {
                mSetupProgress.setVisibility(View.VISIBLE);
                mSetupProgress.setIndeterminate(true);
            } else {
                mSetupProgress.setVisibility(View.GONE);
            }
            String log = EmbeddedApps.readSetupLogTail();
            if (log != null) {
                mSetupLogView.setText(log);
                mSetupLogScroll.post(() -> mSetupLogScroll.fullScroll(View.FOCUS_DOWN));
            }
            return;
        }

        mSetupView.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        if ("anymodelbot".equals(mCurrentTab)) {
            if (!mAnymodelbotLoaded) {
                String url = EmbeddedApps.readAnymodelbotUrl();
                if (url != null) {
                    mAnymodelbotLoaded = true;
                    mStatusView.setVisibility(View.GONE);
                    mWebView.loadUrl(url);
                } else {
                    mStatusView.setVisibility(View.VISIBLE);
                    String status = EmbeddedApps.readStatus();
                    mStatusView.setText(status != null ? "AnyModelBot: " + status : "AnyModelBot starting...");
                }
            }
        } else if ("terminal".equals(mCurrentTab)) {
            if (!mTerminalLoaded) {
                if (EmbeddedApps.isTerminalUp()) {
                    mTerminalLoaded = true;
                    mStatusView.setVisibility(View.GONE);
                    mWebView.loadUrl(TERMINAL_URL);
                } else {
                    mStatusView.setVisibility(View.VISIBLE);
                    mStatusView.setText("Terminal: starting...");
                }
            }
        } else if ("ovis".equals(mCurrentTab)) {
            if (!mOvisLoaded) {
                if (EmbeddedApps.isOvisUp()) {
                    mOvisLoaded = true;
                    mStatusView.setVisibility(View.GONE);
                    mWebView.loadUrl(OVIS_URL);
                } else {
                    mStatusView.setVisibility(View.VISIBLE);
                    mStatusView.setText("Ovis needs herdr-core — not bundled in this version.");
                }
            }
        }
    }

    private void showTab(String tab) {
        mCurrentTab = tab;
        if (!EmbeddedApps.isSetupDone()) {
            // Setup screen owns the view until setup finishes; the poll picks up the tab then.
            mSetupView.setVisibility(View.VISIBLE);
            mWebView.setVisibility(View.GONE);
            mStatusView.setVisibility(View.VISIBLE);
            mStatusView.setText("Setting up...");
            mWebView.loadUrl("about:blank");
            return;
        }
        if ("anymodelbot".equals(tab)) {
            String url = EmbeddedApps.readAnymodelbotUrl();
            if (url != null) {
                mAnymodelbotLoaded = true;
                mStatusView.setVisibility(View.GONE);
                mWebView.loadUrl(url);
            } else {
                mStatusView.setVisibility(View.VISIBLE);
                String status = EmbeddedApps.readStatus();
                mStatusView.setText(status != null ? "AnyModelBot: " + status : "AnyModelBot starting...");
                mWebView.loadUrl("about:blank");
            }
        } else if ("terminal".equals(tab)) {
            if (EmbeddedApps.isTerminalUp()) {
                mTerminalLoaded = true;
                mStatusView.setVisibility(View.GONE);
                mWebView.loadUrl(TERMINAL_URL);
            } else {
                mStatusView.setVisibility(View.VISIBLE);
                mStatusView.setText("Terminal: starting...");
                mWebView.loadUrl("about:blank");
            }
        } else if ("ovis".equals(tab)) {
            if (EmbeddedApps.isOvisUp()) {
                mOvisLoaded = true;
                mStatusView.setVisibility(View.GONE);
                mWebView.loadUrl(OVIS_URL);
            } else {
                mStatusView.setVisibility(View.VISIBLE);
                mStatusView.setText("Ovis needs herdr-core — not bundled in this version.");
                mWebView.loadUrl("about:blank");
            }
        }
    }

    private void showKeyDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Xkiro API key");
        String existingKey = EmbeddedApps.readKeyFile();
        if (existingKey != null) input.setText(existingKey);
        new AlertDialog.Builder(this)
            .setTitle("Model API key")
            .setMessage("Xkiro API key for AnyModelBot (free models). Stored only on this phone, never sent anywhere else.")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String key = input.getText().toString().trim();
                if (EmbeddedApps.writeKeyFile(key)) {
                    Toast.makeText(this, "Key saved, restarting servers...", Toast.LENGTH_LONG).show();
                    EmbeddedApps.restartServers(this);
                    mAnymodelbotLoaded = false;
                    mTerminalLoaded = false;
                    showTab("anymodelbot");
                } else {
                    Toast.makeText(this, "Failed to save key", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mPollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPollRunnable);
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
