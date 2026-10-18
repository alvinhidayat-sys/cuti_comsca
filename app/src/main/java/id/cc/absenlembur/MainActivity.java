package id.cc.absenlembur;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pembungkus (wrapper) WebView untuk Dashboard Command Center.
 * HTML dimuat dari assets lewat https://appassets.androidplatform.net sehingga
 * localStorage, fetch ke Google Apps Script, dan CDN bekerja seperti di browser.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String HOME = ORIGIN + "/assets/index.html";
    private static final int REQ_FILE = 1001;
    private static final int REQ_STORAGE = 1002;

    private WebView web;
    private WebView printView;
    private ProgressBar bar;
    private ValueCallback<Uri[]> fileCb;
    private long lastBack = 0;
    private boolean docStartInstalled = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Skrip yang disuntik ke setiap halaman (termasuk iframe srcdoc bila didukung WebView). */
    private static final String BOOT = """
        (function(){
          if(window.__ccApp) return;
          window.__ccApp = 1;

          function init(){
            try{
              var st = document.createElement('style');
              st.textContent = 'html{-webkit-tap-highlight-color:transparent;-webkit-text-size-adjust:100%}'
                + 'a,button,[role=button],summary,label,select,input[type=checkbox],input[type=radio]{touch-action:manipulation}';
              (document.head || document.documentElement).appendChild(st);
            }catch(e){}
            try{
              if(window.top === window){
                var send = function(){
                  var m = document.querySelector('meta[name=theme-color]');
                  if(m && window.AndroidApp){ window.AndroidApp.setBarColor(m.getAttribute('content') || ''); }
                };
                send();
                var mm = document.querySelector('meta[name=theme-color]');
                if(mm){ new MutationObserver(send).observe(mm, {attributes:true, attributeFilter:['content']}); }
              }
            }catch(e){}
          }
          if(document.readyState === 'loading'){ document.addEventListener('DOMContentLoaded', init); } else { init(); }

          function isDl(a){
            return a && a.hasAttribute && a.hasAttribute('download') && /^(blob|data):/.test(a.href);
          }
          function saveAnchor(a){
            fetch(a.href).then(function(r){ return r.blob(); }).then(function(bl){
              var fr = new FileReader();
              fr.onloadend = function(){
                window.AndroidApp.saveBase64(String(fr.result).split(',')[1] || '',
                  a.getAttribute('download') || 'berkas', bl.type || 'application/octet-stream');
              };
              fr.readAsDataURL(bl);
            }).catch(function(){ /* biarkan DownloadListener native menangani */ });
          }
          var oc = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function(){
            if(window.AndroidApp && isDl(this)){ saveAnchor(this); return; }
            return oc.call(this);
          };
          var od = EventTarget.prototype.dispatchEvent;
          HTMLAnchorElement.prototype.dispatchEvent = function(ev){
            if(window.AndroidApp && ev && ev.type === 'click' && isDl(this)){ saveAnchor(this); return true; }
            return od.call(this, ev);
          };

          window.print = function(){
            if(window.AndroidApp){
              window.AndroidApp.printHtml('<!DOCTYPE html>' + document.documentElement.outerHTML);
            }
          };
        })();
        """;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF52C3DC));
        float d = getResources().getDisplayMetrics().density;
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(3 * d), Gravity.TOP));
        setContentView(root);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> rules = new HashSet<>();
            rules.add(ORIGIN);
            WebViewCompat.addDocumentStartJavaScript(web, BOOT, rules);
            docStartInstalled = true;
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleExternal(r.getUrl());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                bar.setProgress(0);
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                if (!docStartInstalled) {
                    v.evaluateJavascript(BOOT, null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                if (isFinishing()) { r.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (dlg, w) -> r.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                if (isFinishing()) { r.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (dlg, w) -> r.confirm())
                        .setNegativeButton(android.R.string.cancel, (dlg, w) -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                if (isFinishing()) { r.cancel(); return true; }
                final EditText et = new EditText(MainActivity.this);
                et.setText(def);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setView(et)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (dlg, w) -> r.confirm(et.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (dlg, w) -> r.cancel())
                        .show();
                return true;
            }
        });

        // Unduhan: blob:/data: dari skrip, atau URL biasa (fallback)
        web.setDownloadListener((url, userAgent, contentDisposition, mime, length) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            if (url.startsWith("blob:")) {
                String js = "(function(u,n,m){var x=new XMLHttpRequest();x.open('GET',u,true);"
                        + "x.responseType='blob';x.onload=function(){var r=new FileReader();"
                        + "r.onloadend=function(){AndroidApp.saveBase64(String(r.result).split(',')[1]||'',n,m||x.response.type)};"
                        + "r.readAsDataURL(x.response)};x.send()})("
                        + JSONObject.quote(url) + "," + JSONObject.quote(name) + "," + JSONObject.quote(mime == null ? "" : mime) + ");";
                web.evaluateJavascript(js, null);
            } else if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma > 0 && url.substring(0, comma).contains(";base64")) {
                    saveFile(Base64.decode(url.substring(comma + 1), Base64.DEFAULT), name, mime);
                }
            } else {
                handleExternal(Uri.parse(url));
            }
        });

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }

        if (!isOnline()) {
            Toast.makeText(this, "Tidak ada koneksi internet. Data tidak dapat disinkronkan.", Toast.LENGTH_LONG).show();
        }

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(HOME);
        }
    }

    // ---------------------------------------------------------------- navigasi

    private boolean handleExternal(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) {
            return false;
        }
        if ((scheme.equals("https") || scheme.equals("http")) && HOST.equals(u.getHost())) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    // ---------------------------------------------------------- jembatan JS

    private class Bridge {
        @JavascriptInterface
        public void saveBase64(String b64, String name, String mime) {
            try {
                saveFile(Base64.decode(b64, Base64.DEFAULT), name, mime);
            } catch (IllegalArgumentException e) {
                toast("Gagal menyimpan berkas");
            }
        }

        @JavascriptInterface
        public void printHtml(final String html) {
            ui.post(() -> doPrint(html));
        }

        @JavascriptInterface
        public void setBarColor(final String color) {
            ui.post(() -> applyBarColor(color));
        }
    }

    private void toast(final String msg) {
        ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void applyBarColor(String c) {
        int col;
        try {
            col = Color.parseColor(c.trim());
        } catch (Exception e) {
            return;
        }
        getWindow().setStatusBarColor(col);
        getWindow().setNavigationBarColor(col);
        web.setBackgroundColor(col);
        double lum = (0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col)) / 255.0;
        View dv = getWindow().getDecorView();
        int f = dv.getSystemUiVisibility();
        if (lum > 0.6) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        dv.setSystemUiVisibility(f);
    }

    // ------------------------------------------------------------ simpan berkas

    private void saveFile(byte[] data, String name, String mime) {
        name = cleanName(name);
        if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
            int dot = name.lastIndexOf('.');
            String guess = dot >= 0
                    ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                    : null;
            mime = guess != null ? guess : "application/octet-stream";
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                final Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("insert gagal");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(data);
                }
                final String m = mime;
                final String n = name;
                ui.post(() -> {
                    Toast.makeText(this, "Tersimpan di folder Download: " + n, Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, m);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                    } catch (ActivityNotFoundException ignored) {
                        // tidak ada aplikasi pembuka; berkas tetap ada di Download
                    }
                });
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File f = uniqueFile(dir, name);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(data);
                }
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                toast("Tersimpan di folder Download: " + f.getName());
            }
        } catch (Exception e) {
            toast("Gagal menyimpan berkas: izinkan akses penyimpanan lalu coba lagi");
        }
    }

    private static String cleanName(String n) {
        if (n == null || n.trim().isEmpty()) return "berkas";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
    }

    // ------------------------------------------------------------------ cetak

    private void doPrint(String html) {
        final WebView pv = new WebView(this);
        printView = pv;
        pv.getSettings().setJavaScriptEnabled(false);
        final boolean[] started = {false};
        pv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (started[0]) return;
                started[0] = true;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                String job = "Dokumen Command Center";
                pm.print(job, v.createPrintDocumentAdapter(job),
                        new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            }
        });
        pv.loadDataWithBaseURL(ORIGIN + "/assets/", html, "text/html", "UTF-8", null);
    }

    // ------------------------------------------------------------ siklus hidup

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (printView != null) {
            printView.destroy();
            printView = null;
        }
        web.destroy();
        super.onDestroy();
    }
}
