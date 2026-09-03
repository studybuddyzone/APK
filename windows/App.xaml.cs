using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace StudyBuddyZoneWindows;

/// <summary>
/// StudyBuddyZone Windows host application. Provides the application-specific
/// behavior to supplement the default Application class. Wraps the same
/// production web app the Android build points at (see
/// capacitor.config.json "server.url") in a native WebView2 window so it can
/// be packaged as an MSIX for the Microsoft Store.
///
/// Also handles "App URI Handler" activation: when Windows has verified this
/// app owns https://studybuddypro-psi.vercel.app (via the
/// .well-known/windows-app-web-link association file + the
/// windows.appUriHandler extension in Package.appxmanifest), tapping a
/// studybuddypro-psi.vercel.app link launches/focuses this app and hands us
/// the exact URL instead of opening a browser.
/// </summary>
public partial class App : Application
{
    private MainWindow? _window;
    private AppInstance? _mainInstance;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var activatedArgs = AppInstance.GetCurrent().GetActivatedEventArgs();

        // Single-instance: if StudyBuddyZone is already running and a second
        // link tap tries to start a new process, hand the URL over to the
        // already-running window instead of opening a second one.
        _mainInstance = AppInstance.FindOrRegisterForKey("StudyBuddyZoneMain");
        if (!_mainInstance.IsCurrent)
        {
            _mainInstance.RedirectActivationToAsync(activatedArgs).AsTask().GetAwaiter().GetResult();
            Process.GetCurrentProcess().Kill();
            return;
        }
        _mainInstance.Activated += OnActivated;

        _window = new MainWindow(GetLaunchUrl(activatedArgs));
        _window.Activate();
    }

    // Fires when a link is tapped while the app is already running.
    private void OnActivated(object? sender, AppActivationArguments args)
    {
        var url = GetLaunchUrl(args);
        if (url == null || _window == null) return;
        _window.DispatcherQueue.TryEnqueue(() => _window.NavigateTo(url));
    }

    private static string? GetLaunchUrl(AppActivationArguments args)
    {
        // The windows.appUriHandler manifest extension (Package.appxmanifest)
        // makes Windows launch this app for studybuddypro-psi.vercel.app
        // links, but the Windows App SDK surfaces that activation as a plain
        // Protocol activation, not a distinct "AppUriHandler" kind. There is
        // no ExtendedActivationKind.AppUriHandler and no
        // IAppUriHandlerActivatedEventArgs type — using them fails to
        // compile (CS0117 / CS0234). Protocol + IProtocolActivatedEventArgs
        // is the correct pairing for this activation.
        if (args.Kind == ExtendedActivationKind.Protocol
            && args.Data is Windows.ApplicationModel.Activation.IProtocolActivatedEventArgs protocolArgs)
        {
            return protocolArgs.Uri.ToString();
        }
        return null;
    }
}
