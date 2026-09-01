package com.hyunjanggam.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.*;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.Base64;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int PERM_REQUEST    = 100;
    private static final int FILE_CHOOSER    = 102;
    private static final int MANAGE_STORAGE  = 103;

    private File baseDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 기본 경로: 내부저장소/현장캠/
        baseDir = new File(Environment.getExternalStorageDirectory(), "현장캠");
        ensureDir(baseDir);
        ensureDir(new File(baseDir, "내보내기"));

        webView = findViewById(R.id.webview);
        setupWebView();
        requestAllPermissions();
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback cb) { cb.invoke(origin, true, false); }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                req.grant(req.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb,
                    FileChooserParams p) {
                filePathCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_CHOOSER); }
                catch (Exception e) { filePathCallback = null; return false; }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
        });
    }

    private void requestAllPermissions() {
        // Android 11+ 전체 파일 접근 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, MANAGE_STORAGE);
                } catch (Exception e) {
                    Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(i, MANAGE_STORAGE);
                }
            }
        }
        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        boolean ok = true;
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, perms, PERM_REQUEST);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    // ── 유틸 ──
    private void ensureDir(File d) { if (!d.exists()) d.mkdirs(); }

    private String readFileContent(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private void writeFileContent(File f, String text) throws IOException {
        try (FileWriter fw = new FileWriter(f)) { fw.write(text); }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteRecursive(x); }
        f.delete();
    }

    // ── JavascriptInterface ──
    class Bridge {

        /** 앱 기본 경로 반환 */
        @JavascriptInterface
        public String getBasePath() { return baseDir.getAbsolutePath(); }

        // ── 프로젝트 ──

        @JavascriptInterface
        public String listProjects() {
            try {
                JSONArray arr = new JSONArray();
                File[] dirs = baseDir.listFiles(File::isDirectory);
                if (dirs == null) return "[]";
                java.util.Arrays.sort(dirs);
                for (File d : dirs) {
                    if (d.getName().equals("내보내기")) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName());
                    File meta = new File(d, "project.json");
                    if (meta.exists()) {
                        JSONObject m = new JSONObject(readFileContent(meta));
                        o.put("color", m.optString("color", "#FF6B00"));
                        o.put("createdAt", m.optString("createdAt", ""));
                    } else { o.put("color", "#FF6B00"); }
                    // 사진 수
                    File pd = new File(d, "사진");
                    int cnt = 0;
                    if (pd.exists()) { File[] jps = pd.listFiles(f -> f.getName().endsWith(".jpg")); cnt = jps != null ? jps.length : 0; }
                    o.put("photoCount", cnt);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void createProject(String name, String color) {
            try {
                File pd = new File(baseDir, name);
                ensureDir(pd);
                ensureDir(new File(pd, "사진"));
                ensureDir(new File(pd, "레이어"));
                JSONObject m = new JSONObject();
                m.put("color", color);
                m.put("createdAt", new java.util.Date().toInstant().toString());
                writeFileContent(new File(pd, "project.json"), m.toString());
            } catch (Exception e) { uiToast("프로젝트 생성 실패: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void deleteProject(String name) {
            deleteRecursive(new File(baseDir, name));
        }

        @JavascriptInterface
        public void updateProjectColor(String name, String color) {
            try {
                File meta = new File(new File(baseDir, name), "project.json");
                JSONObject m = meta.exists() ? new JSONObject(readFileContent(meta)) : new JSONObject();
                m.put("color", color);
                writeFileContent(meta, m.toString());
            } catch (Exception e) {}
        }

        // ── 사진 ──

        @JavascriptInterface
        public void savePhoto(String base64, String projectName, String filename, String metaJson) {
            try {
                byte[] data = Base64.getDecoder().decode(base64.split(",")[1]);
                File photoDir = new File(new File(baseDir, projectName), "사진");
                ensureDir(photoDir);
                try (FileOutputStream fo = new FileOutputStream(new File(photoDir, filename))) { fo.write(data); }
                writeFileContent(new File(photoDir, filename.replace(".jpg", ".json")), metaJson);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "📸 저장됨", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { uiToast("저장 실패: " + e.getMessage()); }
        }

        @JavascriptInterface
        public String listPhotos(String projectName) {
            try {
                JSONArray arr = new JSONArray();
                File pd = new File(new File(baseDir, projectName), "사진");
                if (!pd.exists()) return "[]";
                File[] jpgs = pd.listFiles(f -> f.getName().endsWith(".jpg"));
                if (jpgs == null) return "[]";
                java.util.Arrays.sort(jpgs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File f : jpgs) {
                    JSONObject o = new JSONObject();
                    o.put("filename", f.getName());
                    o.put("path", f.getAbsolutePath());
                    o.put("project", projectName);
                    File mf = new File(pd, f.getName().replace(".jpg", ".json"));
                    if (mf.exists()) {
                        JSONObject m = new JSONObject(readFileContent(mf));
                        o.put("lat",  m.optDouble("lat", 0));
                        o.put("lon",  m.optDouble("lon", 0));
                        o.put("alt",  m.optDouble("alt", 0));
                        o.put("acc",  m.optDouble("acc", 0));
                        o.put("time", m.optString("time", ""));
                        o.put("description",  m.optString("description", ""));
                        o.put("projectName",  m.optString("projectName", projectName));
                        o.put("projectColor", m.optString("projectColor", "#FF6B00"));
                    }
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void deletePhoto(String path) {
            new File(path).delete();
            new File(path.replace(".jpg", ".json")).delete();
        }

        // ── 레이어 ──

        @JavascriptInterface
        public void saveLayer(String projectName, String name, String geojson, String metaJson) {
            try {
                File ld = new File(new File(baseDir, projectName), "레이어");
                ensureDir(ld);
                writeFileContent(new File(ld, name + ".geojson"), geojson);
                writeFileContent(new File(ld, name + ".meta.json"), metaJson);
            } catch (Exception e) { uiToast("레이어 저장 실패: " + e.getMessage()); }
        }

        @JavascriptInterface
        public String listLayers(String projectName) {
            try {
                JSONArray arr = new JSONArray();
                File ld = new File(new File(baseDir, projectName), "레이어");
                if (!ld.exists()) return "[]";
                File[] files = ld.listFiles(f -> f.getName().endsWith(".geojson"));
                if (files == null) return "[]";
                java.util.Arrays.sort(files);
                for (File f : files) {
                    String baseName = f.getName().replace(".geojson", "");
                    JSONObject o = new JSONObject();
                    o.put("name", baseName);
                    o.put("project", projectName);
                    o.put("geojsonPath", f.getAbsolutePath());
                    File mf = new File(ld, baseName + ".meta.json");
                    if (mf.exists()) {
                        JSONObject m = new JSONObject(readFileContent(mf));
                        o.put("color",   m.optString("color",   "#4CAF50"));
                        o.put("visible", m.optBoolean("visible", true));
                        o.put("symbol",  m.optString("symbol",  "circle"));
                        o.put("order",   m.optInt("order", 0));
                        if (m.has("srcFile") && m.optString("srcFile", "").length() > 0)
                            o.put("srcFile", m.optString("srcFile", ""));
                    } else { o.put("color", "#4CAF50"); o.put("visible", true); }
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String readText(String path) {
            try { return readFileContent(new File(path)); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void updateLayerMeta(String geojsonPath, String metaJson) {
            try {
                File mf = new File(geojsonPath.replace(".geojson", ".meta.json"));
                writeFileContent(mf, metaJson);
            } catch (Exception e) {}
        }

        @JavascriptInterface
        public void deleteLayer(String geojsonPath) {
            new File(geojsonPath).delete();
            new File(geojsonPath.replace(".geojson", ".meta.json")).delete();
        }

        // ── 파일 내보내기 ──

        @JavascriptInterface
        public void saveFile(String base64, String filename, String mimeType) {
            try {
                String b64 = base64.contains(",") ? base64.split(",")[1] : base64;
                byte[] data = Base64.getDecoder().decode(b64);
                File exportDir = new File(baseDir, "내보내기");
                ensureDir(exportDir);
                try (FileOutputStream fo = new FileOutputStream(new File(exportDir, filename))) { fo.write(data); }
                uiToast("✅ " + filename + "\n📁 현장캠/내보내기/ 에 저장됨");
            } catch (Exception e) { uiToast("저장 실패: " + e.getMessage()); }
        }

        // ── USB로 넣은 레이어 파일 읽기 ──

        @JavascriptInterface
        public String listRawLayerFiles(String projectName) {
            try {
                JSONArray arr = new JSONArray();
                // 프로젝트 루트부터 하위 폴더까지 재귀 스캔 (USB로 폴더째 넣은 파일 지원)
                File projDir = new File(baseDir, projectName);
                if (!projDir.exists()) return "[]";
                collectLayerFiles(projDir, 0, arr);
                return arr.toString();
            } catch (Exception e) { return "[]"; }
        }

        // 레이어 파일 재귀 수집 (최대 4단계 깊이)
        private void collectLayerFiles(File dir, int depth, JSONArray arr) {
            if (depth > 3) return;               // 무한 방지: 4단계까지
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) {
                    String dn = f.getName();
                    // 앱이 만든 폴더/시스템 폴더는 건너뜀
                    // (사진=촬영본, 내보내기=내보낸 파일, 레이어=앱이 변환한 복사본 - 재스캔 시 원본처럼 잡히는 것 방지)
                    if (dn.equals("사진") || dn.equals("내보내기") || dn.equals("레이어") || dn.startsWith(".")) continue;
                    collectLayerFiles(f, depth + 1, arr);
                    continue;
                }
                String n = f.getName().toLowerCase();
                if (n.equals("project.json")) continue;
                if (n.endsWith(".meta.json")) continue;
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) continue;
                if (n.endsWith(".qmd") || n.endsWith(".sbx") || n.endsWith(".sbn")) continue;
                // .cpg는 포함: JS에서 한글 인코딩(cp949) 감지에 사용
                String ext = n.contains(".") ? n.substring(n.lastIndexOf(".")+1) : "";
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("path", f.getAbsolutePath());
                    o.put("ext", ext);
                    arr.put(o);
                } catch (Exception e) { continue; }
            }
        }

        @JavascriptInterface
        public String readFileAsBase64(String path) {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(new File(path).toPath());
                return Base64.getEncoder().encodeToString(data);
            } catch (Exception e) { return ""; }
        }

        // URL에서 파일 다운로드 (CORS 없이 Android 네이티브로)
        @JavascriptInterface
        public String fetchUrl(String urlStr) {
            try {
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 HyunJangCam/1.0");
                int code = conn.getResponseCode();
                if (code != 200) return "error:HTTP " + code;
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                is.close();
                // KMZ는 base64로, 나머지는 텍스트로 반환
                String ct = conn.getContentType() != null ? conn.getContentType() : "";
                boolean isBinary = ct.contains("zip") || ct.contains("octet") ||
                    urlStr.toLowerCase().contains(".kmz");
                if (isBinary) return "base64:" + Base64.getEncoder().encodeToString(baos.toByteArray());
                return "text:" + baos.toString("UTF-8");
            } catch (Exception e) { return "error:" + e.getMessage(); }
        }

        private void uiToast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }
}
