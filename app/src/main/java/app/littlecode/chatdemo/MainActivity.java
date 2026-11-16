package app.littlecode.chatdemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * chat-demo 的 Android 外壳：把 rspack 打出来的静态站点放进 assets，
 * 用 https://appassets.androidplatform.net 这个安全源交给 WebView，
 * 这样 localStorage / IndexedDB / getUserMedia 都能正常工作
 * （直接用 file:// 打开会丢存储能力）。
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final String PATCH_ASSET = "shell/patch.js";
    /** 自动备份的专有目录：Download/DeepSeekChatDemo/backups */
    private static final String BACKUP_RELATIVE = "Download/DeepSeekChatDemo/backups";
    private static final String BACKUP_SUBDIR = "DeepSeekChatDemo/backups";
    private static final int REQ_FILE_CHOOSER = 2101;
    private static final int REQ_AUDIO_PERMISSION = 2102;

    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("mjs", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("map", "application/json");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("txt", "text/plain");
        MIME.put("wasm", "application/wasm");
    }

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest audioRequest;
    private String patchScript;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBarColors();

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureSettings(webView.getSettings());
        webView.setBackgroundColor(0xFFF5F7FE);
        webView.setWebViewClient(new ShellClient());
        webView.setWebChromeClient(new ShellChromeClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidShell");
        patchScript = readAsset(PATCH_ASSET);
        webView.loadUrl(START_URL);
    }

    /** 系统栏跟随 App 内浅/深色：外壳没有自己的主题，跟系统夜间模式走。 */
    private void applySystemBarColors() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Window window = getWindow();
        int color = night ? 0xFF101319 : 0xFFF9FBFF;
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        int flags = window.getDecorView().getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBarColors();
        if (webView != null) {
            webView.setBackgroundColor((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES ? 0xFF101319 : 0xFFF5F7FE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
    }


    /** 备份目录里的一个 ZIP */
    private static class BackupFile {
        final String name;
        final long size;
        final long modified;
        final Uri uri;

        BackupFile(String name, long size, long modified, Uri uri) {
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.uri = uri;
        }
    }

    /** 备份文件所在目录（API 29 以下回落到公共下载目录下的子目录） */
    private File backupDirLegacy() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_SUBDIR);
    }

    private java.util.List<BackupFile> queryBackups() {
        java.util.List<BackupFile> files = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = {
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED,
            };
            String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String[] args = { BACKUP_RELATIVE + "%" };
            try (android.database.Cursor cursor = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                    MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(1);
                        if (name == null) {
                            continue;
                        }
                        Uri uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                        files.add(new BackupFile(name, cursor.getLong(2), cursor.getLong(3) * 1000L, uri));
                    }
                }
            } catch (Exception error) {
                logShell("查询备份目录失败：" + error);
            }
            return files;
        }
        File dir = backupDirLegacy();
        File[] list = dir.listFiles();
        if (list != null) {
            java.util.Arrays.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : list) {
                if (file.isFile()) {
                    files.add(new BackupFile(
                            file.getName(), file.length(), file.lastModified(), Uri.fromFile(file)));
                }
            }
        }
        return files;
    }

    private BackupFile findBackup(String name) {
        for (BackupFile file : queryBackups()) {
            if (file.name.equals(name)) {
                return file;
            }
        }
        return null;
    }

    private InputStream openBackupStream(BackupFile file) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getContentResolver().openInputStream(file.uri);
        }
        return new java.io.FileInputStream(file.uri.getPath());
    }

    private boolean deleteBackup(String name) {
        BackupFile file = findBackup(name);
        if (file == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContentResolver().delete(file.uri, null, null) > 0;
            }
            File target = new File(backupDirLegacy(), name);
            return target.delete();
        } catch (Exception error) {
            logShell("删除备份失败：" + error);
            return false;
        }
    }

    /** 把外壳事件写进 App 的日志页（页面用 installLogCapture 注册了 window.__chatDemoLog） */
    private void logShell(final String message) {
        final WebView view = webView;
        if (view == null) {
            return;
        }
        String quoted = JSONObject.quote(message);
        view.post(() -> {
            WebView target = webView;
            if (target != null) {
                target.evaluateJavascript("window.__chatDemoLog&&window.__chatDemoLog(" + quoted + ")", null);
            }
        });
    }

    private String readAsset(String path) {
        InputStream in = null;
        try {
            in = getAssets().open(path);
            byte[] buf = new byte[in.available()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (IOException error) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }

    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.length() == 0 || path.equals("/")) {
            path = "/index.html";
        }
        String name = path.substring(1);
        String mime = mimeOf(name);
        try {
            InputStream in = getAssets().open(name);
            String encoding = mime.startsWith("text/") || mime.endsWith("javascript")
                    || mime.endsWith("json") || mime.endsWith("xml") ? "utf-8" : null;
            return new WebResourceResponse(mime, encoding, in);
        } catch (IOException error) {
            return null;
        }
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String mime = MIME.get(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    private boolean isShellHost(Uri uri) {
        String host = uri.getHost();
        return host != null && host.equalsIgnoreCase(HOST);
    }

    private class ShellClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && isShellHost(uri)) {
                return serveAsset(uri);
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null || "blob".equals(uri.getScheme()) || "data".equals(uri.getScheme())) {
                return false;
            }
            if (isShellHost(uri)) {
                return false;
            }
            // 外部链接交给系统浏览器，App 内不跳走
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // 没有可处理的应用时保持当前页面
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            logShell("页面加载完成");
            if (patchScript != null && patchScript.length() > 0) {
                view.evaluateJavascript(patchScript, null);
            }
        }
    }

    private class ShellChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                        FileChooserParams params) {
            logShell("原生文件选择器被调用");
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_file)),
                        REQ_FILE_CHOOSER);
                return true;
            } catch (Exception error) {
                logShell("文件选择器启动失败：" + error);
                fileCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            logShell("页面请求权限：" + java.util.Arrays.toString(request.getResources()));
            boolean wantsAudio = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsAudio = true;
                }
            }
            if (!wantsAudio) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                return;
            }
            audioRequest = request;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQ_AUDIO_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_AUDIO_PERMISSION) {
            return;
        }
        PermissionRequest request = audioRequest;
        audioRequest = null;
        if (request == null) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
        } else {
            request.deny();
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            int picked = data == null ? -1 : (data.getClipData() != null
                    ? data.getClipData().getItemCount()
                    : (data.getData() != null ? 1 : 0));
            logShell("文件选择返回 resultCode=" + resultCode + " 文件数=" + picked);
            if (fileCallback == null) {
                return;
            }
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[] { data.getData() };
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 返回键：先按 Esc 关掉设置等弹窗，其次退网页历史，最后退出。 */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){var d=document.querySelector('[role=\"dialog\"]');"
                        + "if(d){document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));"
                        + "return 'dialog';}return 'none';})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value != null && value.contains("dialog")) {
                            return;
                        }
                        if (webView.canGoBack()) {
                            webView.goBack();
                            return;
                        }
                        finish();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 网页里的下载（导出历史 ZIP）走的是 blob: + a[download]，WebView 自己存不了，
     * 由注入脚本读成 base64 后交给这里写入系统下载目录。
     */
    private class Bridge {
        /** 列出备份目录里的 ZIP：JSON 数组 [{name,size,modified}]，按修改时间倒序 */
        @JavascriptInterface
        public String listBackups() {
            try {
                StringBuilder out = new StringBuilder("[");
                boolean first = true;
                for (BackupFile file : queryBackups()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append("{\"name\":").append(JSONObject.quote(file.name))
                            .append(",\"size\":").append(file.size)
                            .append(",\"modified\":").append(file.modified)
                            .append('}');
                }
                return out.append(']').toString();
            } catch (Exception error) {
                logShell("备份列表读取失败：" + error);
                return "[]";
            }
        }

        /** 删除指定备份，返回成功删除的数量 */
        @JavascriptInterface
        public int deleteBackups(String namesJson) {
            int removed = 0;
            try {
                org.json.JSONArray names = new org.json.JSONArray(namesJson);
                for (int index = 0; index < names.length(); index++) {
                    if (deleteBackup(names.getString(index))) {
                        removed += 1;
                    }
                }
            } catch (Exception error) {
                logShell("删除备份失败：" + error);
            }
            return removed;
        }

        /** 读取备份的一段字节（base64），供页面分片拼回 Blob 做回滚 */
        @JavascriptInterface
        public String readBackupChunk(String name, int offset, int length) {
            try {
                BackupFile file = findBackup(name);
                if (file == null) {
                    return null;
                }
                byte[] buffer = new byte[length];
                int read = 0;
                try (InputStream in = openBackupStream(file)) {
                    long skipped = 0;
                    while (skipped < offset) {
                        long step = in.skip(offset - skipped);
                        if (step <= 0) {
                            break;
                        }
                        skipped += step;
                    }
                    while (read < length) {
                        int chunk = in.read(buffer, read, length - read);
                        if (chunk <= 0) {
                            break;
                        }
                        read += chunk;
                    }
                }
                if (read <= 0) {
                    return null;
                }
                return Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
            } catch (Exception error) {
                logShell("读取备份失败：" + error);
                return null;
            }
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safeName = sanitize(name);
                String saved = writeToDownloads(safeName, mime, bytes);
                final String message = saved != null
                        ? getString(R.string.saved_to, saved)
                        : getString(R.string.save_failed);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, R.string.save_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    private static String sanitize(String name) {
        String value = name == null || name.length() == 0 ? "chat-demo.zip" : name;
        value = value.replace('/', '_').replace('\\', '_').replace(':', '_');
        return value;
    }

    private String writeToDownloads(String name, String mime, byte[] bytes) {
        String type = mime == null || mime.length() == 0 ? "application/octet-stream" : mime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, type);
            values.put(MediaStore.Downloads.RELATIVE_PATH, BACKUP_RELATIVE);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) {
                return null;
            }
            OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(target);
                if (out == null) {
                    return null;
                }
                out.write(bytes);
                out.flush();
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(target, values, null, null);
                return BACKUP_RELATIVE + "/" + name;
            } catch (IOException error) {
                getContentResolver().delete(target, null, null);
                return null;
            } finally {
                closeQuietly(out);
            }
        }
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_SUBDIR);
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(bytes);
            out.flush();
            return target.getAbsolutePath();
        } catch (IOException error) {
            return null;
        } finally {
            closeQuietly(out);
        }
    }
}
