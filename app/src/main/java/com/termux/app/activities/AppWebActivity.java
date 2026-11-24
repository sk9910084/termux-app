package com.termux.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.EmbeddedApps;

/**
 * Web UI for the embedded apps (AnyModelBot, Terminal, Ovis).
 * The servers run inside Termux on loopback; this activity only displays them.
 *
 * Antigem Dots-style UI: a top app bar (title, back, Stop, Show setup), tabs
 * for the three servers, and a setup screen with a Keys card (Xkiro key,
 * endpoint, model) and a Status card (step, download progress, live log).
 * The WebView fills the screen once its server is running; a tab never shows
 * a bare "connection refused" page. Ovis is not bundled in v2 (it needs
 * herdr-core); its tab says so.
 */
public final class AppWebActivity extends AppCompatActivity {

    private static final String OVIS_URL = "http://127.0.0.1:7317";
    private static final String TERMINAL_URL = "http://127.0.0.1:3602";

    private WebView mWebView;
    private ScrollView mSetupView;
    private TextView mStatusView;
    private ProgressBar mSetupProgress;
    private TextView mSetupLogView;
    private TextInputEditText mKeyInput;
    private TextInputEditText mBaseUrlInput;
    private TextInputEditText mModelInput;
    private Button mStartButton;
    private Handler mHandler;
    private Runnable mPollRunnable;
    private String mCurrentTab = "anymodelbot";
    private boolean mAnymodelbotLoaded = false;
    private boolean mTerminalLoaded = false;
    private boolean mOvisLoaded = false;
    private boolean mShowSetup = false;
    private boolean mKeysLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_web);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        mWebView = findViewById(R.id.web_view);
        mSetupView = findViewById(R.id.setup_view);
        mStatusView = findViewById(R.id.text_status);
        mSetupProgress = findViewById(R.id.setup_progress);
        mSetupLogView = findViewById(R.id.text_setup_log);
        mKeyInput = findViewById(R.id.input_key);
        mBaseUrlInput = findViewById(R.id.input_base_url);
        mModelInput = findViewById(R.id.input_model);
        mStartButton = findViewById(R.id.btn_start_restart);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(false);
        mWebView.setWebViewClient(new WebViewClient());

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                showTab(pos == 1 ? "terminal" : pos == 2 ? "ovis" : "anymodelbot");
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        findViewById(R.id.btn_save_keys).setOnClickListener(v -> saveKeys());
        mStartButton.setOnClickListener(v -> {
            if (EmbeddedApps.isSetupDone()) {
                EmbeddedApps.restartServers(this);
                mAnymodelbotLoaded = false;
                mTerminalLoaded = false;
                mShowSetup = false;
                Toast.makeText(this, "Restarting servers...", Toast.LENGTH_SHORT).show();
            } else {
                EmbeddedApps.startSetup(this);
                Toast.makeText(this, "Starting setup...", Toast.LENGTH_SHORT).show();
            }
        });

        mHandler = new Handler(Looper.getMainLooper());
        mPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                poll();
                mHandler.postDelayed(this, 1000);
            }
        };

        loadKeysIntoFields();
        showTab("anymodelbot");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.app_web_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_stop) {
            EmbeddedApps.stopServers(this);
            mAnymodelbotLoaded = false;
            mTerminalLoaded = false;
            mShowSetup = true;
            Toast.makeText(this, "Servers stopped.", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_show_setup) {
            mShowSetup = true;
            showSetupView();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Fill the Keys card from the stored files (once, so typing is not clobbered). */
    private void loadKeysIntoFields() {
        if (mKeysLoaded) return;
        mKeysLoaded = true;
        String key = EmbeddedApps.readKeyFile();
        if (key != null) mKeyInput.setText(key);
        mBaseUrlInput.setText(EmbeddedApps.readBaseUrl());
        mModelInput.setText(EmbeddedApps.readModel());
    }

    private void saveKeys() {
        String key = mKeyInput.getText() == null ? "" : mKeyInput.getText().toString().trim();
        String baseUrl = mBaseUrlInput.getText() == null ? "" : mBaseUrlInput.getText().toString().trim();
        String model = mModelInput.getText() == null ? "" : mModelInput.getText().toString().trim();
        boolean ok = EmbeddedApps.writeKeyFile(key)
            && EmbeddedApps.writeBaseUrl(baseUrl)
            && EmbeddedApps.writeModel(model);
        if (ok) {
            Toast.makeText(this, "Keys saved.", Toast.LENGTH_SHORT).show();
            if (EmbeddedApps.isSetupDone()) {
                EmbeddedApps.restartServers(this);
                mAnymodelbotLoaded = false;
                mTerminalLoaded = false;
                mShowSetup = false;
            }
        } else {
            Toast.makeText(this, "Failed to save keys", Toast.LENGTH_LONG).show();
        }
    }

    /** One poll tick: setup screen while setup runs (or is shown), otherwise the tab. */
    private void poll() {
        if (!EmbeddedApps.isSetupDone() || mShowSetup) {
            showSetupView();
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
                mSetupView.post(() -> mSetupView.fullScroll(View.FOCUS_DOWN));
            }
            mStartButton.setText(EmbeddedApps.isSetupDone() ? "Restart" : "Start");
            return;
        }

        mSetupView.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        if ("anymodelbot".equals(mCurrentTab)) {
            if (!mAnymodelbotLoaded) {
                String url = EmbeddedApps.readAnymodelbotUrl();
                if (url != null) {
                    mAnymodelbotLoaded = true;
                    mWebView.loadUrl(url);
                }
            }
        } else if ("terminal".equals(mCurrentTab)) {
            if (!mTerminalLoaded && EmbeddedApps.isTerminalUp()) {
                mTerminalLoaded = true;
                mWebView.loadUrl(TERMINAL_URL);
            }
        } else if ("ovis".equals(mCurrentTab)) {
            if (!mOvisLoaded && EmbeddedApps.isOvisUp()) {
                mOvisLoaded = true;
                mWebView.loadUrl(OVIS_URL);
            }
        }
    }

    private void showSetupView() {
        mSetupView.setVisibility(View.VISIBLE);
        mWebView.setVisibility(View.GONE);
    }

    private void showTab(String tab) {
        mCurrentTab = tab;
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle("terminal".equals(tab) ? "Terminal"
                : "ovis".equals(tab) ? "Ovis" : "AnyModelBot");
        }
        if (!EmbeddedApps.isSetupDone() || mShowSetup) {
            showSetupView();
            mWebView.loadUrl("about:blank");
            return;
        }
        mSetupView.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        if ("anymodelbot".equals(tab)) {
            String url = EmbeddedApps.readAnymodelbotUrl();
            if (url != null) {
                mAnymodelbotLoaded = true;
                mWebView.loadUrl(url);
            } else {
                String status = EmbeddedApps.readStatus();
                loadMessagePage("AnyModelBot is starting...",
                    status != null ? status : "The server is starting. This takes a minute on first run.");
            }
        } else if ("terminal".equals(tab)) {
            if (EmbeddedApps.isTerminalUp()) {
                mTerminalLoaded = true;
                mWebView.loadUrl(TERMINAL_URL);
            } else {
                loadMessagePage("Terminal is starting...", "The terminal bridge starts after setup finishes.");
            }
        } else if ("ovis".equals(tab)) {
            if (EmbeddedApps.isOvisUp()) {
                mOvisLoaded = true;
                mWebView.loadUrl(OVIS_URL);
            } else {
                loadMessagePage("Ovis is not available yet.",
                    "The Ovis server needs herdr-core, which is not bundled in this version.");
            }
        }
    }

    /** Small dark message page in the WebView area (never a raw browser error page). */
    private void loadMessagePage(String title, String body) {
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<style>body{background:#121212;color:#e0e0e0;font-family:sans-serif;padding:32px 16px}"
            + "h2{font-size:18px}p{font-size:14px;color:#9e9e9e}</style></head><body>"
            + "<h2>" + title + "</h2><p>" + body + "</p></body></html>";
        mWebView.loadData(html, "text/html", "UTF-8");
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
        if (mSetupView.getVisibility() == View.VISIBLE && EmbeddedApps.isSetupDone()) {
            mShowSetup = false;
            showTab(mCurrentTab);
            return;
        }
        if (mWebView.getVisibility() == View.VISIBLE && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
