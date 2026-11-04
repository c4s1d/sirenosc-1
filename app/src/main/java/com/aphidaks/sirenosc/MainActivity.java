package com.aphidaks.sirenosc;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int BG = 0xFF0B0C0D;

    static MainActivity instance;

    private WebView web;
    private ValueCallback<Uri[]> filePathCb;
    private volatile boolean audioOn = false;
    private boolean serviceRunning = false;
    private boolean resumed = false;

    private final Object saveLock = new Object();
    private OutputStream saveOut;
    private Uri saveUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) { }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        if (Config.KEEP_AWAKE) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tuneWindow(w);

        web = new WebView(this);
        setContentView(web);
        configureWebView(web);

        if (Config.BG_AUDIO && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        web.loadUrl(Config.START_URL);
    }

    /** Display + CPU hints: highest refresh rate at native resolution, sustained-performance mode, no cutout letterbox surprises. */
    private void tuneWindow(Window w) {
        WindowManager.LayoutParams lp = w.getAttributes();
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }
        if (Config.MAX_REFRESH) {
            try {
                Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
                if (d != null) {
                    Display.Mode cur = d.getMode();
                    Display.Mode best = cur;
                    for (Display.Mode m : d.getSupportedModes()) {
                        if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                                && m.getPhysicalHeight() == cur.getPhysicalHeight()
                                && m.getRefreshRate() > best.getRefreshRate()) {
                            best = m;
                        }
                    }
                    lp.preferredDisplayModeId = best.getModeId();
                    if (Build.VERSION.SDK_INT >= 30) lp.preferredRefreshRate = best.getRefreshRate();
                }
            } catch (Throwable ignored) { }
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isSustainedPerformanceModeSupported()) w.setSustainedPerformanceMode(true);
            } catch (Throwable ignored) { }
        }
        w.setAttributes(lp);
    }

    private void applyImmersive() {
        if (!Config.IMMERSIVE) return;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void configureWebView(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);
        try { s.setForceDark(WebSettings.FORCE_DARK_OFF); } catch (Throwable ignored) { }

        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        v.setVerticalScrollBarEnabled(false);
        v.setHorizontalScrollBarEnabled(false);
        v.setBackgroundColor(BG);
        v.addJavascriptInterface(new Bridge(), "AndroidApp");

        v.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!Config.HOST.equals(u.getHost())) return null;
                return serveAsset(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (Config.HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                runOnUiThread(() -> recreate());
                return true;
            }
        });

        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "Select file"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    filePathCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
    }

    private void openExternal(Uri u) {
        String sc = u.getScheme();
        if (sc == null) return;
        if (sc.equals("http") || sc.equals("https") || sc.equals("mailto")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignored) { }
        }
    }

    private WebResourceResponse serveAsset(Uri u) {
        String p = u.getPath();
        if (p == null || p.isEmpty() || p.equals("/")) p = "/index.html";
        while (p.startsWith("/")) p = p.substring(1);
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        if (p.contains("..")) {
            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", h, new ByteArrayInputStream(new byte[0]));
        }
        try {
            InputStream in = getAssets().open("www/" + p, AssetManager.ACCESS_STREAMING);
            String mime = mimeFor(p);
            boolean text = mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/javascript");
            return new WebResourceResponse(mime, text ? "utf-8" : null, 200, "OK", h, in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mimeFor(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".svg")) return "image/svg+xml";
        int dot = l.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(l.substring(dot + 1));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    /** JavaScript <-> native bridge (window.AndroidApp). */
    private class Bridge {
        @JavascriptInterface
        public void audioState(boolean on) {
            audioOn = on;
            runOnUiThread(() -> syncService());
        }

        @JavascriptInterface
        public void saveBegin(String name, String mime) {
            synchronized (saveLock) {
                abortSave();
                try {
                    String n = (name == null ? "" : name).replaceAll("[^A-Za-z0-9._ ()\\-]+", "_").trim();
                    if (n.isEmpty()) n = "download";
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, n);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE,
                            (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Config.SAVE_DIR);
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    saveUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    saveOut = getContentResolver().openOutputStream(saveUri);
                } catch (Throwable t) {
                    abortSave();
                    toast("Save failed");
                }
            }
        }

        @JavascriptInterface
        public void saveChunk(String b64) {
            synchronized (saveLock) {
                if (saveOut == null) return;
                try {
                    saveOut.write(Base64.decode(b64, Base64.DEFAULT));
                } catch (Throwable t) {
                    abortSave();
                    toast("Save failed");
                }
            }
        }

        @JavascriptInterface
        public void saveEnd() {
            synchronized (saveLock) {
                if (saveOut == null) return;
                try {
                    saveOut.close();
                    saveOut = null;
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(saveUri, cv, null, null);
                    saveUri = null;
                    toast("Saved to Downloads/" + Config.SAVE_DIR);
                } catch (Throwable t) {
                    abortSave();
                    toast("Save failed");
                }
            }
        }

        @JavascriptInterface
        public void saveAbort() {
            synchronized (saveLock) { abortSave(); }
            toast("Save failed");
        }

        @JavascriptInterface
        public void toast(String msg) { MainActivity.this.toast(msg); }
    }

    private void abortSave() {
        try { if (saveOut != null) saveOut.close(); } catch (Throwable ignored) { }
        try { if (saveUri != null) getContentResolver().delete(saveUri, null, null); } catch (Throwable ignored) { }
        saveOut = null;
        saveUri = null;
    }

    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
    }

    /** Foreground service keeps the process (and the audio thread) alive while sound is playing in the background. */
    private void syncService() {
        if (Config.BG_AUDIO) {
            Intent i = new Intent(this, PlaybackService.class);
            if (audioOn && !serviceRunning) {
                try { startForegroundService(i); serviceRunning = true; } catch (Throwable ignored) { }
            } else if (!audioOn && serviceRunning) {
                try { stopService(i); } catch (Throwable ignored) { }
                serviceRunning = false;
            }
        }
        if (!audioOn && !resumed && web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    static void quit() {
        final MainActivity a = instance;
        if (a != null) a.runOnUiThread(() -> a.finishAndRemoveTask());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
        applyImmersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Only freeze the page when nothing is playing; audio keeps running with the screen off.
        if (!audioOn && web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onBackPressed() {
        if (audioOn) moveTaskToBack(true);
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                out = new Uri[cd.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        if (filePathCb != null) {
            filePathCb.onReceiveValue(out);
            filePathCb = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        synchronized (saveLock) { abortSave(); }
        if (serviceRunning) {
            try { stopService(new Intent(this, PlaybackService.class)); } catch (Throwable ignored) { }
            serviceRunning = false;
        }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
