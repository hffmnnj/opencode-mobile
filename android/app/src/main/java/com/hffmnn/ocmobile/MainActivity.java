package com.hffmnn.ocmobile;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "OCMobile";
    private static final String PREFS_NAME = "oc_settings";
    private static final String KEY_URL = "server_url";
    private static final String KEY_USER = "username";
    private static final String KEY_PASS = "password";
    private static final String KEY_ZOOM = "zoom_level";
    private static final String DEFAULT_URL = "http://localhost:4096";
    private static final int DEFAULT_ZOOM = 100;
    private static final int PERM_REQUEST = 1;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout errorView;
    private TextView errorText;
    private MaterialButton btnSettingsFab;
    private MaterialButton btnRetry;
    private MaterialButton btnSettings;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        errorText = findViewById(R.id.error_text);
        btnSettingsFab = findViewById(R.id.btn_settings_fab);
        btnRetry = findViewById(R.id.btn_retry);
        btnSettings = findViewById(R.id.btn_settings);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; Pixel 9 Pro Fold) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // File picker launcher
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = result.getData().getClipData().getItemAt(i).getUri();
                        }
                    } else if (result.getData().getData() != null) {
                        results = new Uri[]{result.getData().getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        );

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    filePickerLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    Log.e(TAG, "file chooser failed: " + e.getMessage());
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "page started: " + url);
                showLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "page finished: " + url);
                hideLoading();
                view.evaluateJavascript(
                    "(function(){" +
                    "  if(!document.querySelector('meta[name=viewport]')){" +
                    "    var m=document.createElement('meta');" +
                    "    m.name='viewport';" +
                    "    m.content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';" +
                    "    document.head.appendChild(m);" +
                    "  } else {" +
                    "    document.querySelector('meta[name=viewport]').content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';" +
                    "  }" +
                    "  document.body.style.overscrollBehavior='none';" +
                    "})();", null);
            }

            @Override
            public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error) {
                Log.e(TAG, "error: " + error.getDescription() + " for " + request.getUrl());
                if (request.isForMainFrame()) {
                    showError("Failed to load: " + error.getDescription());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                return false;
            }
        });

        btnRetry.setOnClickListener(v -> connect());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnSettingsFab.setOnClickListener(v -> showSettingsDialog());

        requestMediaPermissions();
        applyZoom();
        connect();
    }

    private void requestMediaPermissions() {
        String[] perms;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.CAMERA
            };
        } else {
            perms = new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CAMERA
            };
        }

        boolean needs = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needs = true;
                break;
            }
        }
        if (needs) {
            requestPermissions(perms, PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            for (int i = 0; i < permissions.length; i++) {
                Log.d(TAG, "permission " + permissions[i] + ": " + (grantResults[i] == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
            }
        }
    }

    private void connect() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = prefs.getString(KEY_URL, DEFAULT_URL);
        Log.d(TAG, "connecting to: " + url);

        if (url.isEmpty()) {
            showSettingsDialog();
            return;
        }

        showLoading();

        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);

                String username = prefs.getString(KEY_USER, "");
                String password = prefs.getString(KEY_PASS, "");
                if (!username.isEmpty() && !password.isEmpty()) {
                    String creds = Base64.encodeToString((username + ":" + password).getBytes(), Base64.NO_WRAP);
                    conn.setRequestProperty("Authorization", "Basic " + creds);
                }

                int code = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "server response: " + code);

                mainHandler.post(() -> {
                    if (code >= 200 && code < 400) {
                        Log.d(TAG, "loading webview: " + url);
                        webView.loadUrl(url);
                    } else {
                        showError("Server returned HTTP " + code);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "connection failed: " + e.getMessage(), e);
                mainHandler.post(() -> showError("Server unreachable: " + e.getMessage()));
            }
        });
    }

    private void showLoading() {
        webView.setVisibility(View.INVISIBLE);
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        fadeIn(webView);
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.INVISIBLE);
        errorView.setVisibility(View.VISIBLE);
        errorText.setText(msg);
        fadeIn(errorView);
    }

    private void fadeIn(View view) {
        AlphaAnimation anim = new AlphaAnimation(0f, 1f);
        anim.setDuration(200);
        view.startAnimation(anim);
    }

    private void applyZoom() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int zoom = prefs.getInt(KEY_ZOOM, DEFAULT_ZOOM);
        webView.getSettings().setTextZoom(zoom);
        Log.d(TAG, "zoom set to: " + zoom + "%");
    }

    private void showSettingsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String currentUrl = prefs.getString(KEY_URL, DEFAULT_URL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText urlInput = new EditText(this);
        urlInput.setHint("Server URL (e.g. http://localhost:4096)");
        urlInput.setText(currentUrl);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(urlInput);

        EditText userInput = new EditText(this);
        userInput.setHint("Username (optional)");
        userInput.setText(prefs.getString(KEY_USER, ""));
        layout.addView(userInput);

        EditText passInput = new EditText(this);
        passInput.setHint("Password (optional)");
        passInput.setText(prefs.getString(KEY_PASS, ""));
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passInput);

        int currentZoom = prefs.getInt(KEY_ZOOM, DEFAULT_ZOOM);
        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("Zoom: " + currentZoom + "%");
        zoomLabel.setTextColor(getResources().getColor(android.R.color.white, null));
        zoomLabel.setPadding(0, 24, 0, 8);
        layout.addView(zoomLabel);

        SeekBar zoomBar = new SeekBar(this);
        zoomBar.setMax(150);
        zoomBar.setProgress(currentZoom - 50);
        zoomBar.setPadding(0, 0, 0, 16);
        layout.addView(zoomBar);

        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int zoom = progress + 50;
                zoomLabel.setText("Zoom: " + zoom + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        new MaterialAlertDialogBuilder(this)
            .setTitle("Server Settings")
            .setView(layout)
            .setPositiveButton("Save", (dialog, which) -> {
                String newUrl = urlInput.getText().toString().trim();
                if (!newUrl.isEmpty() && !newUrl.contains("://")) {
                    newUrl = "http://" + newUrl;
                }
                int zoom = zoomBar.getProgress() + 50;
                prefs.edit()
                    .putString(KEY_URL, newUrl)
                    .putString(KEY_USER, userInput.getText().toString().trim())
                    .putString(KEY_PASS, passInput.getText().toString())
                    .putInt(KEY_ZOOM, zoom)
                    .apply();
                applyZoom();
                if (!newUrl.equals(currentUrl)) {
                    connect();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
