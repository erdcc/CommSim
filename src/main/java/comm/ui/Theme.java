package comm.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Gorunum: FlatLaf acik/koyu tema, ortak renkler ve fontlar. Renkler her cagrida etkin temaya gore
 * verilir; boylece tema degisince ozel cizimler de uyum saglar.
 */
public final class Theme {
    public enum Mode {
        LIGHT("Açık"), DARK("Koyu"), SYSTEM("Sistem");

        public final String label;

        Mode(String label) { this.label = label; }
    }

    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static final String KEY = "theme";
    private static Mode mode = Mode.SYSTEM;
    private static boolean dark;
    private static String monoFamily = Font.MONOSPACED;
    private static int version;

    private Theme() { }

    /** Uygulama acilisinda, ilk pencereden once cagrilir. */
    public static void install() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Haberleşme Simülatörü");
        System.setProperty("apple.awt.application.appearance", "system");
        try {
            FlatJetBrainsMonoFont.install();
            monoFamily = FlatJetBrainsMonoFont.FAMILY;
        } catch (RuntimeException | LinkageError e) {
            monoFamily = Font.MONOSPACED;
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("@accentColor", "#2F6FEB");
        FlatLaf.setGlobalExtraDefaults(extra);
        try {
            mode = Mode.valueOf(PREFS.get(KEY, Mode.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            mode = Mode.SYSTEM;
        }
        setup();
    }

    public static Mode mode() { return mode; }

    public static boolean dark() { return dark; }

    /** Temayi degistirir ve tum acik pencereleri yeniler. */
    public static void apply(Mode m) {
        mode = m;
        PREFS.put(KEY, m.name());
        setup();
        version++;
        FlatLaf.updateUI();
    }

    private static void setup() {
        dark = mode == Mode.DARK || (mode == Mode.SYSTEM && systemDark());
        if (dark) FlatMacDarkLaf.setup();
        else FlatMacLightLaf.setup();
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("SplitPaneDivider.style", "plain");
        UIManager.put("TabbedPane.tabHeight", 30);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabSelectionHeight", 2);
        UIManager.put("Table.rowHeight", 22);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        UIManager.put("Table.alternateRowColor", dark ? new Color(0x2A2C30) : new Color(0xF6F8FB));
        UIManager.put("TableHeader.height", 26);
        UIManager.put("TableHeader.separatorColor", dark ? new Color(0x3A3D42) : new Color(0xE3E6EB));
        UIManager.put("List.selectionArc", 8);
        UIManager.put("Tree.selectionArc", 8);
        UIManager.put("TitledBorder.titleColor", muted());
    }

    /** macOS'ta "defaults read -g AppleInterfaceStyle" Dark ise koyu. Diger sistemlerde acik. */
    private static boolean systemDark() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return line != null && line.trim().equalsIgnoreCase("dark");
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * O an pencereye bagli olmayan (sekmeden cikarilmis) bir paneli etkin temaya getirir.
     * FlatLaf.updateUI yalnizca pencerelerdeki bilesenleri gunceller.
     */
    static void sync(javax.swing.JComponent c) {
        Object v = c.getClientProperty("comm.themeVersion");
        if (!Integer.valueOf(version).equals(v)) {
            javax.swing.SwingUtilities.updateComponentTreeUI(c);
            c.putClientProperty("comm.themeVersion", version);
        }
    }

    // ---- fontlar ----

    public static Font mono(float size) { return new Font(monoFamily, Font.PLAIN, Math.round(size)); }

    public static Font mono() { return mono(12); }

    // ---- anlamsal renkler ----

    public static Color ok() { return dark ? new Color(0x4CC38A) : new Color(0x1A7F4B); }

    public static Color err() { return dark ? new Color(0xFF6B6B) : new Color(0xC62828); }

    public static Color warn() { return dark ? new Color(0xF2B85B) : new Color(0xB26A00); }

    public static Color muted() { return dark ? new Color(0x8D939C) : new Color(0x7A808A); }

    public static Color accent() { return dark ? new Color(0x5B8DEF) : new Color(0x2F6FEB); }

    /** Alinan cerceve rengi. */
    public static Color rx() { return dark ? new Color(0x4CC38A) : new Color(0x1A7F4B); }

    /** Gonderilen cerceve rengi. */
    public static Color tx() { return dark ? new Color(0x6EA8FE) : new Color(0x1F5BD6); }

    public static Color info() { return muted(); }

    /** Grup/baslik satirlari icin yumusak zemin. */
    public static Color groupBg() { return dark ? new Color(0x30343A) : new Color(0xEEF2F7); }

    /** Kart/yuzey zemini (panellerden bir ton farkli). */
    public static Color surface() { return dark ? new Color(0x26282C) : new Color(0xFFFFFF); }

    public static Color separator() { return dark ? new Color(0x3A3D42) : new Color(0xE3E6EB); }

    /** Rengin yari saydam zemini (rozetler icin). */
    public static Color tint(Color c, int alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha); }

    /** Protokol ailesine gore rozet rengi. */
    public static Color protocol(String family) {
        switch (family) {
            case "TCP": return dark ? new Color(0x6EA8FE) : new Color(0x1F5BD6);
            case "UDP": return dark ? new Color(0xB794F6) : new Color(0x7B3FD1);
            case "MB": return dark ? new Color(0xF2B85B) : new Color(0xB26A00);
            case "WS": return dark ? new Color(0xF78FB3) : new Color(0xC2185B);
            default: return dark ? new Color(0x4FD1C5) : new Color(0x0F8A80);
        }
    }

    /** Baglanti durumu rengi. */
    public static Color state(comm.core.Endpoint.State s) {
        switch (s) {
            case RUNNING: return ok();
            case STARTING: return warn();
            case ERROR: return err();
            default: return dark ? new Color(0x5F646C) : new Color(0xB4B9C1);
        }
    }
}
