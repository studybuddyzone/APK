package com.xevrontech.studybuddyzone;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {

    // ---- Native file chooser state (<input type="file"> support) ----
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ValueCallback<Uri[]> pendingFileCallback;
    private WebChromeClient.FileChooserParams pendingParams;
    private final List<File> pendingCameraFiles = new ArrayList<>();
    private final List<Uri> pendingCameraUris = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Block screenshots and screen recording, and cause the app content
        // to render as a black rectangle in any screen-share/cast/mirroring
        // session (Meet, Zoom, Gemini Live screen share, built-in Android
        // screen recorder, etc.). This is the standard Android mechanism —
        // apps cannot distinguish "screenshot" from "screen recording" from
        // "live screen share to an AI tool"; all of these go through the
        // same capture surface, and FLAG_SECURE blocks all of them uniformly.
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );

        WebView webView = this.bridge.getWebView();

        // ---- Native Camera / Gallery / Files picker for <input type="file"> ----
        // No storage permissions are needed: the system picker (SAF) and the
        // system camera app are used, so the app never reads storage directly.
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onFileChooserResult(result.getResultCode(), result.getData())
        );
        cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> launchFileChooser(granted)
        );
        cleanOldUploadCache();

        // Extend Capacitor's own WebChromeClient so every other behaviour
        // (permissions, geolocation, JS dialogs, console) is preserved.
        webView.setWebChromeClient(new BridgeWebChromeClient(this.bridge) {
            @Override
            public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
            ) {
                return handleShowFileChooser(filePathCallback, fileChooserParams);
            }
        });

        // Extend Capacitor's own WebViewClient so the JS bridge keeps working,
        // but intercept UPI / payment-app intent URLs that Cashfree tries to launch.
        webView.setWebViewClient(new BridgeWebViewClient(this.bridge) {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isPaymentAppScheme(url)) {
                    launchExternalPaymentApp(url);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }
        });
    }

    // =====================================================================
    //  Native file chooser: Camera + Gallery + Files (images, PDFs, docs)
    // =====================================================================

    private boolean handleShowFileChooser(
        ValueCallback<Uri[]> callback,
        WebChromeClient.FileChooserParams params
    ) {
        // A new request supersedes any unfinished one; the callback must always be answered.
        finishFileChooser(null);
        pendingFileCallback = callback;
        pendingParams = params;

        // Only ask for CAMERA at runtime if some library declared it in the merged manifest.
        // (If it is not declared, the system camera app is launched with no permission at all.)
        if (isCameraPermissionDeclared()
            && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            launchFileChooser(true);
        }
        return true;
    }

    private void launchFileChooser(boolean cameraAllowed) {
        WebChromeClient.FileChooserParams params = pendingParams;
        if (pendingFileCallback == null || params == null) return;

        String[] types = normalizeAcceptTypes(params.getAcceptTypes());
        boolean anyType = containsType(types, "*/*");
        boolean wantsImage = anyType || containsType(types, "image/");
        boolean wantsVideo = containsType(types, "video/");
        boolean multiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;

        List<Intent> cameraIntents = new ArrayList<>();
        if (cameraAllowed) {
            if (wantsImage) {
                Intent i = buildCameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, ".jpg", "photo_");
                if (i != null) cameraIntents.add(i);
            }
            if (containsType(types, "video/")) {
                Intent i = buildCameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, ".mp4", "video_");
                if (i != null) cameraIntents.add(i);
            }
        }

        try {
            // <input capture> -> go straight to the camera.
            if (params.isCaptureEnabled() && !cameraIntents.isEmpty()) {
                Intent direct = (wantsVideo && !wantsImage) ? cameraIntents.get(cameraIntents.size() - 1) : cameraIntents.get(0);
                fileChooserLauncher.launch(direct);
                return;
            }

            Intent content = new Intent(Intent.ACTION_GET_CONTENT);
            content.addCategory(Intent.CATEGORY_OPENABLE);
            if (anyType || types.length > 1) {
                content.setType("*/*");
                if (!anyType) content.putExtra(Intent.EXTRA_MIME_TYPES, types);
            } else {
                content.setType(types[0]);
            }
            if (multiple) content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            Intent chooser = Intent.createChooser(content, null);
            if (!cameraIntents.isEmpty()) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toArray(new Intent[0]));
            }
            fileChooserLauncher.launch(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            e.printStackTrace();
            finishFileChooser(null);
        }
    }

    private void onFileChooserResult(int resultCode, Intent data) {
        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                // Gallery / Files selection (single or multiple)
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else {
                // Camera capture: the file we passed as EXTRA_OUTPUT
                for (int i = 0; i < pendingCameraFiles.size(); i++) {
                    File f = pendingCameraFiles.get(i);
                    if (f.exists() && f.length() > 0) {
                        result = new Uri[] { pendingCameraUris.get(i) };
                        break;
                    }
                }
            }
        }
        finishFileChooser(result);
    }

    /** Answers the WebView callback exactly once and removes unused camera temp files. */
    private void finishFileChooser(Uri[] result) {
        ValueCallback<Uri[]> cb = pendingFileCallback;
        pendingFileCallback = null;
        pendingParams = null;

        for (int i = 0; i < pendingCameraFiles.size(); i++) {
            File f = pendingCameraFiles.get(i);
            boolean used = result != null && result.length == 1 && result[0].equals(pendingCameraUris.get(i));
            if (!used && f.exists() && f.length() == 0) f.delete();
        }
        pendingCameraFiles.clear();
        pendingCameraUris.clear();

        if (cb != null) cb.onReceiveValue(result); // null == user cancelled
    }

    private Intent buildCameraIntent(String action, String extension, String prefix) {
        try {
            File dir = new File(getCacheDir(), "web_uploads");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = File.createTempFile(prefix, extension, dir);
            // Capacitor's default file_paths.xml already exposes the cache dir.
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(action);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            pendingCameraFiles.add(file);
            pendingCameraUris.add(uri);
            return intent;
        } catch (IOException | IllegalArgumentException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Turns accept="..." (mime types, wildcards, or .extensions) into a clean mime list. */
    private String[] normalizeAcceptTypes(String[] accept) {
        Set<String> out = new LinkedHashSet<>();
        if (accept != null) {
            for (String entry : accept) {
                if (entry == null) continue;
                for (String raw : entry.split(",")) {
                    String t = raw.trim().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) continue;
                    if (t.startsWith(".")) {
                        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1));
                        if (mime != null) out.add(mime);
                    } else if (t.contains("/")) {
                        out.add(t);
                    }
                }
            }
        }
        if (out.isEmpty()) out.add("*/*");
        return out.toArray(new String[0]);
    }

    private boolean containsType(String[] types, String match) {
        for (String t : types) {
            if (t.equals(match) || (match.endsWith("/") && t.startsWith(match))) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean isCameraPermissionDeclared() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            }
            if (info.requestedPermissions != null) {
                for (String p : info.requestedPermissions) {
                    if (Manifest.permission.CAMERA.equals(p)) return true;
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    /** Removes camera temp files older than a day. */
    private void cleanOldUploadCache() {
        File[] files = new File(getCacheDir(), "web_uploads").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File f : files) {
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    @Override
    public void onDestroy() {
        finishFileChooser(null);
        super.onDestroy();
    }

    private boolean isPaymentAppScheme(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("intent://")
            || lower.startsWith("upi://")
            || lower.startsWith("gpay://")
            || lower.startsWith("tez://")
            || lower.startsWith("phonepe://")
            || lower.startsWith("paytmmp://")
            || lower.startsWith("bhim://")
            || lower.startsWith("credpay://");
    }

    private void launchExternalPaymentApp(String url) {
        try {
            Intent intent;
            if (url.startsWith("intent://")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                // No UPI app installed that can handle this — fall back to Play Store
                // link if Cashfree provided one inside the intent (S.browser_fallback_url),
                // otherwise silently ignore so the WebView doesn't crash.
                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                if (fallbackUrl != null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)));
                }
            }
        } catch (URISyntaxException | ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }
}
