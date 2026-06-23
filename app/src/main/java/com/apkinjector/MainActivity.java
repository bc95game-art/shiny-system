package com.apkinjector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    private static final int REQ_APK        = 1001;
    private static final int REQ_ZIP        = 1002;
    private static final int REQ_PERM_WRITE = 1003;
    private static final int REQ_MANAGE_EXT = 1004;

    // volatile: written/read from different threads
    private volatile String pendingJsCallback = null;
    private volatile File   resultApkFile     = null;

    // ─────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        initWebView();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void initWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setTextZoom(100);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        // Use static inner class with WeakReference to avoid memory leak
        webView.addJavascriptInterface(new JsBridge(this), "Android");
    }

    // ─────────────────────────────────────
    // JavaScript Bridge (static — no leak)
    // ─────────────────────────────────────

    private static class JsBridge {
        private final WeakReference<MainActivity> ref;

        JsBridge(MainActivity activity) {
            ref = new WeakReference<>(activity);
        }

        private MainActivity get() { return ref.get(); }

        /** FIX: startActivityForResult MUST run on the UI thread */
        @JavascriptInterface
        public void pickApk(String callback) {
            MainActivity a = get();
            if (a == null) return;
            a.pendingJsCallback = callback;
            a.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/vnd.android.package-archive",
                                 "application/octet-stream"});
                try {
                    a.startActivityForResult(
                        Intent.createChooser(intent, "انتخاب فایل APK"), REQ_APK);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در باز کردن انتخابگر فایل: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void pickZip(String callback) {
            MainActivity a = get();
            if (a == null) return;
            a.pendingJsCallback = callback;
            a.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/x-zip-compressed",
                                 "multipart/x-zip", "application/octet-stream"});
                try {
                    a.startActivityForResult(
                        Intent.createChooser(intent, "انتخاب پروژه ZIP"), REQ_ZIP);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در باز کردن انتخابگر فایل: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void startInjection(String apkPath, String zipPath) {
            MainActivity a = get();
            if (a == null) return;
            // FIX: named thread for easier debugging; daemon so it doesn't block app exit
            Thread t = new Thread(() -> {
                try {
                    a.runInjection(apkPath, zipPath);
                } catch (InterruptedException ie) {
                    // FIX: restore interrupt flag; don't show error to user on cancellation
                    Thread.currentThread().interrupt();
                } catch (Throwable err) {
                    String msg = err.getMessage() != null
                        ? err.getMessage() : err.getClass().getSimpleName();
                    a.jsEvent("onInjectError", msg);
                }
            }, "APK-Injector-Worker");
            t.setDaemon(true);
            t.start();
        }

        @JavascriptInterface
        public void installApk() {
            MainActivity a = get();
            if (a == null) return;
            if (a.resultApkFile == null || !a.resultApkFile.exists()) {
                a.runOnUiThread(() ->
                    Toast.makeText(a, "فایل APK یافت نشد", Toast.LENGTH_SHORT).show());
                return;
            }
            a.runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(
                        a, a.getPackageName() + ".fileprovider", a.resultApkFile);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                             | Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در نصب: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void saveApk() {
            MainActivity a = get();
            if (a == null) return;
            if (a.resultApkFile == null || !a.resultApkFile.exists()) {
                a.jsEvent("onSaveError", "فایل نتیجه وجود ندارد");
                return;
            }
            a.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        a.saveToDownloads();
                    } else {
                        Intent i = new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + a.getPackageName()));
                        a.startActivityForResult(i, REQ_MANAGE_EXT);
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(a,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(a,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_PERM_WRITE);
                    } else {
                        a.saveToDownloads();
                    }
                }
            });
        }

        @JavascriptInterface
        public void shareApk() {
            MainActivity a = get();
            if (a == null || a.resultApkFile == null || !a.resultApkFile.exists()) return;
            a.runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(
                        a, a.getPackageName() + ".fileprovider", a.resultApkFile);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/vnd.android.package-archive");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(share, "اشتراک‌گذاری APK"));
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در اشتراک‌گذاری: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            return Build.VERSION.SDK_INT + "|" + Build.MODEL + "|" + Build.VERSION.RELEASE;
        }

        @JavascriptInterface
        public void reset() {
            MainActivity a = get();
            if (a == null) return;
            new Thread(() -> {
                File cache = a.getCacheDir();
                File[] files = cache.listFiles((dir, name) ->
                    name.startsWith("apk_input_")
                    || name.startsWith("zip_input_")
                    || name.startsWith("injected_"));
                if (files != null) for (File f : files) f.delete();
                a.resultApkFile = null;
            }).start();
        }
    }

    // ─────────────────────────────────────
    // Core Injection Engine
    // ─────────────────────────────────────

    private void runInjection(String apkPath, String zipPath) throws Exception {
        File apkFile = new File(apkPath);
        File zipFile = new File(zipPath);

        if (!apkFile.exists()) throw new Exception("APK یافت نشد: " + apkPath);
        if (!zipFile.exists()) throw new Exception("ZIP یافت نشد: " + zipPath);

        // ── Phase 1: Analyze & Read APK ──
        jsProgress("analyze", 0, "در حال آنالیز APK...");
        Map<String, EntryData> apkMap = new LinkedHashMap<>();
        long apkRawSize = apkFile.length();

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(apkFile), 65536))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && !name.isEmpty()) {
                    byte[] data = readStream(zis);
                    apkMap.put(name, new EntryData(data, entry.getMethod()));
                    count++;
                    if (count % 30 == 0) {
                        jsProgress("analyze",
                            Math.min(28, 2 + count / 5),
                            "خواندن APK: " + count + " فایل");
                    }
                }
                zis.closeEntry();
            }
        }
        jsProgress("analyze", 30,
            "APK آنالیز شد ← " + apkMap.size() + " فایل در " + formatBytes(apkRawSize));
        Thread.sleep(150);

        // ── Phase 2: Read Injection ZIP ──
        jsProgress("inject", 32, "در حال خواندن پروژه تزریق...");

        // FIX: detectTopFolder now validates ALL entries
        String topFolder = detectTopFolderRobust(zipFile);

        Map<String, EntryData> zipMap = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile), 65536))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (!entry.isDirectory() && !rawName.isEmpty()) {
                    String name = stripTopFolder(rawName, topFolder);
                    if (!name.isEmpty()) {
                        byte[] data = readStream(zis);
                        zipMap.put(name, new EntryData(data, ZipEntry.DEFLATED));
                        count++;
                        if (count % 10 == 0) {
                            jsProgress("inject",
                                Math.min(48, 32 + count),
                                "خواندن پروژه: " + count + " فایل");
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        jsProgress("inject", 50,
            "پروژه تزریق خوانده شد ← " + zipMap.size() + " فایل");
        Thread.sleep(150);

        // ── Phase 3: Smart Merge ──
        jsProgress("merge", 52, "ترکیب هوشمند فایل‌ها...");
        int overridden = 0, added = 0, skipped = 0;

        for (Map.Entry<String, EntryData> ze : zipMap.entrySet()) {
            String rawKey = ze.getKey();

            // Skip shell scripts and metadata
            if (shouldSkip(rawKey)) {
                skipped++;
                continue;
            }

            // FIX: improved smart path remapping
            String targetKey = remapPath(rawKey, apkMap.keySet());

            if (apkMap.containsKey(targetKey)) {
                overridden++;
            } else {
                added++;
            }
            apkMap.put(targetKey, ze.getValue());
        }

        jsProgress("merge", 65,
            added + " اضافه + " + overridden + " جایگزین + " + skipped + " نادیده");
        Thread.sleep(250);

        // ── Phase 4: Write Output APK ──
        jsProgress("write", 67, "در حال نوشتن APK نهایی...");
        File outDir = getCacheDir();
        String outName = "injected_" + System.currentTimeMillis() + ".apk";
        resultApkFile = new File(outDir, outName);
        if (resultApkFile.exists()) resultApkFile.delete();

        int totalEntries = apkMap.size();
        int written = 0;

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(resultApkFile), 65536))) {

            for (Map.Entry<String, EntryData> e : apkMap.entrySet()) {
                ZipEntry outEntry = new ZipEntry(e.getKey());
                EntryData ed = e.getValue();

                if (ed.method == ZipEntry.STORED) {
                    // STORED entries need CRC and sizes set beforehand
                    CRC32 crc = new CRC32();
                    crc.update(ed.data);
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(ed.data.length);
                    outEntry.setCompressedSize(ed.data.length);
                    outEntry.setCrc(crc.getValue());
                    zos.setLevel(Deflater.NO_COMPRESSION);
                } else {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION);
                }

                zos.putNextEntry(outEntry);
                zos.write(ed.data);
                zos.closeEntry();

                written++;
                if (written % 40 == 0 || written == totalEntries) {
                    int pct = 67 + (int)(((float) written / totalEntries) * 28);
                    jsProgress("write", Math.min(94, pct),
                        "نوشتن: " + written + "/" + totalEntries);
                }
            }
        }

        jsProgress("done", 97, "در حال نهایی‌سازی...");
        Thread.sleep(350);

        long outSize = resultApkFile.length();
        double ratio = apkRawSize > 0 ? (double) outSize / apkRawSize * 100 : 100;
        jsProgress("done", 100, "تزریق کامل شد! ✓");
        Thread.sleep(150);

        String payload = resultApkFile.getAbsolutePath()
            + "|" + formatBytes(outSize)
            + "|" + added
            + "|" + overridden
            + "|" + totalEntries
            + "|" + String.format("%.1f", ratio);
        jsEvent("onInjectDone", payload);
    }

    // ─────────────────────────────────────
    // Injection Logic Helpers
    // ─────────────────────────────────────

    private static class EntryData {
        final byte[] data;
        final int    method;
        EntryData(byte[] d, int m) { data = d; method = m; }
    }

    /**
     * FIX: Detects a common top-level folder ONLY if ALL entries share it.
     * If any root-level file exists → returns null (no stripping).
     */
    private String detectTopFolderRobust(File zip) {
        String topFolder = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                // FIX: normalize Windows backslash separators (ZIP spec requires '/')
                String name = e.getName().replace('\\', '/');
                // Skip pure directory entries
                if (e.isDirectory() || name.endsWith("/")) { zis.closeEntry(); continue; }
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    // Root-level file → cannot strip any top folder
                    return null;
                }
                String candidate = name.substring(0, slash + 1);
                if (topFolder == null) {
                    topFolder = candidate;
                } else if (!name.startsWith(topFolder)) {
                    // Multiple different top-level folders
                    return null;
                }
                zis.closeEntry();
            }
        } catch (Exception ignored) {}
        return topFolder;
    }

    private String stripTopFolder(String name, String topFolder) {
        // FIX: also normalize backslash (Windows ZIPs) before stripping
        String normalized = name.replace('\\', '/');
        if (topFolder != null && normalized.startsWith(topFolder)) {
            return normalized.substring(topFolder.length());
        }
        return normalized;
    }

    /** Files that should never be injected into an APK */
    private boolean shouldSkip(String path) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        // Script / metadata / IDE files
        if (lower.endsWith(".sh")
            || lower.endsWith(".bat")
            || lower.endsWith(".cmd")
            || lower.endsWith(".py")
            || lower.endsWith(".rb")
            || lower.endsWith(".md")
            || lower.endsWith(".txt")
            || lower.endsWith(".gradle")
            || lower.endsWith(".gitignore")
            || lower.endsWith(".gitattributes")
            || lower.endsWith(".iml")      // IntelliJ module files
            || lower.endsWith(".class")    // Java class files (not valid in APK directly)
            || lower.endsWith(".ds_store") // macOS metadata
            || lower.endsWith(".classpath")
            || lower.endsWith(".project")) {
            return true;
        }
        // Git internals and IDE config folders
        if (lower.contains("/.git/") || lower.startsWith(".git/")
            || lower.contains("/.idea/") || lower.startsWith(".idea/")
            || lower.contains("/__macosx/") || lower.startsWith("__macosx/")
            || lower.equals("readme")
            || lower.equals("license")
            || lower.equals("licence")) {
            return true;
        }
        return false;
    }

    /**
     * Smart path remapping — maps an injection file to the correct location in an APK.
     *
     * Priority order:
     * 1. Exact path match in APK → keep as-is (override)
     * 2. Path already in a known APK folder → keep as-is
     * 3. Known file types → map to correct APK location
     * 4. Unknown → keep as-is
     */
    private String remapPath(String injPath, Set<String> apkPaths) {
        // 1. Exact match — just override
        if (apkPaths.contains(injPath)) return injPath;

        String lp  = injPath.toLowerCase(Locale.ROOT);
        // Extract just the filename (no leading path)
        String fileName = injPath.contains("/")
            ? injPath.substring(injPath.lastIndexOf('/') + 1)
            : injPath;
        String fileNameLower = fileName.toLowerCase(Locale.ROOT);

        // 2. Already in a known APK top-level folder → keep path intact
        if (injPath.startsWith("res/")
                || injPath.startsWith("assets/")
                || injPath.startsWith("smali/")
                || injPath.startsWith("smali_classes")
                || injPath.startsWith("lib/")
                || injPath.startsWith("META-INF/")
                || injPath.startsWith("kotlin/")
                || injPath.startsWith("okhttp3/")
                || injPath.startsWith("com/")
                || injPath.startsWith("android/")) {
            return injPath;
        }

        // 3. AndroidManifest.xml → root of APK
        if (fileNameLower.equals("androidmanifest.xml")) {
            return "AndroidManifest.xml";
        }

        // 4. DEX files → always place at APK root (never in a subfolder)
        if (lp.endsWith(".dex")) {
            // FIX: return fileName (not injPath) so DEX always lands at APK root
            // even if the injection ZIP wrapped it in a subfolder
            if (fileNameLower.matches("classes\\d*\\.dex")) return fileName;
            // Non-standard name → find next free classesN.dex slot
            int slot = 2;
            while (apkPaths.contains("classes" + slot + ".dex")) slot++;
            return "classes" + slot + ".dex";
        }

        // 5. Smali files → map to smali/ preserving package structure
        if (lp.endsWith(".smali")) {
            // Preserve subfolder structure under smali/
            return "smali/" + injPath;
        }

        // 6. Java/Kotlin source → place in assets/src/ (won't break APK)
        if (lp.endsWith(".java") || lp.endsWith(".kt") || lp.endsWith(".kts")) {
            return "assets/src/" + injPath;
        }

        // 7. XML resources — map based on path context
        if (lp.endsWith(".xml")) {
            // Subfolder hints
            if (lp.contains("/layout/") || fileNameLower.contains("layout")) {
                return "res/layout/" + fileName;
            }
            if (lp.contains("/drawable/") || fileNameLower.contains("drawable")) {
                return "res/drawable/" + fileName;
            }
            if (lp.contains("/values/") || lp.contains("strings")
                    || lp.contains("colors") || lp.contains("dimens")
                    || lp.contains("styles") || lp.contains("themes")
                    || lp.contains("attrs")) {
                return "res/values/" + fileName;
            }
            if (lp.contains("/anim/") || fileNameLower.contains("anim")) {
                return "res/anim/" + fileName;
            }
            if (lp.contains("/xml/") || lp.contains("filepaths")
                    || lp.contains("network_security") || lp.contains("backup")) {
                return "res/xml/" + fileName;
            }
            if (lp.contains("/raw/")) {
                return "res/raw/" + fileName;
            }
            // Generic XML → res/xml/
            return "res/xml/" + fileName;
        }

        // 8. Images → res/drawable/
        if (lp.endsWith(".png") || lp.endsWith(".jpg") || lp.endsWith(".jpeg")
                || lp.endsWith(".webp") || lp.endsWith(".gif") || lp.endsWith(".svg")) {
            return "res/drawable/" + fileName;
        }

        // 9. Fonts → res/font/
        if (lp.endsWith(".ttf") || lp.endsWith(".otf") || lp.endsWith(".woff")
                || lp.endsWith(".woff2")) {
            return "res/font/" + fileName;
        }

        // 10. Native libraries → lib/
        if (lp.endsWith(".so")) {
            // Try to detect ABI from path
            if (lp.contains("arm64") || lp.contains("aarch64")) {
                return "lib/arm64-v8a/" + fileName;
            }
            if (lp.contains("armeabi")) {
                return "lib/armeabi-v7a/" + fileName;
            }
            if (lp.contains("x86_64")) {
                return "lib/x86_64/" + fileName;
            }
            if (lp.contains("x86")) {
                return "lib/x86/" + fileName;
            }
            return "lib/armeabi-v7a/" + fileName;
        }

        // 11. Web assets → assets/
        if (lp.endsWith(".html") || lp.endsWith(".js") || lp.endsWith(".css")
                || lp.endsWith(".json") || lp.endsWith(".wasm")) {
            return "assets/" + injPath;
        }

        // 12. Default: keep path as-is (add to APK without change)
        return injPath;
    }

    private byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private String formatBytes(long b) {
        if (b < 1024)           return b + " B";
        if (b < 1024 * 1024)   return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.2f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ─────────────────────────────────────
    // Save to Downloads
    // ─────────────────────────────────────

    private void saveToDownloads() {
        new Thread(() -> {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    jsEvent("onSaveError", "خطا در ایجاد پوشه دانلودها");
                    return;
                }
                String name = "APK_Injected_" + System.currentTimeMillis() + ".apk";
                File dest = new File(downloads, name);

                try (FileInputStream  in  = new FileInputStream(resultApkFile);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                // Notify MediaStore (deprecated but harmless on newer APIs)
                Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scan.setData(Uri.fromFile(dest));
                sendBroadcast(scan);

                jsEvent("onSaveDone",
                    dest.getAbsolutePath() + "|" + formatBytes(dest.length()));
            } catch (Exception e) {
                jsEvent("onSaveError", "خطا در ذخیره: " + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────
    // JS Communication
    // ─────────────────────────────────────

    private void jsProgress(String phase, int pct, String message) {
        String js = "window.__onProgress&&window.__onProgress('"
            + esc(phase) + "'," + pct + ",'" + esc(message) + "')";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void jsEvent(String name, String data) {
        String js = "window.__ev&&window.__ev('" + esc(name) + "','" + esc(data) + "')";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'",  "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("<",  "\\u003c")
                .replace(">",  "\\u003e");
    }

    // ─────────────────────────────────────
    // Activity Results
    // ─────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_MANAGE_EXT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                saveToDownloads();
            } else {
                jsEvent("onSaveError", "دسترسی به فضای ذخیره‌سازی رد شد");
            }
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (pendingJsCallback != null) {
                jsEvent("onFileCancelled", pendingJsCallback);
                pendingJsCallback = null;
            }
            return;
        }

        Uri uri = data.getData();
        String ext = (requestCode == REQ_APK) ? ".apk" : ".zip";
        String callbackName = pendingJsCallback;
        pendingJsCallback = null;

        new Thread(() -> {
            try {
                File temp = copyUriToCache(uri, ext);
                String fileName = queryFileName(uri);
                if (fileName == null || fileName.isEmpty()) fileName = "file" + ext;

                // FIX B: whitelist callback name to prevent any JS injection via malformed cb
                String defaultCb = (requestCode == REQ_APK) ? "onApkSelected" : "onZipSelected";
                String cb = "onApkSelected".equals(callbackName) || "onZipSelected".equals(callbackName)
                    ? callbackName : defaultCb;

                String js = "window." + cb + "&&window." + cb
                    + "('" + esc(temp.getAbsolutePath()) + "','"
                    + esc(fileName) + "'," + temp.length() + ")";
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });
            } catch (Exception e) {
                jsEvent("onError", "خطا در بارگذاری فایل: " + e.getMessage());
            }
        }).start();
    }

    private File copyUriToCache(Uri uri, String ext) throws IOException {
        String prefix = ext.equals(".apk") ? "apk_input_" : "zip_input_";
        File temp = new File(getCacheDir(), prefix + System.currentTimeMillis() + ext);
        try (InputStream in  = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("نمی‌توان فایل را باز کرد");
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return temp;
    }

    private String queryFileName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String path = uri.getPath();
        if (path != null) {
            int cut = path.lastIndexOf('/');
            if (cut >= 0) return path.substring(cut + 1);
        }
        return null;
    }

    // ─────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM_WRITE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                saveToDownloads();
            } else {
                jsEvent("onSaveError", "دسترسی به حافظه رد شد");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
