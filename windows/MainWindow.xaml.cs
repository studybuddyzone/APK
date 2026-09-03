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

    // If the app was launched by tapping a studybuddypro-psi.vercel.app link
    // (App URI Handler activation — see App.xaml.cs), this is that exact URL
    // so we open straight to it (e.g. the quiz join / PRN page) instead of
    // the default homepage.
    private readonly string _initialUrl;

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

    public MainWindow() : this(null) { }

    public MainWindow(string? launchUrl)
    {
        InitializeComponent();
        Title = "StudyBuddyZone";
        _initialUrl = string.IsNullOrWhiteSpace(launchUrl) ? StartUrl : launchUrl!;

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

        AppWebView.Source = new Uri(_initialUrl);
    }

    // Called by App.xaml.cs when a studybuddypro-psi.vercel.app link is
    // tapped while this window is already open — navigate in place instead
    // of doing nothing / opening a second window.
    public void NavigateTo(string url)
    {
        try
        {
            if (AppWebView.CoreWebView2 != null) AppWebView.CoreWebView2.Navigate(url);
            else AppWebView.Source = new Uri(url);
        }
        catch (UriFormatException)
        {
            // Malformed URL handed to us by the OS — ignore rather than crash.
        }
    }
}
