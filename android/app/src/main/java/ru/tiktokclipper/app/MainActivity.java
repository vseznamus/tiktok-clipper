package ru.tiktokclipper.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

/** Главный экран: тот же интерфейс, что у веб-версии, внутри WebView + мост к системным окнам Android. */
public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 1, REQ_VIDEO = 2;
    private WebView web;
    private Engine engine;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#111118"));
        w.setNavigationBarColor(Color.parseColor("#0b0b10"));
        try {
            engine = Engine.get(this);
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#111118"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebChromeClient(new WebChromeClient()); // включает confirm() и alert()
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("127.0.0.1".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // внешние ссылки (TMDB) — в браузере
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        setContentView(web);
        if (state != null) web.restoreState(state);
        else web.loadUrl(engine.startUrl());
        askPermissions();
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // у некоторых файловых менеджеров постоянного доступа нет — сработает до перезапуска
        }
        try {
            if (req == REQ_FOLDER) {
                String name = folderName(uri);
                JSONObject o = new JSONObject().put("uri", uri.toString()).put("name", name);
                js("window.onFolderPicked && window.onFolderPicked(" + o + ")");
            } else if (req == REQ_VIDEO) {
                String id = engine.platform.registerPicked(uri);
                js("window.onVideoPicked && window.onVideoPicked(" + JSONObject.quote(id) + ")");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Не получилось: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String folderName(Uri tree) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "папка";
    }

    private Uri clipUri(String clipId) {
        JSONObject m = engine.api.loadClip(clipId);
        if (m == null || m.optString("media_uri").isEmpty()) return null;
        return Uri.parse(m.optString("media_uri"));
    }

    /** Методы, доступные из интерфейса: window.AndroidApp.* */
    final class Bridge {
        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            });
        }

        @JavascriptInterface
        public void pickVideo() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_VIDEO);
            });
        }

        @JavascriptInterface
        public void shareClip(String clipId) {
            final Uri u = clipUri(clipId);
            runOnUiThread(() -> {
                if (u == null) {
                    Toast.makeText(MainActivity.this, "Клип не найден в галерее", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, "Поделиться клипом"));
            });
        }

        @JavascriptInterface
        public void openClip(String clipId) {
            final Uri u = clipUri(clipId);
            runOnUiThread(() -> {
                if (u == null) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для просмотра видео", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return ru.tiktokclipper.core.Api.VERSION;
        }
    }
}
