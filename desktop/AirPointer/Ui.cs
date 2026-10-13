using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Presenters;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;
using Path = Avalonia.Controls.Shapes.Path;

namespace AirPointer;

/// <summary>
/// The look shared with the phone app: dark palette, rounded rows with icon tiles, small uppercase section
/// labels, line icons (Lucide), and pill / primary / ghost buttons.
/// </summary>
internal static class Ui
{
    public static readonly IBrush Bg = Brush("#0D1117");
    public static readonly IBrush Row = Brush("#151A22");
    public static readonly IBrush RowHover = Brush("#1B222C");
    public static readonly IBrush Divider = Brush("#232A34");
    public static readonly IBrush Text = Brush("#E6EDF3");
    public static readonly IBrush Muted = Brush("#8B949E");
    public static readonly IBrush Accent = Brush("#2563EB");
    public static readonly IBrush AccentHover = Brush("#1D4ED8");
    public static readonly IBrush AccentLight = Brush("#7AA7FF");
    public static readonly IBrush AccentSoft = Brush("#262563EB");
    public static readonly IBrush AccentSoftHover = Brush("#402563EB");
    public static readonly IBrush Ok = Brush("#3FB950");
    public static readonly IBrush OkSoft = Brush("#263FB950");
    public static readonly IBrush Error = Brush("#F85149");
    public static readonly IBrush ErrorSoft = Brush("#26F85149");
    public static readonly IBrush Warn = Brush("#D29922");

    public static IBrush Brush(string hex) => new SolidColorBrush(Color.Parse(hex));

    /// <summary>A Lucide line icon, [size] px.</summary>
    public static Control Icon(string name, double size, IBrush brush) => new Viewbox
    {
        Width = size,
        Height = size,
        Child = new Path
        {
            Data = Geometry.Parse(LucideIcons.Paths[name]),
            Stroke = brush,
            StrokeThickness = 1.8,
            StrokeLineCap = PenLineCap.Round,
            StrokeJoin = PenLineJoin.Round,
            Width = 24,
            Height = 24,
        },
    };

    /// <summary>Rounded tile behind a row icon.</summary>
    public static Border Tile(string icon, IBrush background, IBrush foreground, double size = 44) => new()
    {
        Width = size,
        Height = size,
        CornerRadius = new CornerRadius(12),
        Background = background,
        Child = new Panel { Children = { Center(Icon(icon, size * 0.5, foreground)) } },
    };

    private static Control Center(Control c)
    {
        c.HorizontalAlignment = HorizontalAlignment.Center;
        c.VerticalAlignment = VerticalAlignment.Center;
        return c;
    }

    public static TextBlock Section(string text) => new()
    {
        Text = text.ToUpperInvariant(),
        FontSize = 12,
        FontWeight = FontWeight.SemiBold,
        LetterSpacing = 1.2,
        Foreground = Muted,
        Margin = new Thickness(4, 26, 0, 10),
    };

    public static TextBlock Caption(string text) => new()
    {
        Text = text,
        FontSize = 13,
        Foreground = Muted,
        TextWrapping = TextWrapping.Wrap,
        LineHeight = 19,
    };

    /// <summary>A list row / card: icon tile, title + subtitle, optional trailing control, optional body below.</summary>
    public static Border RowCard(Control tile, string title, Control? subtitle, Control? trailing = null, Control? body = null)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        grid.Children.Add(tile);
        var texts = new StackPanel { Margin = new Thickness(14, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center, Spacing = 3 };
        texts.Children.Add(new TextBlock { Text = title, FontSize = 15, FontWeight = FontWeight.SemiBold, Foreground = Text, TextTrimming = TextTrimming.CharacterEllipsis });
        if (subtitle != null) texts.Children.Add(subtitle);
        Grid.SetColumn(texts, 1);
        grid.Children.Add(texts);
        if (trailing != null)
        {
            trailing.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(trailing, 2);
            grid.Children.Add(trailing);
        }
        Control content = grid;
        if (body != null)
        {
            body.Margin = new Thickness(58, 12, 0, 0);
            content = new StackPanel { Children = { grid, body } };
        }
        return new Border
        {
            Background = Row,
            BorderBrush = Divider,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Padding = new Thickness(14, 12),
            Margin = new Thickness(0, 0, 0, 10),
            Child = content,
        };
    }

    /// <summary>Muted subtitle, optionally led by a colored status dot.</summary>
    public static Control Subtitle(string text, IBrush? dot = null)
    {
        var t = new TextBlock { Text = text, FontSize = 13, Foreground = Muted, TextWrapping = TextWrapping.Wrap };
        if (dot == null) return t;
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = dot, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 6, 7, 0) });
        Grid.SetColumn(t, 1);
        grid.Children.Add(t);
        return grid;
    }

    private static Control Face(string text, string? icon, IBrush brush, double iconSize = 18)
    {
        if (icon == null) return new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center };
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center };
        p.Children.Add(Center(Icon(icon, iconSize, brush)));
        if (text.Length > 0) p.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
        return p;
    }

    public static Button Primary(string text, string? icon = null)
    {
        var b = new Button { Content = Face(text, icon, Brushes.White), HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetName(b, text);
        b.Classes.Add("primary");
        return b;
    }

    public static Button Pill(string text, string? icon = null, bool danger = false)
    {
        var b = new Button { Content = Face(text, icon, danger ? Error : AccentLight, 15) };
        AutomationProperties.SetName(b, text);
        b.Classes.Add(danger ? "danger" : "pill");
        return b;
    }

    public static Button Ghost(string icon, string tip, IBrush? brush = null)
    {
        var b = new Button { Content = Icon(icon, 18, brush ?? Text) };
        ToolTip.SetTip(b, tip);
        AutomationProperties.SetName(b, tip);
        b.Classes.Add("ghost");
        return b;
    }

    /// <summary>Button looks, including hover and pressed (Fluent's template would otherwise turn them grey).</summary>
    public static Styles Styles()
    {
        Style Button(string cls, IBrush bg, IBrush fg, IBrush hover, CornerRadius radius, Thickness padding, double height)
        {
            return new Style(x => x.OfType<Button>().Class(cls))
            {
                Setters =
                {
                    new Setter(Avalonia.Controls.Button.BackgroundProperty, bg),
                    new Setter(Avalonia.Controls.Button.ForegroundProperty, fg),
                    new Setter(Avalonia.Controls.Button.CornerRadiusProperty, radius),
                    new Setter(Avalonia.Controls.Button.PaddingProperty, padding),
                    new Setter(Avalonia.Controls.Button.MinHeightProperty, height),
                    new Setter(Avalonia.Controls.Button.BorderThicknessProperty, new Thickness(0)),
                    new Setter(Avalonia.Controls.Button.HorizontalContentAlignmentProperty, HorizontalAlignment.Center),
                    new Setter(Avalonia.Controls.Button.VerticalContentAlignmentProperty, VerticalAlignment.Center),
                    new Setter(Avalonia.Controls.Button.FontWeightProperty, FontWeight.SemiBold),
                },
            };
        }

        Style State(string cls, string state, IBrush bg, IBrush fg) =>
            new(x => x.OfType<Button>().Class(cls).Class(state).Template().OfType<ContentPresenter>().Name("PART_ContentPresenter"))
            {
                Setters =
                {
                    new Setter(ContentPresenter.BackgroundProperty, bg),
                    new Setter(ContentPresenter.ForegroundProperty, fg),
                },
            };

        var transparent = Brushes.Transparent;
        var ghostHover = Brush("#26FFFFFF");
        return new Styles
        {
            Button("primary", Accent, Brushes.White, AccentHover, new CornerRadius(14), new Thickness(20, 12), 50),
            State("primary", ":pointerover", AccentHover, Brushes.White),
            State("primary", ":pressed", AccentHover, Brushes.White),
            Button("pill", AccentSoft, AccentLight, AccentSoftHover, new CornerRadius(999), new Thickness(16, 7), 34),
            State("pill", ":pointerover", AccentSoftHover, AccentLight),
            State("pill", ":pressed", AccentSoftHover, AccentLight),
            Button("danger", ErrorSoft, Error, ErrorSoft, new CornerRadius(999), new Thickness(16, 7), 34),
            State("danger", ":pointerover", Brush("#40F85149"), Error),
            State("danger", ":pressed", Brush("#40F85149"), Error),
            Button("link", transparent, AccentLight, transparent, new CornerRadius(0), new Thickness(0), 0),
            State("link", ":pointerover", transparent, Text),
            State("link", ":pressed", transparent, Text),
            Button("ghost", transparent, Text, ghostHover, new CornerRadius(999), new Thickness(10), 40),
            State("ghost", ":pointerover", ghostHover, Text),
            State("ghost", ":pressed", ghostHover, Text),
        };
    }

    /// <summary>The phone app's first-run illustration: a computer showing a pairing code, a phone scanning it.</summary>
    public static Control PairIllustration() => new Viewbox
    {
        Width = 160,
        Height = 120,
        Child = new Canvas
        {
            Width = 160,
            Height = 120,
            Children =
            {
                Shape("M18,10 L112,10 A8,8 0 0,1 120,18 L120,74 A8,8 0 0,1 112,82 L18,82 A8,8 0 0,1 10,74 L10,18 A8,8 0 0,1 18,10 Z", "#93C5FD", 3, "#331E3A8A"),
                Shape("M65,82 L65,96 M46,98 L84,98", "#93C5FD", 3),
                Shape("M46,24 h12 v12 h-12 Z M72,24 h12 v12 h-12 Z M46,50 h12 v12 h-12 Z", "#E6EDF3", 2.5),
                Shape("M50,28 h4 v4 h-4 Z M76,28 h4 v4 h-4 Z M50,54 h4 v4 h-4 Z M64,26 h4 v4 h-4 Z M64,36 h4 v4 h-4 Z M72,44 h4 v4 h-4 Z M80,52 h4 v4 h-4 Z M64,58 h4 v4 h-4 Z M72,58 h4 v4 h-4 Z M84,44 h4 v4 h-4 Z", null, 0, "#E6EDF3"),
                Shape("M108,40 L140,40 A7,7 0 0,1 147,47 L147,105 A7,7 0 0,1 140,112 L108,112 A7,7 0 0,1 101,105 L101,47 A7,7 0 0,1 108,40 Z", "#FFFFFF", 3, "#0D1117"),
                Shape("M113,62 L113,56 L119,56 M135,56 L141,56 L141,62 M141,90 L141,96 L135,96 M119,96 L113,96 L113,90", "#7AA7FF", 2.5),
                Shape("M115,76 L139,76", "#3FB950", 2.5),
                Shape("M121,104 h6 v2 h-6 Z", null, 0, "#FFFFFF"),
            },
        },
    };

    private static Path Shape(string data, string? stroke, double width, string? fill = null) => new()
    {
        Data = Geometry.Parse(data),
        Stroke = stroke == null ? null : Brush(stroke),
        StrokeThickness = width,
        StrokeLineCap = PenLineCap.Round,
        StrokeJoin = PenLineJoin.Round,
        Fill = fill == null ? null : Brush(fill),
    };
}
