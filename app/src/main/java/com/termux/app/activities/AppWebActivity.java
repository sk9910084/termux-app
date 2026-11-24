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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.EmbeddedApps;

/**
 * Web UI for the embedded apps (AnyModelBot, Ovis, web terminal).
 * The servers run inside Termux on loopback; this activity only displays them.
 */
public final class AppWebActivity extends AppCompatActivity {

    private static final String OVIS_URL = "http://127.0.0.1:7317";
    private static final String TERMINAL_URL = "http://127.0.0.1:3602";

    private WebView mWebView;
    private TextView mStatusView;
    private Handler mHandler;
    private Runnable mPollRunnable;
    private String mCurrentTab = "anymodelbot";
    private boolean mAnymodelbotLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_web);

        mWebView = findViewById(R.id.web_view);
        mStatusView = findViewById(R.id.text_status);

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
                if ("anymodelbot".equals(mCurrentTab) && !mAnymodelbotLoaded) {
                    String url = EmbeddedApps.readAnymodelbotUrl();
                    if (url != null) {
                        mAnymodelbotLoaded = true;
                        mStatusView.setVisibility(View.GONE);
                        mWebView.loadUrl(url);
                    } else {
                        String status = EmbeddedApps.readStatus();
                        mStatusView.setText(status != null ? "AnyModelBot: " + status : "AnyModelBot starting...");
                    }
                }
                mHandler.postDelayed(this, 2000);
            }
        };

        showTab("anymodelbot");
    }

    private void showTab(String tab) {
        mCurrentTab = tab;
        if ("anymodelbot".equals(tab)) {
            String url = EmbeddedApps.readAnymodelbotUrl();
            if (url != null) {
                mAnymodelbotLoaded = true;
                mStatusView.setVisibility(View.GONE);
                mWebView.loadUrl(url);
            } else {
                String status = EmbeddedApps.readStatus();
                mStatusView.setVisibility(View.VISIBLE);
                mStatusView.setText(status != null ? "AnyModelBot: " + status : "AnyModelBot starting...");
                mWebView.loadUrl("about:blank");
            }
        } else if ("ovis".equals(tab)) {
            mStatusView.setVisibility(View.GONE);
            mWebView.loadUrl(OVIS_URL);
        } else {
            mStatusView.setVisibility(View.GONE);
            mWebView.loadUrl(TERMINAL_URL);
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
