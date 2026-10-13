package com.chasmet.modeliseur3d;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;

/** Offline GLB viewer. HTTPS is intercepted locally; no network/file/JavaScript bridge. */
public final class CloudModelViewerActivity extends AppCompatActivity {
    private WebView view;
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("job");
        if (id == null || !id.matches("[a-f0-9]{32}")) { finish(); return; }
        File model = new File(getFilesDir(), "cloud_models/" + id + ".glb");
        if (!model.isFile()) { finish(); return; }
        view = new WebView(this); setContentView(view);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setDomStorageEnabled(false); settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, String url) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest request) {
                Uri uri = request.getUrl();
                try {
                    if ("https".equals(uri.getScheme()) && "modeliseur.local".equals(uri.getHost())
                            && "GET".equals(request.getMethod())) {
                        if (("/model/" + id + ".glb").equals(uri.getPath()))
                            return new WebResourceResponse("model/gltf-binary", null, new FileInputStream(model));
                        if ("/viewer/index.html".equals(uri.getPath()))
                            return new WebResourceResponse("text/html", "UTF-8", getAssets().open("viewer/index.html"));
                        if ("/viewer/viewer.js".equals(uri.getPath()))
                            return new WebResourceResponse("application/javascript", "UTF-8", getAssets().open("viewer/viewer.js"));
                    }
                } catch (IOException ignored) { }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null,
                        new ByteArrayInputStream(new byte[0]));
            }
        });
        view.loadUrl("https://modeliseur.local/viewer/index.html?id=" + id);
    }
    @Override protected void onResume() { super.onResume(); if (view != null) { view.onResume(); view.evaluateJavascript("window.viewerActive && window.viewerActive(true)", null); } }
    @Override protected void onPause() { if (view != null) { view.evaluateJavascript("window.viewerActive && window.viewerActive(false)", null); view.onPause(); } super.onPause(); }
    @Override protected void onDestroy() { if (view != null) { view.stopLoading(); view.destroy(); } super.onDestroy(); }
}
