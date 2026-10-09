package com.xevrontech.studybuddyzone;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {

    public static final String APP_SERVER_URL = "https://studybuddypro-psi.vercel.app";

    // ---- Native file chooser state (<input type="file"> support) ----
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ValueCallback<Uri[]> pendingFileCallback;
    private WebChromeClient.FileChooserParams pendingParams;
    private final List<File> pendingCameraFiles = new ArrayList<>();
    private final List<Uri> pendingCameraUris = new ArrayList<>();

    // ---- Overlay & Startup State ----
    private FrameLayout loadingOverlayView;
    private LinearLayout overlayLoadingBox;
    private LinearLayout overlayErrorBox;
    private TextView overlayErrorMsg;
    private boolean isOverlayDismissed = false;
    private boolean hasPageError = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable loadTimeoutRunnable;
    private long lastBackPressTime = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep existing FLAG_SECURE behavior intact
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );

        // Ensure decor view and root background are clean white to prevent
        // black flash / black buffer during startup composition.
        getWindow().getDecorView().setBackgroundColor(Color.WHITE);

        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            webView.setBackgroundColor(Color.WHITE);
            configureWebViewSettings(webView);
        }

        // Setup native loading & error fallback overlay
        setupNativeLoadingOverlay(webView);

        // Setup Android back button handling
        setupBackButtonHandler(webView);

        // Setup Native Camera / Gallery / Files picker for <input type="file">
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onFileChooserResult(result.getResultCode(), result.getData())
        );
        cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> launchFileChooser(granted)
        );
        cleanOldUploadCache();

        if (webView != null) {
            // Enhanced WebChromeClient: supports file picker AND window.open / target="_blank"
            webView.setWebChromeClient(new BridgeWebChromeClient(this.bridge) {
                @Override
                public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
                ) {
                    return handleShowFileChooser(filePathCallback, fileChooserParams);
                }

                @Override
                public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    Message resultMsg
                ) {
                    return handleCreateWindow(view, resultMsg);
                }
            });

            // Enhanced BridgeWebViewClient: preserves Capacitor bridge while handling payments,
            // external links, and load error states.
            webView.setWebViewClient(new BridgeWebViewClient(this.bridge) {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (request != null && request.getUrl() != null) {
                        String url = request.getUrl().toString();
                        Boolean handled = interceptUrlLoading(view, url);
                        if (handled != null) {
                            return handled;
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, request);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (url != null) {
                        Boolean handled = interceptUrlLoading(view, url);
                        if (handled != null) {
                            return handled;
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, url);
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    hasPageError = false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!hasPageError) {
                        cancelTimeoutWatchdog();
                        hideLoadingOverlay();
                    }
                }

                @Override
                public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
                ) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame()) {
                        hasPageError = true;
                        cancelTimeoutWatchdog();
                        showOverlayError("Unable to connect to StudyBuddyZone.\nPlease check your internet connection.");
                    }
                }

                @Override
                public void onReceivedError(
                    WebView view,
                    int errorCode,
                    String description,
                    String failingUrl
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl);
                    hasPageError = true;
                    cancelTimeoutWatchdog();
                    showOverlayError("Unable to connect to StudyBuddyZone.\nPlease check your internet connection.");
                }

                @Override
                public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse errorResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse);
                    if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                        hasPageError = true;
                        cancelTimeoutWatchdog();
                        showOverlayError("Server response error (" + errorResponse.getStatusCode() + ").\nPlease try again.");
                    }
                }
            });
        }

        // Start timeout watchdog (20 seconds fallback for slow connections)
        startTimeoutWatchdog();
    }

    // =====================================================================
    //  WebView Settings & Cookie Configuration
    // =====================================================================

    private void configureWebViewSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    // =====================================================================
    //  Native Loading & Error Fallback Overlay
    // =====================================================================

    private void setupNativeLoadingOverlay(WebView webView) {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null && webView != null && webView.getParent() instanceof ViewGroup) {
            root = (ViewGroup) webView.getParent();
        }
        if (root == null) return;

        int dp8 = dpToPx(8);
        int dp16 = dpToPx(16);
        int dp24 = dpToPx(24);
        int dp80 = dpToPx(80);

        loadingOverlayView = new FrameLayout(this);
        loadingOverlayView.setBackgroundColor(Color.WHITE);
        loadingOverlayView.setClickable(true);
        loadingOverlayView.setFocusable(true);

        LinearLayout contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setGravity(Gravity.CENTER);
        contentBox.setPadding(dp24, dp24, dp24, dp24);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        contentBox.setLayoutParams(boxParams);

        // App Icon
        ImageView logoView = new ImageView(this);
        try {
            logoView.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Exception ignored) {}
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp80, dp80);
        logoParams.bottomMargin = dp16;
        logoView.setLayoutParams(logoParams);
        contentBox.addView(logoView);

        // App Title
        TextView titleView = new TextView(this);
        titleView.setText("StudyBuddyZone");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTextColor(Color.parseColor("#0f172a"));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dp16;
        titleView.setLayoutParams(titleParams);
        contentBox.addView(titleView);

        // Loading section
        overlayLoadingBox = new LinearLayout(this);
        overlayLoadingBox.setOrientation(LinearLayout.VERTICAL);
        overlayLoadingBox.setGravity(Gravity.CENTER);

        ProgressBar spinner = new ProgressBar(this);
        overlayLoadingBox.addView(spinner);

        TextView loadingText = new TextView(this);
        loadingText.setText("Connecting to StudyBuddyZone...");
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        loadingText.setTextColor(Color.parseColor("#64748b"));
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dp8;
        loadingText.setLayoutParams(textParams);
        overlayLoadingBox.addView(loadingText);

        contentBox.addView(overlayLoadingBox);

        // Error section (hidden by default)
        overlayErrorBox = new LinearLayout(this);
        overlayErrorBox.setOrientation(LinearLayout.VERTICAL);
        overlayErrorBox.setGravity(Gravity.CENTER);
        overlayErrorBox.setVisibility(View.GONE);

        overlayErrorMsg = new TextView(this);
        overlayErrorMsg.setText("Unable to connect to StudyBuddyZone.\nPlease check your connection.");
        overlayErrorMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        overlayErrorMsg.setTextColor(Color.parseColor("#64748b"));
        overlayErrorMsg.setGravity(Gravity.CENTER);
        overlayErrorMsg.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams errTextParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        errTextParams.bottomMargin = dp16;
        overlayErrorMsg.setLayoutParams(errTextParams);
        overlayErrorBox.addView(overlayErrorMsg);

        Button retryBtn = new Button(this);
        retryBtn.setText("Retry");
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        retryBtn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#2563eb"));
        btnBg.setCornerRadius(dpToPx(12));
        retryBtn.setBackground(btnBg);
        retryBtn.setPadding(dpToPx(28), dpToPx(12), dpToPx(28), dpToPx(12));

        retryBtn.setOnClickListener(v -> retryConnection());
        overlayErrorBox.addView(retryBtn);

        contentBox.addView(overlayErrorBox);
        loadingOverlayView.addView(contentBox);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        root.addView(loadingOverlayView, overlayParams);
    }

    private void hideLoadingOverlay() {
        if (isOverlayDismissed || loadingOverlayView == null) return;
        isOverlayDismissed = true;
        loadingOverlayView.animate()
            .alpha(0f)
            .setDuration(250)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (loadingOverlayView != null) {
                        loadingOverlayView.setVisibility(View.GONE);
                    }
                }
            });
    }

    private void showOverlayError(String msg) {
        if (loadingOverlayView == null) return;
        isOverlayDismissed = false;
        loadingOverlayView.setAlpha(1f);
        loadingOverlayView.setVisibility(View.VISIBLE);

        if (overlayLoadingBox != null) overlayLoadingBox.setVisibility(View.GONE);
        if (overlayErrorBox != null) {
            overlayErrorBox.setVisibility(View.VISIBLE);
            if (overlayErrorMsg != null && msg != null) {
                overlayErrorMsg.setText(msg);
            }
        }
    }

    private void retryConnection() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection detected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (overlayErrorBox != null) overlayErrorBox.setVisibility(View.GONE);
        if (overlayLoadingBox != null) overlayLoadingBox.setVisibility(View.VISIBLE);

        hasPageError = false;
        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            startTimeoutWatchdog();
            String currentUrl = webView.getUrl();
            if (currentUrl == null || currentUrl.isEmpty() || currentUrl.startsWith("file://") || currentUrl.startsWith("data:")) {
                webView.loadUrl(APP_SERVER_URL);
            } else {
                webView.reload();
            }
        }
    }

    private void startTimeoutWatchdog() {
        cancelTimeoutWatchdog();
        loadTimeoutRunnable = () -> {
            if (!isOverlayDismissed) {
                showOverlayError("Connection timed out.\nPlease tap Retry to try again.");
            }
        };
        mainHandler.postDelayed(loadTimeoutRunnable, 20000);
    }

    private void cancelTimeoutWatchdog() {
        if (loadTimeoutRunnable != null) {
            mainHandler.removeCallbacks(loadTimeoutRunnable);
            loadTimeoutRunnable = null;
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    // =====================================================================
    //  URL Interception & Navigation Handling
    // =====================================================================

    private Boolean interceptUrlLoading(WebView view, String url) {
        if (url == null || url.isEmpty()) return false;

        // Payment schemes / UPI / Cashfree intents
        if (isPaymentAppScheme(url)) {
            launchExternalPaymentApp(url);
            return true;
        }

        // Standard device intent schemes: tel, mailto, sms, whatsapp
        if (isExternalIntentScheme(url)) {
            launchExternalIntent(url);
            return true;
        }

        // Allowed internal web app domains
        if (isInternalOrAllowedUrl(url)) {
            // Return null so BridgeWebViewClient's super method handles it
            // while preserving Capacitor's JavaScript bridge.
            return null;
        }

        // External URLs: open outside WebView so user isn't trapped
        launchExternalBrowser(url);
        return true;
    }

    private boolean isExternalIntentScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("tel:")
            || lower.startsWith("mailto:")
            || lower.startsWith("sms:")
            || lower.startsWith("whatsapp:");
    }

    private void launchExternalIntent(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void launchExternalBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isInternalOrAllowedUrl(String url) {
        if (url.startsWith("capacitor://") || url.startsWith("http://localhost") || url.startsWith("about:")) {
            return true;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);

            List<String> allowedExact = Arrays.asList(
                "studybuddypro-psi.vercel.app",
                "accounts.google.com"
            );
            if (allowedExact.contains(host)) return true;

            List<String> allowedSuffixes = Arrays.asList(
                ".vercel.app",
                ".supabase.co",
                ".supabase.in",
                ".firebaseio.com",
                ".firebaseapp.com",
                ".googleapis.com",
                ".google.com",
                ".cashfree.com",
                ".cashfree.in",
                ".payu.in",
                ".payu.com",
                ".cloudinary.com"
            );
            for (String suffix : allowedSuffixes) {
                if (host.endsWith(suffix)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Handles links with target="_blank" and window.open(...) calls
    private boolean handleCreateWindow(WebView view, Message resultMsg) {
        WebView tempWebView = new WebView(MainActivity.this);
        tempWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (req != null && req.getUrl() != null) {
                    processNewWindowUrl(view, req.getUrl().toString());
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null) {
                    processNewWindowUrl(view, url);
                }
                return true;
            }
        });

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(tempWebView);
        resultMsg.sendToTarget();
        return true;
    }

    private void processNewWindowUrl(WebView mainView, String url) {
        if (isPaymentAppScheme(url)) {
            launchExternalPaymentApp(url);
        } else if (isExternalIntentScheme(url)) {
            launchExternalIntent(url);
        } else if (isInternalOrAllowedUrl(url)) {
            mainView.loadUrl(url);
        } else {
            launchExternalBrowser(url);
        }
    }

    // =====================================================================
    //  Android Back-Button Handling
    // =====================================================================

    private void setupBackButtonHandler(WebView webView) {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleUserBackPress(webView);
            }
        });
    }

    @Override
    public void onBackPressed() {
        WebView webView = this.bridge.getWebView();
        handleUserBackPress(webView);
    }

    private void handleUserBackPress(WebView webView) {
        if (loadingOverlayView != null && loadingOverlayView.getVisibility() == View.VISIBLE && overlayErrorBox.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }

        if (webView == null) {
            finish();
            return;
        }

        // Check if an open modal or overlay exists in the web page and dismiss it
        String jsCloseModal = "(function() {" +
            "var dialog = document.querySelector('dialog[open], [role=\"dialog\"][aria-modal=\"true\"], [role=\"dialog\"], .modal.show, .modal.open, [data-state=\"open\"]');" +
            "if (dialog && dialog.offsetParent !== null) {" +
            "  var closeBtn = dialog.querySelector('button[aria-label*=\"close\" i], button.close, [data-dismiss=\"modal\"]');" +
            "  if (closeBtn) { closeBtn.click(); return 'modal_closed'; }" +
            "  var event = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true });" +
            "  document.dispatchEvent(event);" +
            "  return 'modal_closed';" +
            "}" +
            "return 'no_modal';" +
            "})();";

        webView.evaluateJavascript(jsCloseModal, value -> {
            if ("\"modal_closed\"".equals(value)) {
                // Modal was successfully dismissed by back press
                return;
            }

            // If no modal was open, check WebView history
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                // Root / Home page: press back again within 2 seconds to exit
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < 2000) {
                    finish();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();
                }
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
        finishFileChooser(null);
        pendingFileCallback = callback;
        pendingParams = params;

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
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else {
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

        if (cb != null) cb.onReceiveValue(result);
    }

    private Intent buildCameraIntent(String action, String extension, String prefix) {
        try {
            File dir = new File(getCacheDir(), "web_uploads");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = File.createTempFile(prefix, extension, dir);
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
        cancelTimeoutWatchdog();
        finishFileChooser(null);
        super.onDestroy();
    }

    private boolean isPaymentAppScheme(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
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
