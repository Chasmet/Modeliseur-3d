package com.chasmet.modeliseur3d;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Existing offline WebGL/Three.js GLB viewer, now shared by cloud jobs, the asset
 * catalog, and the Android system document picker. No external viewer or server.
 */
public final class CloudModelViewerActivity extends AppCompatActivity {
    public static final String EXTRA_CONTENT_URI = "glb_content_uri";
    private static final String HOST = "modeliseur.local";
    private WebView view;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        final String job = getIntent().getStringExtra("job");
        final Uri documentUri;
        final File privateFile;
        final String modelId;
        if (job != null) {
            // Preserve the original cloud-job path and its strict identifier check.
            if (!job.matches("[a-f0-9]{32}")) { finish(); return; }
            privateFile = new File(getFilesDir(), "cloud_models/" + job + ".glb");
            if (!privateFile.isFile()) { finish(); return; }
            documentUri = null;
            modelId = job;
        } else {
            final String raw = getIntent().getStringExtra(EXTRA_CONTENT_URI);
            final Uri candidate = raw == null ? null : Uri.parse(raw);
            if (candidate == null || !"content".equals(candidate.getScheme())
                    || candidate.getAuthority() == null) { finish(); return; }
            privateFile = null;
            documentUri = candidate;
            modelId = UUID.randomUUID().toString().replace("-", "");
        }

        view = new WebView(this);
        setContentView(view);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setDomStorageEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, String url) {
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView w, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())
                        || !"GET".equals(request.getMethod())) return forbidden();
                try {
                    String path = uri.getPath();
                    if (("/model/" + modelId + ".glb").equals(path)) {
                        InputStream input = documentUri != null
                                ? getContentResolver().openInputStream(documentUri)
                                : new FileInputStream(privateFile);
                        if (input == null) throw new FileNotFoundException("GLB inaccessible");
                        return new WebResourceResponse("model/gltf-binary", null, input);
                    }
                    if ("/viewer/index.html".equals(path))
                        return new WebResourceResponse("text/html", "UTF-8",
                                getAssets().open("viewer/index.html"));
                    if ("/viewer/viewer.js".equals(path))
                        return new WebResourceResponse("application/javascript", "UTF-8",
                                getAssets().open("viewer/viewer.js"));
                } catch (IOException | SecurityException ignored) {
                    return forbidden();
                }
                return forbidden();
            }
        });
        view.loadUrl("https://" + HOST + "/viewer/index.html?id=" + modelId);
    }

    private static WebResourceResponse forbidden() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                null, new ByteArrayInputStream(new byte[0]));
    }

    @Override protected void onResume() {
        super.onResume();
        if (view != null) {
            view.onResume();
            view.evaluateJavascript("window.viewerActive && window.viewerActive(true)", null);
        }
    }

    @Override protected void onPause() {
        if (view != null) {
            view.evaluateJavascript("window.viewerActive && window.viewerActive(false)", null);
            view.onPause();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (view != null) { view.stopLoading(); view.destroy(); view = null; }
        super.onDestroy();
    }
}
