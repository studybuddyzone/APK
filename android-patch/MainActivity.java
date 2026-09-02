package com.xevrontech.studybuddyzone;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;
import java.net.URISyntaxException;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {

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
