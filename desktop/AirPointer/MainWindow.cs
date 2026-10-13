using AirPointer.Core;
using AirPointer.Media;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using QRCoder;

namespace AirPointer;

/// <summary>
/// The PC app's one screen, in the phone app's look: pairing panel, paired phones, connections, sharing and
/// general settings. Kept short: the activity log only goes to airpointer.log.
/// </summary>
public sealed class MainWindow : Window
{
    private static readonly FontFamily Mono = new("Cascadia Mono, Consolas, Menlo, DejaVu Sans Mono, monospace");

    private readonly ServerHost _host;
    private readonly bool _hideToTray;

    // Pairing panel: the steps, or the QR code while a pairing window is open.
    private readonly StackPanel _heroIntro = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    private Control _hero = null!;
    private readonly StackPanel _heroQr = new() { IsVisible = false, HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Image _qr = new() { Width = 220, Height = 220 };
    private readonly TextBlock _qrCountdown = new() { FontSize = 13, Foreground = Ui.Brush("#CCE6EDF3"), HorizontalAlignment = HorizontalAlignment.Center };
    private readonly DispatcherTimer _pairTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private ServerHost.PairingWindow? _pairing;
    private readonly Dictionary<string, Window> _textWindows = [];

    private readonly StackPanel _phones = new();
    private readonly Button _pairAnother = Ui.Pill("Pair", "plus");
    private readonly StackPanel _connections = new();
    private readonly StackPanel _screen = new();
    private readonly ToggleSwitch _allowInternet = Toggle();
    private readonly ToggleSwitch _allowScreenView = Toggle();
    private readonly ToggleSwitch _allowSound = Toggle();

    // Rows rebuilt by Refresh() keep their toggles; these hold the latest subtitles.
    private readonly ContentControl _wifiSubtitle = new();
    private readonly StackPanel _addresses = new() { Spacing = 4 };
    private string _addressText = "";
    private readonly ContentControl _internetSubtitle = new();

    public bool AllowClose { get; set; }

    public MainWindow(ServerHost host, bool hideToTray)
    {
        _host = host;
        _hideToTray = hideToTray;
        Title = "AirPointer";
        Width = 460;
        Height = 820;
        MinWidth = 380;
        MinHeight = 520;
        Background = Ui.Bg;
        RequestedThemeVariant = Avalonia.Styling.ThemeVariant.Dark;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var content = new StackPanel { Margin = new Thickness(20, 18, 20, 28), MaxWidth = 560 };
        content.Children.Add(TopBar());
        _hero = Hero();
        content.Children.Add(_hero);

        _pairAnother.Click += (_, _) => ShowPairing();
        content.Children.Add(SectionWithAction("Your phones", _pairAnother));
        content.Children.Add(_phones);

        content.Children.Add(Ui.Section("Connections"));
        content.Children.Add(Connections());

        content.Children.Add(Ui.Section("Sharing"));
        content.Children.Add(Sharing());

        if (OperatingSystem.IsWindows())
        {
            content.Children.Add(Ui.Section("General"));
            var autostart = Toggle(Autostart.IsEnabled());
            autostart.IsCheckedChanged += (_, _) => Autostart.Set(autostart.IsChecked == true);
            content.Children.Add(Ui.RowCard(Ui.Tile("power", Ui.AccentSoft, Ui.AccentLight), "Start with Windows",
                Ui.Subtitle("Runs quietly in the tray"), autostart));
        }

        var version = typeof(MainWindow).Assembly.GetName().Version;
        var licenses = new Button { Content = "Open-source licenses", Padding = new Thickness(0), FontSize = 12, Foreground = Ui.AccentLight };
        licenses.Classes.Add("link");
        licenses.Click += (_, _) => ShowLicenses();
        content.Children.Add(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 10,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 18, 0, 0),
            Children =
            {
                new TextBlock { Text = $"AirPointer {version?.ToString(3)}  ·  MIT License", FontSize = 12, Foreground = Ui.Muted, VerticalAlignment = VerticalAlignment.Center },
                licenses,
            },
        });

        Content = new ScrollViewer { Content = content };

        _pairTimer.Tick += (_, _) => UpdatePairingCountdown();
        _host.Paired += _ => Dispatcher.UIThread.Post(HidePairing);
        _host.StateChanged += () => Dispatcher.UIThread.Post(Refresh);
        Refresh();
    }

    private static ToggleSwitch Toggle(bool on = false) => new()
    {
        IsChecked = on,
        OnContent = null,
        OffContent = null,
        MinWidth = 0,
        Padding = new Thickness(0),
    };

    private Control TopBar()
    {
        var logo = new Border
        {
            Width = 40,
            Height = 40,
            CornerRadius = new CornerRadius(10),
            ClipToBounds = true,
            Child = new Image { Source = new Bitmap(AssetLoader.Open(new Uri("avares://AirPointer/Assets/icon.png"))) },
        };
        var texts = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, Spacing = 1 };
        texts.Children.Add(new TextBlock { Text = "AirPointer", FontSize = 22, FontWeight = FontWeight.SemiBold, Foreground = Ui.Text });
        texts.Children.Add(new TextBlock { Text = $"Receiver on {Protocol.ComputerName}", FontSize = 13, Foreground = Ui.Muted });
        var bar = new StackPanel { Orientation = Orientation.Horizontal };
        bar.Children.Add(logo);
        bar.Children.Add(texts);
        return bar;
    }

    /// <summary>The phone's first-run panel, turned around: how to pair, then the code itself.</summary>
    private Control Hero()
    {
        var illustration = Ui.PairIllustration();
        illustration.HorizontalAlignment = HorizontalAlignment.Center;
        _heroIntro.Children.Add(illustration);
        _heroIntro.Children.Add(new TextBlock
        {
            Text = "Pair a phone", FontSize = 21, FontWeight = FontWeight.SemiBold, Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 14, 0, 0),
        });
        _heroIntro.Children.Add(new TextBlock
        {
            Text = "Pair once. Then connect with a tap, from anywhere.",
            FontSize = 14, Foreground = Ui.Brush("#CCE6EDF3"), TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 6, 0, 0), LineHeight = 20,
        });
        var steps = new StackPanel { Margin = new Thickness(0, 14, 0, 0) };
        string[] lines = ["Open AirPointer on your phone", "Tap \"Scan pairing code\"", "Click \"+ Pair\" and scan the code"];
        for (int i = 0; i < lines.Length; i++) steps.Children.Add(Step(i + 1, lines[i]));
        _heroIntro.Children.Add(steps);
        var show = Ui.Primary("Pair", "plus");
        show.Margin = new Thickness(0, 18, 0, 0);
        show.Click += (_, _) => ShowPairing();
        _heroIntro.Children.Add(show);

        RenderOptions.SetBitmapInterpolationMode(_qr, BitmapInterpolationMode.None);
        _heroQr.Children.Add(new TextBlock
        {
            Text = "Scan with your phone", FontSize = 21, FontWeight = FontWeight.SemiBold, Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        _heroQr.Children.Add(new TextBlock
        {
            Text = "Phone and computer must be on the same Wi-Fi.",
            FontSize = 14, Foreground = Ui.Brush("#CCE6EDF3"), TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 6, 0, 16), LineHeight = 20,
        });
        _heroQr.Children.Add(new Border
        {
            Background = Brushes.White,
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(12),
            HorizontalAlignment = HorizontalAlignment.Center,
            Child = _qr,
        });
        _qrCountdown.Margin = new Thickness(0, 14, 0, 0);
        _heroQr.Children.Add(_qrCountdown);
        var cancel = Ui.Pill("Cancel", "x");
        cancel.HorizontalAlignment = HorizontalAlignment.Center;
        cancel.Margin = new Thickness(0, 14, 0, 0);
        cancel.Click += (_, _) => HidePairing();
        _heroQr.Children.Add(cancel);

        return new Border
        {
            Background = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                EndPoint = new RelativePoint(1, 0, RelativeUnit.Relative),
                GradientStops = { new GradientStop(Color.Parse("#1D3B8A"), 0), new GradientStop(Color.Parse("#1E1B4B"), 1) },
            },
            BorderBrush = Ui.Brush("#333B82F6"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(24),
            Padding = new Thickness(22),
            Margin = new Thickness(0, 20, 0, 0),
            Child = new Panel { Children = { _heroIntro, _heroQr } },
        };
    }

    private static Control Step(int n, string text)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), Margin = new Thickness(0, 5) };
        grid.Children.Add(new Border
        {
            Width = 26,
            Height = 26,
            CornerRadius = new CornerRadius(13),
            Background = Ui.Brush("#26FFFFFF"),
            Child = new TextBlock
            {
                Text = n.ToString(), FontSize = 13, Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            },
        });
        var label = new TextBlock
        {
            Text = text, FontSize = 15, Foreground = Ui.Brush("#E6FFFFFF"), TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0),
        };
        Grid.SetColumn(label, 1);
        grid.Children.Add(label);
        return grid;
    }

    private static Control SectionWithAction(string title, Control action)
    {
        // The button is taller than a section label: keep the label's spacing around the whole row.
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 20, 0, 12) };
        var label = Ui.Section(title);
        label.Margin = new Thickness(4, 0, 0, 0);
        label.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(label);
        action.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(action, 1);
        grid.Children.Add(action);
        return grid;
    }

    private Control Connections()
    {
        _allowInternet.IsCheckedChanged += (_, _) =>
        {
            if (_allowInternet.IsChecked != _host.Config.AllowInternet) _host.SetAllowInternet(_allowInternet.IsChecked == true);
        };
        var copy = Ui.Ghost("copy", "Copy addresses");
        copy.Click += async (_, _) =>
        {
            if (Clipboard is { } clipboard) await clipboard.SetTextAsync(_addressText);
        };
        _connections.Children.Add(Ui.RowCard(Ui.Tile("wifi", Ui.AccentSoft, Ui.AccentLight), "Wi-Fi", _wifiSubtitle, copy, _addresses));
        _connections.Children.Add(Ui.RowCard(Ui.Tile("globe", Ui.AccentSoft, Ui.AccentLight), "Internet", _internetSubtitle, _allowInternet));
        return _connections;
    }

    private Control Sharing()
    {
        _allowScreenView.IsCheckedChanged += (_, _) =>
        {
            if (_allowScreenView.IsChecked != _host.Config.AllowScreenView) _host.SetAllowScreenView(_allowScreenView.IsChecked == true);
        };
        _allowSound.IsCheckedChanged += (_, _) =>
        {
            if (_allowSound.IsChecked != _host.Config.AllowSound) _host.SetAllowSound(_allowSound.IsChecked == true);
        };
        _screen.Children.Add(Ui.RowCard(Ui.Tile("monitor", Ui.AccentSoft, Ui.AccentLight), "Share screen",
            Ui.Subtitle("Phones can see this screen"), _allowScreenView));
        _screen.Children.Add(Ui.RowCard(Ui.Tile("volume-2", Ui.AccentSoft, Ui.AccentLight), "Share sound",
            Ui.Subtitle("Phones can play this computer's sound"), _allowSound));
        // Windows encodes the screen as H.264 by itself; elsewhere the user can turn on Cisco's OpenH264.
        bool forceOpenH264 = Environment.GetEnvironmentVariable("AIRPOINTER_FORCE_OPENH264") == "1";
        if ((!OperatingSystem.IsWindows() || forceOpenH264) && OpenH264.Supported) _screen.Children.Add(OpenH264Row());
        return _screen;
    }

    /// <summary>
    /// The user's on/off switch for Cisco's OpenH264 binary, with the attribution and license Cisco requires.
    /// Turning it on downloads the binary from Cisco (once).
    /// </summary>
    private Control OpenH264Row()
    {
        var toggle = Toggle(_host.Config.UseOpenH264);
        var subtitle = new ContentControl();
        void ShowStatus(string? text = null, IBrush? dot = null)
        {
            if (text != null)
            {
                subtitle.Content = Ui.Subtitle(text, dot);
                return;
            }
            subtitle.Content = !_host.Config.UseOpenH264
                ? Ui.Subtitle("Sharper screen, less data")
                : OpenH264.IsDownloaded
                    ? Ui.Subtitle("On", Ui.Ok)
                    : Ui.Subtitle("Not downloaded yet", Ui.Warn);
        }
        ShowStatus();
        toggle.IsCheckedChanged += async (_, _) =>
        {
            bool on = toggle.IsChecked == true;
            if (on == _host.Config.UseOpenH264) return;
            _host.Config.UseOpenH264 = on;
            _host.Config.Save();
            if (on && !OpenH264.IsDownloaded)
            {
                toggle.IsEnabled = false;
                ShowStatus("Downloading…", Ui.AccentLight);
                try
                {
                    await OpenH264.DownloadAsync();
                }
                catch (Exception e)
                {
                    _host.Config.UseOpenH264 = false;
                    _host.Config.Save();
                    toggle.IsChecked = false;
                    ShowStatus("Download failed. Check the internet connection.", Ui.Error);
                    ToolTip.SetTip(subtitle, e.Message);
                    toggle.IsEnabled = true;
                    return;
                }
                toggle.IsEnabled = true;
            }
            ShowStatus();
        };
        // Cisco's license asks for this credit where the user turns the codec on, and for its license text.
        var license = new Button { Content = "License", Padding = new Thickness(0), FontSize = 12, Foreground = Ui.AccentLight };
        license.Classes.Add("link");
        license.Click += (_, _) => ShowOpenH264License();
        var credit = new WrapPanel
        {
            Children =
            {
                new TextBlock { Text = OpenH264.Attribution + "  ", FontSize = 12, Foreground = Ui.Muted, TextWrapping = TextWrapping.Wrap },
                license,
            },
        };
        return Ui.RowCard(Ui.Tile("monitor-play", Ui.AccentSoft, Ui.AccentLight), "Smooth video", subtitle, toggle, credit);
    }

    private void ShowOpenH264License() => ShowText("OpenH264 license", "OpenH264-BINARY_LICENSE.txt");

    /// <summary>AirPointer's license and the notices of everything it includes.</summary>
    private void ShowLicenses() => ShowText("Licenses", "LICENSE.txt", "THIRD-PARTY-NOTICES.txt");

    /// <summary>A window showing bundled text files; one per title, brought to the front if already open.</summary>
    private void ShowText(string title, params string[] assets)
    {
        if (_textWindows.TryGetValue(title, out var open))
        {
            open.Activate();
            return;
        }
        var text = string.Join("\n\n", assets.Select(a =>
        {
            using var stream = AssetLoader.Open(new Uri("avares://AirPointer/Assets/" + a));
            return new StreamReader(stream).ReadToEnd().Replace("\r\n", "\n");
        }));
        // Long texts (the notices are ~1 MB) as a virtualized list of paragraphs, so the window opens instantly.
        var paragraphs = text.Split("\n\n");
        var list = new ListBox
        {
            ItemsSource = paragraphs,
            Background = Ui.Bg,
            Padding = new Thickness(12),
            ItemTemplate = new Avalonia.Controls.Templates.FuncDataTemplate<string>((p, _) => new SelectableTextBlock
            {
                Text = p, TextWrapping = TextWrapping.Wrap, Foreground = Ui.Text, FontSize = 13, LineHeight = 19,
                Margin = new Thickness(0, 0, 0, 10),
            }),
        };
        var w = new Window
        {
            Title = title,
            Width = 640,
            Height = 600,
            Background = Ui.Bg,
            RequestedThemeVariant = Avalonia.Styling.ThemeVariant.Dark,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Content = list,
        };
        w.Closed += (_, _) => _textWindows.Remove(title);
        _textWindows[title] = w;
        w.Show(this);
    }

    private void Refresh()
    {
        var cfg = _host.Config;
        RefreshPhones();

        var addrs = ServerHost.LocalAddresses();
        _addresses.Children.Clear();
        if (addrs.Count == 0) _addresses.Children.Add(Ui.Caption("No network connection"));
        foreach (var a in addrs)
        {
            var line = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
            line.Children.Add(new SelectableTextBlock { Text = $"{a.Address}:{cfg.ListenPort}", FontFamily = Mono, FontSize = 13, Foreground = Ui.Text });
            var adapter = new TextBlock
            {
                Text = a.Adapter, FontSize = 12, Foreground = Ui.Muted, TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(adapter, 1);
            line.Children.Add(adapter);
            _addresses.Children.Add(line);
        }
        _addressText = string.Join(Environment.NewLine, addrs.Select(a => $"{a.Address}:{cfg.ListenPort}"));
        _wifiSubtitle.Content = Ui.Subtitle(Short(_host.WifiStatus), StatusDot(_host.WifiStatus));
        _allowInternet.IsChecked = cfg.AllowInternet;
        _internetSubtitle.Content = Ui.Subtitle(Short(_host.InternetStatus), cfg.AllowInternet ? StatusDot(_host.InternetStatus) : Ui.Muted);
        _allowScreenView.IsChecked = cfg.AllowScreenView;
        _allowSound.IsChecked = cfg.AllowSound;
    }

    /// <summary>A few words for a connection status (the full text goes to the log).</summary>
    private static string Short(string status) => status switch
    {
        _ when status.StartsWith("Listening") => "Ready",
        _ when status.StartsWith("Could not listen") => "Not working: another app uses the port",
        _ when status.StartsWith("On:") => "Connect from anywhere",
        _ when status.StartsWith("Connecting") => "Connecting…",
        _ when status.StartsWith("Waiting") => "Waiting for internet",
        _ when status.StartsWith("Unavailable") || status.StartsWith("Not available") => "Not available",
        _ => status,
    };

    /// <summary>Green when working, red on failure, grey when off, amber while starting or waiting.</summary>
    private static IBrush StatusDot(string status)
    {
        if (status.StartsWith("Listening") || status.StartsWith("On:")) return Ui.Ok;
        if (status.StartsWith("Could not") || status.StartsWith("Unavailable") || status.StartsWith("Not available")) return Ui.Error;
        if (status == "Off") return Ui.Muted;
        return Ui.Warn;
    }

    private void RefreshPhones()
    {
        _phones.Children.Clear();
        var devices = _host.PairedDevices;
        var sessions = _host.Sessions;
        var media = _host.MediaSessions;
        _pairAnother.IsVisible = devices.Count > 0 && _pairing == null;
        _hero.IsVisible = devices.Count == 0 || _pairing != null;
        if (devices.Count == 0)
        {
            _phones.Children.Add(Ui.RowCard(Ui.Tile("smartphone", Ui.Divider, Ui.Muted), "No phones yet",
                Ui.Subtitle("Pair a phone to get started")));
            return;
        }
        foreach (var d in devices)
        {
            var session = sessions.FirstOrDefault(s => s.DeviceId == d.Id);
            Control subtitle;
            Border tile;
            if (session != null)
            {
                var extras = media.Where(m => m.DeviceId == d.Id).Select(m => m.What).Distinct().ToList();
                var text = $"Connected over {session.Transport}";
                if (extras.Count > 0) text += " · " + string.Join(", ", extras);
                subtitle = Ui.Subtitle(text, Ui.Ok);
                tile = Ui.Tile("smartphone", Ui.OkSoft, Ui.Ok);
            }
            else
            {
                subtitle = Ui.Subtitle(d.LastSeen is { } t ? $"Last used {t:g}" : $"Paired {d.PairedAt:g}");
                tile = Ui.Tile("smartphone", Ui.AccentSoft, Ui.AccentLight);
            }
            var remove = Ui.Ghost("trash-2", "Remove phone", Ui.Muted);
            remove.Click += (_, _) => _host.RemoveDevice(d.Id);
            _phones.Children.Add(Ui.RowCard(tile, d.Name, subtitle, remove));
        }
    }

    private void ShowPairing()
    {
        _pairing = _host.StartPairing();
        using var data = new QRCodeGenerator().CreateQrCode(_pairing.Link, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8, [0x0D, 0x11, 0x17], [0xFF, 0xFF, 0xFF], drawQuietZones: false);
        _qr.Source = new Bitmap(new MemoryStream(png));
        _heroIntro.IsVisible = false;
        _heroQr.IsVisible = true;
        _pairAnother.IsVisible = false;
        _hero.IsVisible = true;
        UpdatePairingCountdown();
        _pairTimer.Start();
        if (Content is ScrollViewer sv) sv.Offset = new Vector(0, 0);
    }

    private void HidePairing()
    {
        _host.CancelPairing();
        _pairing = null;
        _pairTimer.Stop();
        _heroQr.IsVisible = false;
        _heroIntro.IsVisible = true;
        _pairAnother.IsVisible = _host.PairedDevices.Count > 0;
        _hero.IsVisible = !_pairAnother.IsVisible;
        _qr.Source = null;
    }

    private void UpdatePairingCountdown()
    {
        if (_pairing == null) return;
        var left = _pairing.Expires - DateTime.UtcNow;
        if (left <= TimeSpan.Zero)
        {
            HidePairing();
            return;
        }
        _qrCountdown.Text = $"Expires in {(int)left.TotalMinutes}:{left.Seconds:00}";
    }

    protected override void OnClosing(WindowClosingEventArgs e)
    {
        if (_hideToTray && !AllowClose)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnClosing(e);
    }
}

internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool IsEnabled()
    {
        if (!OperatingSystem.IsWindows()) return false;
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue("AirPointer") != null;
    }

    public static void Set(bool enabled)
    {
        if (!OperatingSystem.IsWindows()) return;
        using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue("AirPointer", $"\"{Environment.ProcessPath}\" --minimized");
        else key.DeleteValue("AirPointer", throwOnMissingValue: false);
    }
}
