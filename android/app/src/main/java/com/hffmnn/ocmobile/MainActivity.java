package com.hffmnn.ocmobile;

import android.annotation.SuppressLint;
import android.content.Context;
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

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.widget.FrameLayout;

public class MainActivity extends AppCompatActivity {

    public static class ClipboardBridge {
        private final Context context;
        public ClipboardBridge(Context ctx) { this.context = ctx; }
        @android.webkit.JavascriptInterface
        public void copy(String text) {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Copied Text", text);
            clipboard.setPrimaryClip(clip);
        }
        @android.webkit.JavascriptInterface
        public String paste() {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
                android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                if (item.getText() != null) return item.getText().toString();
            }
            return "";
        }
    }

    private static final String TAG = "OCMobile";
    private static final String PREFS_NAME = "oc_settings";
    private static final String KEY_URL = "server_url";
    private static final String KEY_USER = "username";
    private static final String KEY_PASS = "password";
    private static final String KEY_OPENAI = "openai_key";
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

    // Voice input UI
    private MaterialButton btnMicFab;
    private FrameLayout recordingOverlay;
    private TextView recordingStatus;
    private TextView recordingTimer;
    private MaterialButton btnStopRecording;
    private View pulseRing1;
    private View pulseRing2;
    private android.animation.ObjectAnimator pulseAnim1;
    private android.animation.ObjectAnimator pulseAnim2;

    // Voice input state
    private boolean isRecording = false;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private File recordingFile;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordingStartTime;
    private Runnable timerRunnable;

    // Vosk STT
    private Model voskModel;
    private Recognizer voskRecognizer;
    private boolean voskModelReady = false;
    private static final String VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String VOSK_MODEL_DIR = "vosk-model-small-en-us-0.15";

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean wasPaused = false;
    private long pauseTimestamp = 0;
    private final int[] domHashAtPause = new int[1];
    private int resumeGeneration = 0;

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

        // Clipboard bridge for copy-to-clipboard support in WebView
        webView.addJavascriptInterface(new ClipboardBridge(this), "AndroidClipboard");

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
                    "  if (window.AndroidClipboard) {" +
                    "    if (!navigator.clipboard) navigator.clipboard = {};" +
                    "    navigator.clipboard.writeText = function(text) {" +
                    "      return new Promise(function(resolve, reject) {" +
                    "        try { window.AndroidClipboard.copy(String(text)); resolve(); } catch(e) { reject(e); }" +
                    "      });" +
                    "    };" +
                    "    navigator.clipboard.readText = function() {" +
                    "      return new Promise(function(resolve, reject) {" +
                    "        try { resolve(window.AndroidClipboard.paste() || ''); } catch(e) { reject(e); }" +
                    "      });" +
                    "    };" +
                    "  }" +
                    "  if (!window.__ocEnterIntercept) {" +
                    "    window.__ocEnterIntercept = true;" +
                    "    document.addEventListener('keydown', function(e){" +
                    "      if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.isComposing) {" +
                    "        var el = document.activeElement;" +
                    "        if (!el) return;" +
                    "        var tag = el.tagName;" +
                    "        var isInput = tag === 'TEXTAREA' || tag === 'INPUT' || el.isContentEditable;" +
                    "        if (!isInput) return;" +
                    "        e.preventDefault();" +
                    "        e.stopImmediatePropagation();" +
                    "        if (tag === 'TEXTAREA' || tag === 'INPUT') {" +
                    "          var start = el.selectionStart || 0;" +
                    "          var end = el.selectionEnd || 0;" +
                    "          var before = el.value.substring(0, start);" +
                    "          var after = el.value.substring(end);" +
                    "          el.value = before + '\\n' + after;" +
                    "          var newPos = start + 1;" +
                    "          el.dispatchEvent(new Event('input', { bubbles: true }));" +
                    "          requestAnimationFrame(function(){" +
                    "            el.selectionStart = el.selectionEnd = newPos;" +
                    "          });" +
                    "        } else if (el.isContentEditable) {" +
                    "          var sel = window.getSelection();" +
                    "          if (!sel || !sel.rangeCount) return;" +
                    "          var range = sel.getRangeAt(0);" +
                    "          range.deleteContents();" +
                    "          var br = document.createTextNode('\\n');" +
                    "          range.insertNode(br);" +
                    "          range.setStartAfter(br);" +
                    "          range.setEndAfter(br);" +
                    "          sel.removeAllRanges();" +
                    "          sel.addRange(range);" +
                    "          el.dispatchEvent(new Event('input', { bubbles: true }));" +
                    "        }" +
                    "      }" +
                    "    }, true);" +
                    "  }" +
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
                Uri uri = request.getUrl();
                String urlStr = uri.toString();

                // Always let the server URL and its subpaths load in the WebView
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String serverUrl = prefs.getString(KEY_URL, DEFAULT_URL);
                String serverHost = Uri.parse(serverUrl).getHost();
                String reqHost = uri.getHost();

                if (serverHost != null && reqHost != null && serverHost.equalsIgnoreCase(reqHost)) {
                    return false; // load internally
                }

                // Open everything else externally
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "no handler for URL: " + urlStr);
                }
                return true;
            }
        });

        btnRetry.setOnClickListener(v -> connect());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnSettingsFab.setOnClickListener(v -> showSettingsDialog());

        // Voice input UI
        // Voice input UI
        btnMicFab = findViewById(R.id.btn_mic_fab);
        btnSettingsFab = findViewById(R.id.btn_settings_fab);
        recordingOverlay = findViewById(R.id.recording_overlay);
        recordingStatus = findViewById(R.id.recording_status);
        recordingTimer = findViewById(R.id.recording_timer);
        btnStopRecording = findViewById(R.id.btn_stop_recording);
        pulseRing1 = findViewById(R.id.pulse_ring_1);
        pulseRing2 = findViewById(R.id.pulse_ring_2);

        btnMicFab.setOnClickListener(v -> onMicButtonClicked());
        btnSettingsFab.setOnClickListener(v -> showSettingsDialog());
        btnStopRecording.setOnClickListener(v -> stopRecordingAndTranscribe());

        // Initialize Vosk model in background
        initVoskModel();

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
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            };
        } else {
            perms = new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
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

        EditText openaiInput = new EditText(this);
        openaiInput.setHint("OpenAI API Key (optional, for Whisper)");
        openaiInput.setText(prefs.getString(KEY_OPENAI, ""));
        openaiInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(openaiInput);

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
                    .putString(KEY_OPENAI, openaiInput.getText().toString().trim())
                    .putInt(KEY_ZOOM, zoom)
                    .apply();
                applyZoom();
                if (!newUrl.equals(currentUrl)) {
                    connect();
                }
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Refresh", (dialog, which) -> {
                Log.d(TAG, "manual refresh triggered from settings");
                connect();
            })
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
    protected void onPause() {
        super.onPause();
        wasPaused = true;
        pauseTimestamp = System.currentTimeMillis();
        resumeGeneration++; // invalidate any pending health check from a previous resume
        if (webView != null) {
            webView.pauseTimers();
        }
        Log.d(TAG, "onPause — timers paused, gen=" + resumeGeneration);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        if (wasPaused) {
            wasPaused = false;
            final int thisGen = ++resumeGeneration;
            long backgroundMs = System.currentTimeMillis() - pauseTimestamp;
            Log.d(TAG, "onResume — backgrounded for " + backgroundMs + "ms, gen=" + thisGen);

            if (backgroundMs < 2000) {
                Log.d(TAG, "short background (<2s), skipping reconnect logic");
                return;
            }

            if (webView.getVisibility() == View.VISIBLE && webView.getUrl() != null) {
                webView.evaluateJavascript(
                    "document.body.innerHTML.length",
                    value -> {
                        // Ignore if a newer onPause/onResume cycle started
                        if (thisGen != resumeGeneration) {
                            Log.d(TAG, "stale DOM capture, gen=" + thisGen + " current=" + resumeGeneration);
                            return;
                        }
                        try {
                            domHashAtPause[0] = Integer.parseInt(value);
                            Log.d(TAG, "DOM hash captured: " + domHashAtPause[0]);
                        } catch (Exception e) {
                            domHashAtPause[0] = 0;
                        }

                        webView.evaluateJavascript(
                            "(function(){" +
                            "  Object.defineProperty(document,'visibilityState',{value:'visible',writable:true});" +
                            "  Object.defineProperty(document,'hidden',{value:false,writable:true});" +
                            "  document.dispatchEvent(new Event('visibilitychange'));" +
                            "  window.dispatchEvent(new Event('online'));" +
                            "  window.dispatchEvent(new Event('focus'));" +
                            "  document.dispatchEvent(new Event('focus'));" +
                            "  try { Object.defineProperty(navigator,'onLine',{value:true,writable:true}); } catch(e){}" +
                            "  return 'events-fired';" +
                            "})();",
                            evValue -> Log.d(TAG, "reconnect injection result: " + evValue)
                        );

                        mainHandler.postDelayed(() -> {
                            if (thisGen != resumeGeneration) {
                                Log.d(TAG, "stale health check cancelled, gen=" + thisGen + " current=" + resumeGeneration);
                                return;
                            }
                            if (webView == null || webView.getUrl() == null) return;
                            webView.evaluateJavascript(
                                "document.body.innerHTML.length",
                                newValue -> {
                                    if (thisGen != resumeGeneration) {
                                        Log.d(TAG, "stale diff result ignored, gen=" + thisGen + " current=" + resumeGeneration);
                                        return;
                                    }
                                    try {
                                        int currentHash = Integer.parseInt(newValue);
                                        Log.d(TAG, "DOM diff check: was=" + domHashAtPause[0] + " now=" + currentHash);
                                        if (currentHash == domHashAtPause[0]) {
                                            Log.w(TAG, "DOM static for 3s, streaming dead — reloading");
                                            webView.reload();
                                        } else {
                                            Log.d(TAG, "DOM changed, streaming resumed — no reload needed");
                                        }
                                    } catch (Exception e) {
                                        Log.w(TAG, "DOM diff parse failed, reloading to be safe");
                                        webView.reload();
                                    }
                                }
                            );
                        }, 3000);
                    }
                );
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (audioRecord != null) {
            audioRecord.release();
        }
        if (voskRecognizer != null) {
            voskRecognizer.close();
        }
        if (voskModel != null) {
            voskModel.close();
        }
    }

    // ==================== VOICE INPUT ====================

    private void initVoskModel() {
        File modelDir = new File(getFilesDir(), VOSK_MODEL_DIR);
        if (modelDir.exists() && modelDir.isDirectory()) {
            Log.d(TAG, "Vosk model found at " + modelDir.getAbsolutePath());
            executor.execute(() -> {
                try {
                    voskModel = new Model(modelDir.getAbsolutePath());
                    voskModelReady = true;
                    Log.d(TAG, "Vosk model loaded successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load Vosk model: " + e.getMessage(), e);
                }
            });
        } else {
            Log.d(TAG, "Vosk model not found. Will download on first use.");
        }
    }

    private void onMicButtonClicked() {
        if (isRecording) {
            stopRecordingAndTranscribe();
            return;
        }

        // Check audio permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQUEST);
            return;
        }

        // Check model ready
        if (!voskModelReady) {
            // Model not ready yet — try to download it now
            recordingStatus.setText("Downloading voice model...");
            recordingOverlay.setVisibility(View.VISIBLE);
            btnMicFab.setEnabled(false);
            downloadVoskModel();
            return;
        }

        startRecording();
    }

    private void startRecording() {
        isRecording = true;
        recordingOverlay.setVisibility(View.VISIBLE);
        recordingStatus.setText("Listening...");
        btnMicFab.setEnabled(false);
        startPulseAnimation();

        // Start timer
        recordingStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRecording) return;
                long elapsed = System.currentTimeMillis() - recordingStartTime;
                recordingTimer.setText(formatTimer(elapsed));
                timerHandler.postDelayed(this, 500);
            }
        };
        timerHandler.post(timerRunnable);

        // Audio config: 16kHz, mono, 16-bit PCM
        final int sampleRate = 16000;
        final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        final int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            stopRecordingAndTranscribe();
            return;
        }

        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            short[] buffer = new short[bufferSize];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // Convert shorts to little-endian bytes
                    ByteBuffer byteBuffer = ByteBuffer.allocate(read * 2);
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < read; i++) {
                        byteBuffer.putShort(buffer[i]);
                    }
                    baos.write(byteBuffer.array(), 0, read * 2);
                }
            }
            // Store recorded bytes for transcription
            final byte[] audioBytes = baos.toByteArray();
            mainHandler.post(() -> runTranscription(audioBytes));
        });
        recordingThread.start();
    }

    private void stopRecordingAndTranscribe() {
        if (!isRecording) return;
        isRecording = false;
        timerHandler.removeCallbacks(timerRunnable);
        stopPulseAnimation();
        recordingOverlay.setVisibility(View.GONE);
        recordingStatus.setText("Listening...");
        btnMicFab.setEnabled(true);

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void runTranscription(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            Log.w(TAG, "No audio recorded");
            return;
        }

        recordingStatus.setText("Transcribing...");
        recordingOverlay.setVisibility(View.VISIBLE);
        btnMicFab.setEnabled(false);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String openaiKey = prefs.getString(KEY_OPENAI, "");

        executor.execute(() -> {
            String text = null;
            String error = null;

            // Try Whisper API first if key is available
            if (!openaiKey.isEmpty()) {
                try {
                    text = transcribeWithWhisper(audioBytes, openaiKey);
                    Log.d(TAG, "Whisper transcription: " + text);
                } catch (Exception e) {
                    error = e.getMessage();
                    Log.w(TAG, "Whisper API failed, falling back to Vosk: " + error);
                }
            }

            // Fall back to Vosk if Whisper failed or no key
            if (text == null || text.isEmpty()) {
                try {
                    if (voskRecognizer == null) {
                        voskRecognizer = new Recognizer(voskModel, 16000.0f);
                    } else {
                        voskRecognizer.reset();
                    }
                    voskRecognizer.acceptWaveForm(audioBytes, audioBytes.length);
                    String resultJson = voskRecognizer.getResult();
                    Log.d(TAG, "Vosk raw result: " + resultJson);
                    text = extractVoskText(resultJson);
                } catch (Exception e) {
                    error = e.getMessage();
                    Log.e(TAG, "Vosk transcription failed: " + error, e);
                }
            }

            final String finalText = text;
            final String finalError = error;
            mainHandler.post(() -> {
                recordingOverlay.setVisibility(View.GONE);
                btnMicFab.setEnabled(true);
                if (finalText != null && !finalText.isEmpty()) {
                    Log.d(TAG, "Final transcription: " + finalText);
                    injectTextIntoPrompt(finalText);
                } else {
                    Log.w(TAG, "Transcription empty. Error: " + finalError);
                }
            });
        });
    }

    private String extractVoskText(String json) {
        if (json == null) return "";
        // Simple JSON parsing without external dependency
        int textIndex = json.indexOf("\"text\"");
        if (textIndex < 0) return "";
        int colonIndex = json.indexOf(':', textIndex);
        if (colonIndex < 0) return "";
        int startQuote = json.indexOf('"', colonIndex + 1);
        if (startQuote < 0) return "";
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return "";
        return json.substring(startQuote + 1, endQuote);
    }

    // ==================== WHISPER API ====================

    private String transcribeWithWhisper(byte[] pcmBytes, String apiKey) throws Exception {
        byte[] wavBytes = pcmToWav(pcmBytes, 16000, (short) 1, (short) 16);

        String boundary = "----FormBoundary" + System.currentTimeMillis();
        java.net.URL url = new java.net.URL("https://api.openai.com/v1/audio/transcriptions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = conn.getOutputStream()) {
            // file part
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n").getBytes());
            out.write(("Content-Type: audio/wav\r\n\r\n").getBytes());
            out.write(wavBytes);
            out.write("\r\n".getBytes());

            // model part
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"model\"\r\n\r\n").getBytes());
            out.write(("whisper-1\r\n").getBytes());

            out.write(("--" + boundary + "--\r\n").getBytes());
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            resp.write(buf, 0, read);
        }
        in.close();
        conn.disconnect();

        String respStr = resp.toString("UTF-8");
        Log.d(TAG, "Whisper API response: " + respStr);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + respStr);
        }

        // Parse {"text": "..."}
        int textIdx = respStr.indexOf("\"text\"");
        if (textIdx < 0) throw new Exception("No text field in response");
        int colon = respStr.indexOf(':', textIdx);
        int q1 = respStr.indexOf('"', colon + 1);
        int q2 = respStr.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) throw new Exception("Malformed text field");
        return respStr.substring(q1 + 1, q2);
    }

    private byte[] pcmToWav(byte[] pcm, int sampleRate, short channels, short bitsPerSample) {
        int pcmLen = pcm.length;
        int wavLen = pcmLen + 44;
        byte[] wav = new byte[wavLen];
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeIntLE(wav, 4, wavLen - 8);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        // fmt chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeIntLE(wav, 16, 16); // subchunk1Size
        writeShortLE(wav, 20, (short) 1); // audioFormat PCM
        writeShortLE(wav, 22, channels);
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, byteRate);
        writeShortLE(wav, 32, (short) blockAlign);
        writeShortLE(wav, 34, bitsPerSample);
        // data chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeIntLE(wav, 40, pcmLen);
        System.arraycopy(pcm, 0, wav, 44, pcmLen);
        return wav;
    }

    private void writeIntLE(byte[] arr, int offset, int value) {
        arr[offset] = (byte) (value & 0xFF);
        arr[offset + 1] = (byte) ((value >> 8) & 0xFF);
        arr[offset + 2] = (byte) ((value >> 16) & 0xFF);
        arr[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private void writeShortLE(byte[] arr, int offset, short value) {
        arr[offset] = (byte) (value & 0xFF);
        arr[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private void injectTextIntoPrompt(String text) {
        if (webView == null || webView.getUrl() == null) return;

        // Escape the text for JavaScript string literal
        String escaped = text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String script =
            "(function(text){" +
            "  function findInput() {" +
            "    var el = document.activeElement;" +
            "    if (el && (el.tagName==='TEXTAREA' || el.tagName==='INPUT' || el.isContentEditable)) return el;" +
            "    var selectors = [" +
            "      'textarea[placeholder*=\"Ask\"]'," +
            "      '[contenteditable=\"true\"]'," +
            "      'textarea'," +
            "      'input[type=\"text\"]'" +
            "    ];" +
            "    for (var i=0;i<selectors.length;i++) {" +
            "      var found = document.querySelector(selectors[i]);" +
            "      if (found) return found;" +
            "    }" +
            "    return null;" +
            "  }" +
            "  var el = findInput();" +
            "  if (!el) return 'no-input-found';" +
            "  el.focus();" +
            "  if (el.tagName==='TEXTAREA' || el.tagName==='INPUT') {" +
            "    var start = el.selectionStart || el.value.length;" +
            "    var end = el.selectionEnd || el.value.length;" +
            "    var before = el.value.substring(0,start);" +
            "    var after = el.value.substring(end);" +
            "    el.value = before + text + after;" +
            "    el.selectionStart = el.selectionEnd = start + text.length;" +
            "    el.scrollTop = el.scrollHeight;" +
            "    var ev = new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text });" +
            "    el.dispatchEvent(ev);" +
            "    el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "  } else if (el.isContentEditable) {" +
            "    var sel = window.getSelection();" +
            "    if (sel && sel.rangeCount) sel.removeAllRanges();" +
            "    var range = document.createRange();" +
            "    range.selectNodeContents(el);" +
            "    range.collapse(false);" +
            "    sel.addRange(range);" +
            "    document.execCommand('insertText', false, text);" +
            "    var ev = new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text });" +
            "    el.dispatchEvent(ev);" +
            "  } else {" +
            "    return 'not-editable';" +
            "  }" +
            "  return 'injected-' + (el.tagName || 'contenteditable');" +
            "})('" + escaped + "')";

        webView.evaluateJavascript(script, value -> {
            Log.d(TAG, "Text injection result: " + value);
        });
    }

    private void downloadVoskModel() {
        executor.execute(() -> {
            try {
                File modelZip = new File(getCacheDir(), "vosk-model.zip");
                File modelDir = new File(getFilesDir(), VOSK_MODEL_DIR);

                // Download
                Log.d(TAG, "Downloading Vosk model from " + VOSK_MODEL_URL);
                HttpURLConnection conn = (HttpURLConnection) new URL(VOSK_MODEL_URL).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(modelZip)) {
                    byte[] buf = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        total += read;
                        if (total % (1024 * 1024) == 0) {
                            Log.d(TAG, "Downloaded " + (total / 1024 / 1024) + " MB");
                        }
                    }
                }
                conn.disconnect();
                Log.d(TAG, "Model download complete: " + modelZip.length() + " bytes");

                // Unzip
                Log.d(TAG, "Unpacking model to " + modelDir.getAbsolutePath());
                unzip(modelZip, modelDir.getParentFile());
                modelZip.delete();

                // Load model
                voskModel = new Model(modelDir.getAbsolutePath());
                voskModelReady = true;
                Log.d(TAG, "Vosk model ready");

                mainHandler.post(() -> {
                    recordingOverlay.setVisibility(View.GONE);
                    btnMicFab.setEnabled(true);
                    startRecording();
                });
            } catch (Exception e) {
                Log.e(TAG, "Model download failed: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    recordingOverlay.setVisibility(View.GONE);
                    btnMicFab.setEnabled(true);
                    showError("Voice model download failed: " + e.getMessage());
                });
            }
        });
    }

    private void unzip(File zipFile, File targetDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(zipFile.toURI().toURL().openStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[4096];
                        int read;
                        while ((read = zis.read(buf)) != -1) {
                            fos.write(buf, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private String formatTimer(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    // ==================== PULSE ANIMATION ====================

    private void startPulseAnimation() {
        if (pulseRing1 == null || pulseRing2 == null) return;
        pulseRing1.setScaleX(0.5f);
        pulseRing1.setScaleY(0.5f);
        pulseRing1.setAlpha(0.8f);
        pulseRing2.setScaleX(0.5f);
        pulseRing2.setScaleY(0.5f);
        pulseRing2.setAlpha(0.8f);

        pulseAnim1 = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            pulseRing1,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.5f, 1.5f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.5f, 1.5f),
            android.animation.PropertyValuesHolder.ofFloat("alpha", 0.8f, 0f)
        );
        pulseAnim1.setDuration(1500);
        pulseAnim1.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        pulseAnim1.setRepeatMode(android.animation.ObjectAnimator.RESTART);
        pulseAnim1.setInterpolator(new android.view.animation.LinearInterpolator());

        pulseAnim2 = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            pulseRing2,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.5f, 1.5f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.5f, 1.5f),
            android.animation.PropertyValuesHolder.ofFloat("alpha", 0.8f, 0f)
        );
        pulseAnim2.setDuration(1500);
        pulseAnim2.setStartDelay(750);
        pulseAnim2.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        pulseAnim2.setRepeatMode(android.animation.ObjectAnimator.RESTART);
        pulseAnim2.setInterpolator(new android.view.animation.LinearInterpolator());

        pulseAnim1.start();
        pulseAnim2.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnim1 != null) {
            pulseAnim1.cancel();
            pulseAnim1 = null;
        }
        if (pulseAnim2 != null) {
            pulseAnim2.cancel();
            pulseAnim2 = null;
        }
        if (pulseRing1 != null) {
            pulseRing1.setScaleX(1f);
            pulseRing1.setScaleY(1f);
            pulseRing1.setAlpha(0.1f);
        }
        if (pulseRing2 != null) {
            pulseRing2.setScaleX(1f);
            pulseRing2.setScaleY(1f);
            pulseRing2.setAlpha(0.1f);
        }
    }
}
