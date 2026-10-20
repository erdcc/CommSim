package comm.ui.editor;

import comm.scenario.ScriptCheck;
import comm.schema.MessageDef;
import comm.ui.Theme;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.folding.CurlyFoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Senaryo editoru: soz dizimi renklendirme, satir numaralari, katlama, baglama duyarli tamamlama (Ctrl+Bosluk
 * ya da yazarken), canli hata/uyari isaretleme, uzerine gelince belgeler ve arama.
 */
public final class ScriptEditor extends JPanel {
    private static final long serialVersionUID = 1L;
    static final String STYLE_DSL = "text/commsim-dsl";
    static final String STYLE_GROOVY = "text/commsim-groovy";

    static {
        AbstractTokenMakerFactory f = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        f.putMapping(STYLE_DSL, DslTokenMaker.class.getName(), ScriptEditor.class.getClassLoader());
        f.putMapping(STYLE_GROOVY, SimGroovyTokenMaker.class.getName(), ScriptEditor.class.getClassLoader());
        FoldParserManager.get().addFoldParserMapping(STYLE_DSL, new DslFoldParser());
        FoldParserManager.get().addFoldParserMapping(STYLE_GROOVY, new CurlyFoldParser());
    }

    private final RSyntaxTextArea area = new RSyntaxTextArea(20, 60);
    private final RTextScrollPane scroll = new RTextScrollPane(area);
    private final ErrorStrip strip = new ErrorStrip(area);
    private final ScriptAssist assist;
    private final AutoCompletion completion;
    private final Checker checker = new Checker();
    private final Supplier<List<Target>> targets;
    private final Supplier<Path> baseDir;
    private final JLabel position = new JLabel(" ");
    private final JLabel language = new JLabel(" ");
    private final JLabel problems = new JLabel(" ");
    private final JPanel findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JTextField findField = new JTextField(22);
    private final JCheckBox matchCase = new JCheckBox("Aa");
    private final JLabel findInfo = new JLabel(" ");
    private boolean groovy;
    private Runnable onRun;
    private int themeVersion = -1;

    public ScriptEditor(Supplier<List<Target>> targets, Supplier<Path> baseDir) {
        super(new BorderLayout());
        this.targets = targets;
        this.baseDir = baseDir;
        assist = new ScriptAssist(targets, baseDir);
        completion = new AutoCompletion(assist);
        completion.setAutoActivationEnabled(true);
        completion.setAutoActivationDelay(180);
        completion.setShowDescWindow(true);
        completion.setParameterAssistanceEnabled(true);
        completion.setAutoCompleteSingleChoices(false);
        completion.setChoicesWindowSize(380, 260);
        completion.setDescriptionWindowSize(360, 220);
        completion.install(area);

        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setMarkOccurrences(true);
        area.setMarkOccurrencesDelay(400);
        area.setPaintMatchedBracketPair(true);
        area.setAnimateBracketMatching(false);
        area.setInsertPairedCharacters(true);
        area.setCloseCurlyBraces(true);
        area.setAutoIndentEnabled(true);
        area.setTabSize(4);
        area.setTabsEmulated(true);
        area.setClearWhitespaceLinesEnabled(false);
        area.setHighlightCurrentLine(true);
        area.setFadeCurrentLineHighlight(false);
        area.setParserDelay(500);
        area.addParser(checker);
        area.setToolTipSupplier((ta, e) -> tooltip(e));
        scroll.setFoldIndicatorEnabled(true);
        scroll.setLineNumbersEnabled(true);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        strip.setShowMarkAll(false);

        JPanel center = new JPanel(new BorderLayout());
        center.add(scroll, BorderLayout.CENTER);
        center.add(strip, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);
        add(findBar(), BorderLayout.NORTH);
        add(statusBar(), BorderLayout.SOUTH);
        installKeys();
        area.addCaretListener(e -> updatePosition());
        setGroovy(false);
        applyTheme();
    }

    // =====================================================================
    //  genel API
    // =====================================================================

    public void setGroovy(boolean g) {
        groovy = g;
        assist.setGroovy(g);
        area.setSyntaxEditingStyle(g ? STYLE_GROOVY : STYLE_DSL);
        area.setTabSize(g ? 4 : 2);
        language.setText(g ? "Groovy" : "Senaryo dili");
        area.forceReparsing(checker);
    }

    public boolean isGroovy() { return groovy; }

    public String getText() { return area.getText(); }

    public void setText(String s) {
        area.setText(s);
        area.setCaretPosition(0);
        area.discardAllEdits();
    }

    /** ⌘⏎ ile calistirma. */
    public void setOnRun(Runnable r) { onRun = r; }

    public RSyntaxTextArea textArea() { return area; }

    /** Baglanti listesi degisti (ad ya da sema): uyarilar yeniden hesaplansin. */
    public void targetsChanged() { area.forceReparsing(checker); }

    @Override public void updateUI() {
        super.updateUI();
        if (area != null) applyTheme();
    }

    // =====================================================================
    //  gorunum
    // =====================================================================

    /** Acik/koyu temaya gore RSyntaxTextArea temasi; zemin FlatLaf yuzeyine uyarlanir. */
    private void applyTheme() {
        boolean dark = Theme.dark();
        int v = dark ? 1 : 0;
        Font font = Theme.mono(13);
        try (InputStream in = getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + (dark ? "dark.xml" : "idea.xml"))) {
            if (in != null) org.fife.ui.rsyntaxtextarea.Theme.load(in, font).apply(area);
        } catch (java.io.IOException e) {
            // tema yuklenemezse varsayilan renkler kalir
        }
        area.setFont(font);
        // komutlar / API yontemleri temada renksiz kaliyor: belirgin bir renk ver
        org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = area.getSyntaxScheme();
        org.fife.ui.rsyntaxtextarea.Style fn = scheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.FUNCTION);
        fn.foreground = dark ? new Color(0xC792EA) : new Color(0x7A3E9D);
        fn.font = font.deriveFont(Font.BOLD);
        area.setSyntaxScheme(scheme);
        Color bg = UIManager.getColor("TextArea.background");
        if (bg != null) {
            area.setBackground(bg);
            scroll.getGutter().setBackground(bg);
        }
        area.setCurrentLineHighlightColor(dark ? new Color(0x2C2F34) : new Color(0xF2F5FA));
        area.setMarkOccurrencesColor(dark ? new Color(0x3B4B63) : new Color(0xDCE8FF));
        area.setMatchedBracketBGColor(dark ? new Color(0x3B4B63) : new Color(0xDCE8FF));
        area.setMatchedBracketBorderColor(Theme.accent());
        scroll.getGutter().setLineNumberColor(Theme.muted());
        scroll.getGutter().setBorderColor(Theme.separator());
        scroll.getGutter().setLineNumberFont(Theme.mono(11));
        scroll.getGutter().setFoldIndicatorForeground(Theme.muted());
        scroll.getGutter().setFoldBackground(bg != null ? bg : Color.WHITE);
        strip.setBackground(bg);
        themeVersion = v;
        problems.setForeground(Theme.muted());
    }

    private JPanel statusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.separator()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        for (JLabel l : new JLabel[]{problems, position, language}) {
            l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
            left.add(l);
        }
        position.setForeground(Theme.muted());
        language.setForeground(Theme.muted());
        problems.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        problems.setToolTipText("İlk soruna git");
        problems.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { gotoFirstProblem(); }
        });
        JLabel hint = new JLabel(shortcut("⌃Space") + " öneri   " + shortcut("⌘/") + " yorum   " + shortcut("⌘F") + " ara   "
                + shortcut("⌘⏎") + " çalıştır");
        hint.setForeground(Theme.muted());
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        p.add(left, BorderLayout.WEST);
        p.add(hint, BorderLayout.EAST);
        return p;
    }

    private static String shortcut(String mac) {
        boolean isMac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        return isMac ? mac : mac.replace("⌘", "Ctrl+").replace("⌃", "Ctrl+").replace("⏎", "Enter");
    }

    private void updatePosition() {
        int caret = area.getCaretPosition();
        Element root = area.getDocument().getDefaultRootElement();
        int line = root.getElementIndex(caret);
        int col = caret - root.getElement(line).getStartOffset();
        position.setText("Satır " + (line + 1) + ", Sütun " + (col + 1));
    }

    // =====================================================================
    //  klavye ve arama
    // =====================================================================

    private void installKeys() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), "comm-run", () -> {
            if (onRun != null) onRun.run();
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, mask), "comm-find", this::showFind);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, mask), "comm-find-next", () -> find(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, mask | KeyEvent.SHIFT_DOWN_MASK), "comm-find-prev", () -> find(false));
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, mask), RSyntaxTextAreaEditorKit.rstaToggleCommentAction);
        // macOS'ta ⌃Bosluk giris kaynagini degistirebilir: ⌥Bosluk da oneri acar
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, KeyEvent.ALT_DOWN_MASK), "AutoComplete");
    }

    private void bind(KeyStroke k, String name, Runnable r) {
        area.getInputMap().put(k, name);
        area.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    private JPanel findBar() {
        findBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.separator()),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        findField.putClientProperty("JTextField.placeholderText", "Ara…");
        findField.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        JButton prev = new JButton("↑");
        JButton next = new JButton("↓");
        JButton close = new JButton("✕");
        for (JButton b : new JButton[]{prev, next, close}) b.putClientProperty("JButton.buttonType", "toolBarButton");
        matchCase.setToolTipText("Büyük/küçük harf duyarlı");
        findInfo.setForeground(Theme.muted());
        findBar.add(findField);
        findBar.add(prev);
        findBar.add(next);
        findBar.add(matchCase);
        findBar.add(findInfo);
        findBar.add(close);
        findBar.setVisible(false);
        findField.addActionListener(e -> find(true));
        next.addActionListener(e -> find(true));
        prev.addActionListener(e -> find(false));
        close.addActionListener(e -> hideFind());
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        findField.getActionMap().put("close", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override public void actionPerformed(ActionEvent e) { hideFind(); }
        });
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { markAll(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { markAll(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { markAll(); }
        });
        return findBar;
    }

    private void showFind() {
        String sel = area.getSelectedText();
        if (sel != null && !sel.contains("\n")) findField.setText(sel);
        findBar.setVisible(true);
        revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    private void hideFind() {
        findBar.setVisible(false);
        SearchEngine.markAll(area, new SearchContext());
        findInfo.setText(" ");
        revalidate();
        area.requestFocusInWindow();
    }

    private SearchContext searchContext(boolean forward) {
        SearchContext c = new SearchContext(findField.getText());
        c.setMatchCase(matchCase.isSelected());
        c.setSearchForward(forward);
        c.setWholeWord(false);
        c.setRegularExpression(false);
        c.setSearchWrap(true);
        return c;
    }

    private void markAll() {
        if (findField.getText().isEmpty()) {
            SearchEngine.markAll(area, new SearchContext());
            findInfo.setText(" ");
            return;
        }
        SearchResult r = SearchEngine.markAll(area, searchContext(true));
        findInfo.setText(r.getMarkedCount() + " sonuç");
    }

    private void find(boolean forward) {
        if (findField.getText().isEmpty()) {
            showFind();
            return;
        }
        SearchResult r = SearchEngine.find(area, searchContext(forward));
        findInfo.setText(r.wasFound() ? r.getMarkedCount() + " sonuç" : "bulunamadı");
    }

    // =====================================================================
    //  denetim (hata / uyari)
    // =====================================================================

    private volatile List<ScriptCheck.Problem> lastProblems = new ArrayList<>();

    private void gotoFirstProblem() {
        List<ScriptCheck.Problem> ps = lastProblems;
        if (ps.isEmpty()) return;
        ScriptCheck.Problem p = ps.get(0);
        Element root = area.getDocument().getDefaultRootElement();
        int line = Math.max(0, Math.min(root.getElementCount() - 1, p.line - 1));
        int start = root.getElement(line).getStartOffset();
        area.setCaretPosition(Math.min(area.getDocument().getLength(), start + Math.max(0, p.column - 1)));
        area.requestFocusInWindow();
    }

    private void showProblemCount(List<ScriptCheck.Problem> ps) {
        int errors = 0;
        int warnings = 0;
        for (ScriptCheck.Problem p : ps) {
            if (p.warning) warnings++;
            else errors++;
        }
        if (errors == 0 && warnings == 0) {
            problems.setText("✓ sorun yok");
            problems.setForeground(Theme.ok());
        } else {
            problems.setText((errors > 0 ? "✗ " + errors + " hata" : "") + (errors > 0 && warnings > 0 ? "  " : "")
                    + (warnings > 0 ? "⚠ " + warnings + " uyarı" : ""));
            problems.setForeground(errors > 0 ? Theme.err() : Theme.warn());
        }
    }

    /**
     * RSyntaxTextArea ayristiricisi. Derleme arka planda yapilir: metin degisince once bos sonuc doner,
     * denetim bitince yeniden ayristirma istenir ve o metne ait sonuc gosterilir.
     */
    private final class Checker extends AbstractParser {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "script-check");
            t.setDaemon(true);
            return t;
        });
        private String checkedText;
        private boolean checkedGroovy;
        private List<ScriptCheck.Problem> checked = new ArrayList<>();
        private String pendingText;

        @Override public ParseResult parse(RSyntaxDocument doc, String style) {
            DefaultParseResult result = new DefaultParseResult(this);
            String text;
            try {
                text = doc.getText(0, doc.getLength());
            } catch (BadLocationException e) {
                return result;
            }
            Element root = doc.getDefaultRootElement();
            result.setParsedLines(0, root.getElementCount() - 1);
            boolean g = groovy;
            if (text.equals(checkedText) && g == checkedGroovy) {
                for (ScriptCheck.Problem p : checked) result.addNotice(notice(doc, p));
                lastProblems = checked;
                showProblemCount(checked);
                return result;
            }
            if (!text.equals(pendingText)) {
                pendingText = text;
                List<Target> ts = targets.get(); // EDT'de anlik kopya
                Path base = baseDir.get();
                worker.execute(() -> {
                    List<ScriptCheck.Problem> ps = g ? ScriptCheck.groovy(text) : ScriptCheck.dsl(text, base);
                    ps.addAll(g ? groovyWarnings(text, ts) : dslWarnings(text, ts));
                    SwingUtilities.invokeLater(() -> {
                        checkedText = text;
                        checkedGroovy = g;
                        checked = ps;
                        pendingText = null;
                        if (text.equals(area.getText())) area.forceReparsing(this);
                    });
                });
            }
            return result;
        }

        private DefaultParserNotice notice(RSyntaxDocument doc, ScriptCheck.Problem p) {
            Element root = doc.getDefaultRootElement();
            int line = Math.max(0, Math.min(root.getElementCount() - 1, p.line - 1));
            Element el = root.getElement(line);
            int start = el.getStartOffset();
            int lineLen = Math.max(0, el.getEndOffset() - 1 - start);
            int offs;
            int len;
            if (p.column > 0) {
                offs = start + Math.min(lineLen, p.column - 1);
                len = p.endColumn > p.column ? p.endColumn - p.column : 1;
                len = Math.max(1, Math.min(len, start + lineLen - offs));
                if (offs >= start + lineLen) {
                    offs = Math.max(start, start + lineLen - 1);
                    len = 1;
                }
            } else {
                String t;
                try {
                    t = doc.getText(start, lineLen);
                } catch (BadLocationException e) {
                    t = "";
                }
                int lead = t.length() - t.replaceAll("^\\s+", "").length();
                offs = start + lead;
                len = Math.max(1, lineLen - lead);
            }
            DefaultParserNotice n = new DefaultParserNotice(this, p.message, line, offs, len);
            n.setLevel(p.warning ? ParserNotice.Level.WARNING : ParserNotice.Level.ERROR);
            n.setColor(p.warning ? Theme.warn() : Theme.err());
            n.setShowInEditor(true);
            return n;
        }
    }

    // ---- anlamsal uyarilar (bilinmeyen baglanti / mesaj / alan) ----

    private static final java.util.Set<String> EP_COMMANDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "start", "stop", "send", "set", "hex", "text", "waitfor", "expect", "modbus", "mbset", "snmp", "trap"));
    private static final java.util.Set<String> MSG_COMMANDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "send", "set", "expect", "waitfor"));

    static List<ScriptCheck.Problem> dslWarnings(String text, List<Target> ts) {
        List<ScriptCheck.Problem> out = new ArrayList<>();
        if (ts.isEmpty()) return out;
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            List<int[]> spans = new ArrayList<>();
            List<String> tok = tokens(lines[i], spans);
            if (tok.size() < 2) continue;
            String cmd = tok.get(0).toLowerCase(java.util.Locale.ROOT);
            if (!EP_COMMANDS.contains(cmd)) continue;
            String ep = tok.get(1);
            if (ep.contains("${")) continue;
            Target t = find(ts, ep);
            if (t == null) {
                out.add(new ScriptCheck.Problem(i + 1, spans.get(1)[0] + 1, spans.get(1)[1] + 1, "Bilinmeyen bağlantı: " + ep, true));
                continue;
            }
            if (!MSG_COMMANDS.contains(cmd) || tok.size() < 3 || t.schema == null || tok.get(2).contains("${")) continue;
            MessageDef m = Target.message(t.schema, tok.get(2));
            if (m == null) {
                if (!t.modbusMaster && !t.snmpManager) {
                    out.add(new ScriptCheck.Problem(i + 1, spans.get(2)[0] + 1, spans.get(2)[1] + 1,
                            "'" + t.name + "' şemasında " + tok.get(2) + " mesajı yok", true));
                }
                continue;
            }
            if (cmd.equals("waitfor")) continue;
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Target.Field f : Target.fields(t.schema, m)) known.add(f.path);
            for (int k = 3; k < tok.size(); k++) {
                String a = tok.get(k);
                int eq = a.indexOf('=');
                if (eq <= 0) continue;
                String key = a.substring(0, eq).replaceAll("\\[\\d+]", "[0]");
                if (!known.contains(key) && !known.contains(key.replaceAll("\\[0]$", ""))) {
                    out.add(new ScriptCheck.Problem(i + 1, spans.get(k)[0] + 1, spans.get(k)[0] + eq + 1,
                            m.name + " mesajında '" + a.substring(0, eq) + "' alanı yok", true));
                }
            }
        }
        return out;
    }

    private static final Pattern GROOVY_EP = Pattern.compile(
            "\\b(start|stop|send|set|sendHex|sendText|sendBytes|waitFor|last|expect|modbusRead|modbusWrite|modbusCoil|mbset|snmpGet|snmpWalk|snmpSet|trap|endpoint)"
                    + "\\s*\\(?\\s*([\"'])([^\"'$]*)\\2(?:\\s*,\\s*([\"'])([^\"'$]*)\\4)?");

    static List<ScriptCheck.Problem> groovyWarnings(String text, List<Target> ts) {
        List<ScriptCheck.Problem> out = new ArrayList<>();
        if (ts.isEmpty()) return out;
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int comment = l.indexOf("//");
            Matcher m = GROOVY_EP.matcher(l);
            while (m.find()) {
                if (comment >= 0 && m.start() > comment) break;
                Target t = find(ts, m.group(3));
                if (t == null) {
                    out.add(new ScriptCheck.Problem(i + 1, m.start(3) + 1, m.end(3) + 1, "Bilinmeyen bağlantı: " + m.group(3), true));
                    continue;
                }
                String call = m.group(1);
                if (m.group(5) != null && t.schema != null && !t.modbusMaster && !t.snmpManager
                        && (call.equals("send") || call.equals("set") || call.equals("waitFor") || call.equals("last") || call.equals("expect"))
                        && Target.message(t.schema, m.group(5)) == null) {
                    out.add(new ScriptCheck.Problem(i + 1, m.start(5) + 1, m.end(5) + 1,
                            "'" + t.name + "' şemasında " + m.group(5) + " mesajı yok", true));
                }
            }
        }
        return out;
    }

    private static Target find(List<Target> ts, String name) {
        for (Target t : ts) if (t.name.equals(name)) return t;
        return null;
    }

    /** Senaryo dili satirini sozcuklere ayirir; her sozcugun [bas, son) sutunlarini da verir. */
    static List<String> tokens(String line, List<int[]> spans) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') {
                if (!any) start = i;
                quoted = true;
                any = true;
            } else if (c == '#' && !any) {
                break;
            } else if (Character.isWhitespace(c)) {
                if (any) {
                    out.add(cur.toString());
                    spans.add(new int[]{start, i});
                    cur.setLength(0);
                    any = false;
                }
            } else {
                if (!any) start = i;
                cur.append(c);
                any = true;
            }
        }
        if (any) {
            out.add(cur.toString());
            spans.add(new int[]{start, line.length()});
        }
        return out;
    }

    // ---- uzerine gelince belge ----

    private String tooltip(MouseEvent e) {
        int offs = area.viewToModel(e.getPoint());
        if (offs < 0) return null;
        String text = area.getText();
        int a = offs;
        int b = offs;
        while (a > 0 && Character.isLetterOrDigit(text.charAt(a - 1)) || a > 0 && text.charAt(a - 1) == '_') a--;
        while (b < text.length() && (Character.isLetterOrDigit(text.charAt(b)) || text.charAt(b) == '_')) b++;
        if (a >= b) return null;
        String w = text.substring(a, b);
        if (groovy) {
            GroovyInfo.Method m = GroovyInfo.METHODS.get(w);
            if (m != null) return "<html><b>" + ScriptAssist.esc(m.signature) + "</b><br>" + ScriptAssist.esc(m.doc) + "</html>";
        } else {
            DslInfo.Cmd c = DslInfo.COMMANDS.get(w.toLowerCase(java.util.Locale.ROOT));
            if (c != null && isLineStart(text, a)) {
                return "<html><b>" + ScriptAssist.esc(c.syntax) + "</b><br>" + ScriptAssist.esc(c.doc) + "</html>";
            }
        }
        for (Target t : targets.get()) {
            if (t.schema == null) continue;
            MessageDef m = Target.message(t.schema, w);
            if (m != null && m.name.equals(w)) {
                StringBuilder sb = new StringBuilder("<html><b>").append(ScriptAssist.esc(m.name)).append("</b> (id ").append(m.id).append(")<br>");
                int n = 0;
                for (Target.Field f : Target.fields(t.schema, m)) {
                    if (n++ >= 14) {
                        sb.append("…");
                        break;
                    }
                    sb.append("<code>").append(ScriptAssist.esc(f.path)).append("</code> ").append(ScriptAssist.esc(f.type)).append("<br>");
                }
                return sb.append("</html>").toString();
            }
        }
        return null;
    }

    private static boolean isLineStart(String text, int offs) {
        int i = offs - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        return i < 0 || text.charAt(i) == '\n';
    }

    /** Tema degisimi sonrasi (updateUI cagrilmadiysa) elle yenileme. */
    public void refreshTheme() {
        if (themeVersion != (Theme.dark() ? 1 : 0)) applyTheme();
    }

    JComponent component() { return this; }
}
