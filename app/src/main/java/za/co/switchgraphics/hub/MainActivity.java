package za.co.switchgraphics.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Switch Hub for Android.
 * Opens Switch Hub from Switch's PC (shop Wi-Fi or Tailscale) in a full-screen app, and adds the
 * phone things a browser can't do well: open / share invoice PDFs (WhatsApp), take photos of
 * supplier invoices, open WhatsApp and phone links, and find the PC on the Wi-Fi by itself.
 */
public class MainActivity extends Activity {

    static final int FILE_REQ = 7;
    static final String ASSET = "file:///android_asset/";

    WebView web;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile String server;            // e.g. http://192.168.1.20:5757 (null while on our own pages)
    ValueCallback<Uri[]> fileCb;
    File camFile;
    Discovery discovery;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0E1B31"));
        w.setNavigationBarColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        prefs = getSharedPreferences("hub", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1B31"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " SwitchHubApp/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Bridge(), "SwitchHubApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            String name = URLUtil.guessFileName(url, disposition, mime);
            fetchFile(url, name, false);
        });

        if (!handleLink(getIntent())) connect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleLink(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        stopDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (server == null && web.getUrl() != null && web.getUrl().startsWith(ASSET + "setup")) startDiscovery();
    }

    @Override
    public void onBackPressed() {
        String url = web.getUrl();
        if (url != null && url.startsWith(ASSET + "setup") && !addresses().isEmpty()) { connect(); return; }
        web.evaluateJavascript("!!(window.appBack && window.appBack())", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }

    /** switchhub://connect?url=http://192.168.1.20:5757 */
    boolean handleLink(Intent i) {
        if (i == null || i.getData() == null || !"switchhub".equals(i.getData().getScheme())) return false;
        String u = i.getData().getQueryParameter("url");
        if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
            addAddress(u, true);
            connect();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ saved PC addresses
    List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("addresses", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    void saveAddresses(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs.edit().putString("addresses", a.toString()).apply();
    }

    static String normalise(String u) {
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        try {
            Uri p = Uri.parse(u);
            String host = p.getHost();
            if (host == null || host.isEmpty()) return "";
            int port = p.getPort();
            if (port == -1 && "http".equals(p.getScheme())) port = 5757;
            return p.getScheme() + "://" + host + (port == -1 ? "" : ":" + port);
        } catch (Exception e) {
            return "";
        }
    }

    void addAddress(String u, boolean first) {
        u = normalise(u);
        if (u.isEmpty()) return;
        List<String> list = addresses();
        list.remove(u);
        if (first) list.add(0, u); else list.add(u);
        saveAddresses(list);
    }

    // ------------------------------------------------------------------ connecting
    void connect() {
        stopDiscovery();
        final List<String> list = addresses();
        if (list.isEmpty()) { showSetup(); return; }
        server = null;
        web.loadUrl(ASSET + "offline.html?s=connecting");
        new Thread(() -> {
            String found = probeAll(list);
            ui.post(() -> {
                if (found != null) {
                    server = found;
                    prefs.edit().putString("last", found).apply();
                    web.clearHistory();
                    web.loadUrl(found + "/");
                } else {
                    showOffline();
                }
            });
        }).start();
    }

    /** Tries every saved address at once; the last one that worked wins a tie, otherwise the first to answer. */
    String probeAll(List<String> list) {
        final AtomicReference<String> winner = new AtomicReference<>();
        final String last = prefs.getString("last", "");
        final CountDownLatch done = new CountDownLatch(list.size());
        final List<String> ok = new ArrayList<>();
        for (final String base : list) {
            new Thread(() -> {
                try {
                    if (ping(base)) {
                        synchronized (ok) { ok.add(base); }
                        if (base.equals(last) || winner.get() == null) winner.set(base);
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }
        try { done.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        synchronized (ok) { if (ok.contains(last)) return last; }
        return winner.get();
    }

    static boolean ping(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/ping").openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            if (c.getResponseCode() != 200) return false;
            String body = new String(readAll(c.getInputStream()), "UTF-8");
            return body.contains("switchhub");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    void showSetup() {
        server = null;
        web.loadUrl(ASSET + "setup.html");
        startDiscovery();
    }

    void showOffline() {
        server = null;
        web.loadUrl(ASSET + "offline.html");
    }

    /** After signing in, remember the PC's other addresses (Wi-Fi + Tailscale) so it also works away from the shop. */
    void learnAddresses() {
        final String base = server;
        if (base == null) return;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/api/phone/links").openConnection();
                String cookie = CookieManager.getInstance().getCookie(base);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                if (c.getResponseCode() != 200) return;
                JSONObject j = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                JSONArray links = j.optJSONArray("links");
                if (links == null) return;
                List<String> list = addresses();
                boolean changed = false;
                for (int i = 0; i < links.length(); i++) {
                    String u = normalise(links.getJSONObject(i).optString("url"));
                    if (!u.isEmpty() && !list.contains(u)) { list.add(u); changed = true; }
                }
                if (changed) saveAddresses(list);
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    boolean onOurServer(Uri u) {
        String base = server;
        if (base == null || u == null) return false;
        Uri b = Uri.parse(base);
        int up = u.getPort() == -1 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort();
        int bp = b.getPort() == -1 ? ("https".equals(b.getScheme()) ? 443 : 80) : b.getPort();
        return b.getHost() != null && b.getHost().equalsIgnoreCase(u.getHost()) && up == bp;
    }

    // ------------------------------------------------------------------ wifi discovery
    void startDiscovery() {
        if (discovery == null) { discovery = new Discovery(this); discovery.start(); }
    }

    void stopDiscovery() {
        if (discovery != null) { discovery.stop(); discovery = null; }
    }

    // ------------------------------------------------------------------ files (PDFs, photos)
    void fetchFile(final String rawUrl, final String title, final boolean share) {
        final String url = absolute(rawUrl);
        if (url == null) return;
        toast("Opening…");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                c.setConnectTimeout(8000);
                c.setReadTimeout(60000);
                if (c.getResponseCode() != 200) throw new Exception("The PC answered " + c.getResponseCode());
                String mime = c.getContentType();
                byte[] data = readAll(c.getInputStream());
                String cd = c.getHeaderField("Content-Disposition");
                String name = (cd != null && cd.contains("filename")) ? fileName(URLUtil.guessFileName(url, cd, mime), mime) : fileName(title, mime);
                File f = saveFile(data, name);
                ui.post(() -> openFile(f, share));
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't get the file: " + e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    String absolute(String u) {
        if (u == null) return null;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (server == null) return null;
        return server + (u.startsWith("/") ? "" : "/") + u;
    }

    static String fileName(String title, String mime) {
        String t = (title == null || title.trim().isEmpty()) ? "document" : title.trim();
        t = t.replaceAll("[^A-Za-z0-9 ._\\-]+", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "document";
        if (t.length() > 80) t = t.substring(0, 80);
        String lower = t.toLowerCase();
        boolean pdf = mime == null || mime.contains("pdf");
        if (pdf && !lower.endsWith(".pdf")) t += ".pdf";
        else if (!pdf && mime.startsWith("image/") && !lower.matches(".*\\.(jpe?g|png|webp)$")) t += mime.contains("png") ? ".png" : ".jpg";
        return t;
    }

    File saveFile(byte[] data, String name) throws Exception {
        File dir = new File(getCacheDir(), "files");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no space");
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) if (System.currentTimeMillis() - o.lastModified() > 86400000L) o.delete();
        File f = new File(dir, name);
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f;
    }

    void openFile(File f, boolean share) {
        Uri uri = FilesProvider.uriFor(f);
        String mime = FilesProvider.mimeOf(f.getName());
        try {
            if (share) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.setClipData(ClipData.newRawUri(f.getName(), uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Send " + f.getName()));
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.setClipData(ClipData.newRawUri(f.getName(), uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Open " + f.getName()));
            }
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone can open PDFs. Install Google Drive or a PDF viewer.");
        }
    }

    void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No app on this phone can open that link.");
        }
    }

    void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != FILE_REQ || fileCb == null) return;
        Uri[] res = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData cd = data.getClipData();
                res = new Uri[cd.getItemCount()];
                for (int i = 0; i < cd.getItemCount(); i++) res[i] = cd.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                res = new Uri[]{data.getData()};
            } else if (camFile != null && camFile.exists() && camFile.length() > 0) {
                res = new Uri[]{FilesProvider.uriFor(camFile)};
            }
        }
        fileCb.onReceiveValue(res);
        fileCb = null;
    }

    // ------------------------------------------------------------------ web view
    class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("file") || scheme.equals("about") || scheme.equals("data") || scheme.equals("blob")) return false;
            if (onOurServer(u)) {
                String p = u.getPath() == null ? "" : u.getPath();
                if (req.isForMainFrame() && (p.endsWith("/pdf") || p.startsWith("/api/file/") || p.startsWith("/api/statement") || p.startsWith("/api/supplier-statement")
                        || "1".equals(u.getQueryParameter("download")))) {
                    fetchFile(u.toString(), lastSegment(u), false);
                    return true;
                }
                return false;
            }
            if (server == null && (scheme.equals("http") || scheme.equals("https"))) {
                // a PC address typed on the setup page
                return false;
            }
            openExternal(u.toString());
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && server != null && url.startsWith(server) && !url.contains("/phone")) learnAddresses();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame() && !req.getUrl().toString().startsWith(ASSET)) showOffline();
        }
    }

    static String lastSegment(Uri u) {
        String s = u.getLastPathSegment();
        return s == null ? "document" : s;
    }

    class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                Intent pick = p.createIntent();
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                String[] types = p.getAcceptTypes();
                StringBuilder ab = new StringBuilder();
                if (types != null) for (String t : types) ab.append(t).append(',');
                String accept = ab.toString().replace(",", "");
                List<Intent> extra = new ArrayList<>();
                if (accept.isEmpty() || accept.contains("image") || accept.contains("*")) {
                    File dir = new File(getCacheDir(), "cam");
                    dir.mkdirs();
                    camFile = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    Uri out = FilesProvider.uriFor(camFile);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, out);
                    cam.setClipData(ClipData.newRawUri("photo", out));
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    extra.add(cam);
                } else {
                    camFile = null;
                }
                Intent chooser = Intent.createChooser(pick, "Choose a file or take a photo");
                if (!extra.isEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toArray(new Intent[0]));
                startActivityForResult(chooser, FILE_REQ);
                return true;
            } catch (Exception e) {
                fileCb = null;
                toast("Couldn't open the file picker");
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ JavaScript bridge (window.SwitchHubApp)
    class Bridge {
        @JavascriptInterface public String version() { return "1.0"; }

        @JavascriptInterface public void openPdf(String url, String title) { ui.post(() -> fetchFile(url, title, false)); }

        @JavascriptInterface public void sharePdf(String url, String title) { ui.post(() -> fetchFile(url, title, true)); }

        @JavascriptInterface public void openPdfData(String b64, String title, boolean share) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                File f = saveFile(data, fileName(title, "application/pdf"));
                ui.post(() -> openFile(f, share));
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't open the preview"));
            }
        }

        @JavascriptInterface public void openExternal(String url) { ui.post(() -> MainActivity.this.openExternal(url)); }

        @JavascriptInterface public void openSetup() { ui.post(MainActivity.this::showSetup); }

        @JavascriptInterface public void retry() { ui.post(MainActivity.this::connect); }

        @JavascriptInterface public String getAddresses() {
            JSONArray a = new JSONArray();
            for (String s : addresses()) a.put(s);
            return a.toString();
        }

        @JavascriptInterface public void addAddress(String u) {
            MainActivity.this.addAddress(u, true);
            ui.post(MainActivity.this::connect);
        }

        @JavascriptInterface public void removeAddress(String u) {
            List<String> list = addresses();
            list.remove(u);
            saveAddresses(list);
        }

        @JavascriptInterface public String found() {
            Discovery d = discovery;
            return d == null ? "[]" : d.foundJson();
        }

        @JavascriptInterface public String lastTried() {
            return prefs.getString("last", "");
        }
    }
}
