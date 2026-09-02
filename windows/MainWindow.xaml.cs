using Microsoft.UI.Xaml;
using System;
using System.Runtime.InteropServices;
using WinRT.Interop;

namespace StudyBuddyZoneWindows;

public sealed partial class MainWindow : Window
{
    // Same production URL the Android build loads via capacitor.config.json
    // "server.url". Kept identical on purpose so both platforms show the
    // exact same live app (see repo requirement: do not change this URL).
    private const string StartUrl = "https://studybuddypro-psi.vercel.app/";

    // Excludes this window's content from anything that captures the screen:
    // screenshots (PrtScn, Snipping Tool), screen recorders, and live
    // screen-share / remote-desktop sessions (Teams, Zoom, a browser tab
    // sharing to Gemini or any other AI tool, etc.). Windows has no API to
    // tell "who" is capturing — a screenshot tool and a live AI screen-share
    // both go through the same capture pipeline — so this flag blocks all of
    // them uniformly: the window shows up solid black in whatever is
    // recording it, while the person using the app still sees it normally.
    // Requires Windows 10 version 2004 (build 19041) or later. The return
    // value is checked below in case it's ever run on an older build.
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);

    private const uint WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    public MainWindow()
    {
        InitializeComponent();
        Title = "StudyBuddyZone";

        IntPtr hwnd = WindowNative.GetWindowHandle(this);
        bool protectedFromCapture = SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
        if (!protectedFromCapture)
        {
            // Older Windows build without WDA_EXCLUDEFROMCAPTURE support.
            // Fails safe: the app still runs, just without capture blocking.
            System.Diagnostics.Debug.WriteLine(
                "SetWindowDisplayAffinity failed (Win32 error " + Marshal.GetLastWin32Error() +
                "); screenshot/recording protection is unavailable on this Windows build.");
        }

        InitializeWebViewAsync();
    }

    private async void InitializeWebViewAsync()
    {
        await AppWebView.EnsureCoreWebView2Async();

        AppWebView.CoreWebView2.NavigationCompleted += (sender, args) =>
        {
            LoadingPanel.Visibility = Visibility.Collapsed;
            AppWebView.Visibility = Visibility.Visible;
        };

        AppWebView.Source = new Uri(StartUrl);
    }
}
