using AirPointer.Core;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;

namespace AirPointer;

public sealed class App : Application
{
    private ServerHost? _host;
    private MainWindow? _window;

    public override void Initialize()
    {
        // Always dark, with the phone app's blue as the accent (toggles, focus, selection).
        RequestedThemeVariant = ThemeVariant.Dark;
        Styles.Add(new FluentTheme
        {
            Palettes =
            {
                [ThemeVariant.Dark] = new ColorPaletteResources
                {
                    Accent = Color.Parse("#2563EB"),
                    RegionColor = Color.Parse("#0D1117"),
                },
            },
        });
        Styles.Add(Ui.Styles());
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            _host = new ServerHost(AppConfig.Load());

            // On Windows and macOS the window hides to the tray; Linux trays are unreliable, so closing quits there.
            bool useTray = !OperatingSystem.IsLinux();
            desktop.ShutdownMode = useTray ? ShutdownMode.OnExplicitShutdown : ShutdownMode.OnMainWindowClose;

            var icon = new WindowIcon(AssetLoader.Open(new Uri("avares://AirPointer/Assets/icon.png")));
            _window = new MainWindow(_host, useTray) { Icon = icon };

            if (useTray) SetupTray(desktop, icon);

            bool startHidden = useTray && desktop.Args?.Contains("--minimized") == true;
            if (!startHidden) desktop.MainWindow = _window;

            desktop.ShutdownRequested += (_, _) => _host.Dispose();
            _host.Start();
        }
        base.OnFrameworkInitializationCompleted();
    }

    private void SetupTray(IClassicDesktopStyleApplicationLifetime desktop, WindowIcon icon)
    {
        var show = new NativeMenuItem("Show AirPointer");
        show.Click += (_, _) => ShowWindow();
        var quit = new NativeMenuItem("Quit");
        quit.Click += (_, _) =>
        {
            _window!.AllowClose = true;
            desktop.Shutdown();
        };
        var menu = new NativeMenu();
        menu.Add(show);
        menu.Add(new NativeMenuItemSeparator());
        menu.Add(quit);

        var tray = new TrayIcon { Icon = icon, ToolTipText = "AirPointer - phone mouse receiver", Menu = menu };
        tray.Clicked += (_, _) => ShowWindow();
        TrayIcon.SetIcons(this, [tray]);
    }

    private void ShowWindow()
    {
        if (_window == null) return;
        _window.Show();
        _window.WindowState = WindowState.Normal;
        _window.Activate();
    }
}
