package comm.ui;

import comm.core.Direction;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.schema.Decoded;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Secili trafik kaydinin incelemesi: ust bilgi satiri, alan tablosu ve renkli hex dokumu.
 * Alan tablosunda secilen alanin byte'lari hex dokumunde vurgulanir; hex'te bir byte'a tiklamak alani secer.
 */
final class FrameInspector extends JPanel {
    private static final long serialVersionUID = 1L;

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel title = new JLabel(" ");
    private final Chip dirChip = new Chip(" ");
    private final JLabel meta = new JLabel(" ");
    private final JLabel issues = new JLabel();
    private final JButton copy = UiKit.tool(null, Icons.COPY, "Kaydın tam dökümünü panoya kopyalar");
    private final FieldModel fieldModel = new FieldModel();
    private final JTable fields = new JTable(fieldModel);
    private final HexView hex = new HexView();
    private final JTextArea text = new JTextArea();
    private final JSplitPane split;
    private LogEntry entry;
    private boolean syncing;

    FrameInspector() {
        super(new BorderLayout());
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.separator()),
                BorderFactory.createEmptyBorder(6, 10, 6, 6)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        title.putClientProperty("FlatLaf.styleClass", "h4");
        meta.setForeground(Theme.muted());
        issues.setForeground(Theme.warn());
        left.add(dirChip);
        left.add(title);
        left.add(meta);
        left.add(issues);
        head.add(left, BorderLayout.CENTER);
        head.add(copy, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);
        copy.addActionListener(e -> {
            if (entry != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(TrafficPanel.describe(entry)), null);
            }
        });

        Font mono = Theme.mono(12);
        fields.setFont(mono);
        fields.setRowHeight(Math.max(fields.getRowHeight(), 20));
        fields.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fields.setFillsViewportHeight(true);
        fields.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int[] widths = {52, 40, 190, 200};
        for (int i = 0; i < widths.length; i++) fields.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        fields.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                setHorizontalAlignment(c <= 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (!sel) setForeground(c <= 1 ? Theme.muted() : t.getForeground());
                return this;
            }
        });
        fields.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing) return;
            int r = fields.getSelectedRow();
            hex.select(r < 0 ? -1 : r);
        });
        hex.onPick = i -> {
            syncing = true;
            try {
                if (i < 0) fields.clearSelection();
                else {
                    fields.setRowSelectionInterval(i, i);
                    fields.scrollRectToVisible(fields.getCellRect(i, 0, true));
                }
            } finally {
                syncing = false;
            }
        };

        JScrollPane hexScroll = new JScrollPane(hex);
        hexScroll.getViewport().setBackground(hex.getBackground());
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(fields), hexScroll);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());

        text.setEditable(false);
        text.setFont(mono);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel empty = new JPanel(new java.awt.GridBagLayout());
        JLabel hint = new JLabel("Ayrıntıları görmek için yukarıdan bir kayıt seçin");
        hint.setForeground(Theme.muted());
        empty.add(hint);

        body.add(empty, "empty");
        body.add(split, "frame");
        body.add(new JScrollPane(text), "text");
        add(body, BorderLayout.CENTER);
        show(null);
    }

    /** Gosterilecek kayit; null bos durumu gosterir. */
    void show(LogEntry e) {
        entry = e;
        copy.setEnabled(e != null);
        if (e == null) {
            dirChip.setVisible(false);
            title.setText(" ");
            meta.setText(" ");
            issues.setText("");
            cards.show(body, "empty");
            return;
        }
        dirChip.setVisible(true);
        switch (e.dir) {
            case RX: dirChip.set("RX", Theme::rx); break;
            case TX: dirChip.set("TX", Theme::tx); break;
            case ERROR: dirChip.set("HATA", Theme::err); break;
            default: dirChip.set("OLAY", Theme::muted);
        }
        StringBuilder m = new StringBuilder(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(e.time)));
        m.append("  ·  ").append(e.endpoint.name());
        if (!e.peer.isEmpty()) m.append("  ·  ").append(e.peer);
        if (e.isFrame()) m.append("  ·  ").append(e.data.length).append(" byte");
        meta.setText(m.toString());
        if (!e.isFrame()) {
            title.setText(e.dir == Direction.ERROR ? "Hata" : "Olay");
            issues.setText("");
            text.setText(e.text);
            text.setCaretPosition(0);
            cards.show(body, "text");
            return;
        }
        Decoded d = e.decoded;
        title.setText(d == null ? "Ham çerçeve" : d.message == null ? "Tanınmayan mesaj" : d.message.name + "  (id " + d.message.id + ")");
        List<String> problems = new ArrayList<>();
        if (d != null && d.error != null) problems.add(d.error);
        if (d != null) problems.addAll(d.warnings);
        issues.setText(problems.isEmpty() ? "" : "⚠ " + String.join(" · ", problems));
        issues.setToolTipText(problems.isEmpty() ? null : "<html>" + String.join("<br>", problems) + "</html>");
        issues.setForeground(d != null && d.error != null ? Theme.err() : Theme.warn());
        List<Decoded.Span> spans = d == null ? Collections.<Decoded.Span>emptyList() : d.spans;
        fieldModel.set(spans);
        hex.set(e.data, spans);
        cards.show(body, "frame");
        if (spans.isEmpty()) split.setDividerLocation(0);
        else if (split.getDividerLocation() < 40) split.setDividerLocation(0.45);
    }

    LogEntry entry() { return entry; }

    // ------------------------------------------------------------------

    private static final class FieldModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLS = {"Ofset", "Boy", "Alan", "Değer"};
        private List<Decoded.Span> spans = Collections.emptyList();

        void set(List<Decoded.Span> s) {
            spans = s;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return spans.size(); }

        @Override public int getColumnCount() { return COLS.length; }

        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override public Object getValueAt(int r, int c) {
            Decoded.Span s = spans.get(r);
            switch (c) {
                case 0: return s.length == 0 ? "" : Integer.toString(s.offset);
                case 1: return s.length == 0 ? "" : Integer.toString(s.length);
                case 2: return s.path;
                default: return s.text;
            }
        }
    }

    /** Ofset | 16 byte hex | ASCII dokumu; alanlar ardisik tonlarla boyanir. */
    static final class HexView extends JComponent implements Scrollable {
        private static final long serialVersionUID = 1L;
        private static final int PER_ROW = 16;
        private static final int PAD = 10;

        private byte[] data = new byte[0];
        private List<Decoded.Span> spans = Collections.emptyList();
        /** Her byte'i kapsayan en dar alanin indeksi; yoksa -1. */
        private int[] owner = new int[0];
        private int selected = -1;
        private int hover = -1;
        java.util.function.IntConsumer onPick = i -> { };

        @Override public void updateUI() {
            super.updateUI();
            // tema degisince zemin ve yazi tipi yeni temadan alinir
            setBackground(UIManager.getColor("Table.background"));
            setFont(Theme.mono(12));
            if (getParent() instanceof javax.swing.JViewport) getParent().setBackground(getBackground());
        }

        HexView() {
            setOpaque(true);
            updateUI();
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int b = byteAt(e.getX(), e.getY());
                    int h = b < 0 ? -1 : owner[b];
                    if (h != hover) {
                        hover = h;
                        repaint();
                    }
                    setToolTipText(b < 0 ? null : tip(b));
                }

                @Override public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }

                @Override public void mousePressed(MouseEvent e) {
                    int b = byteAt(e.getX(), e.getY());
                    select(b < 0 ? -1 : owner[b]);
                    onPick.accept(selected);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void set(byte[] d, List<Decoded.Span> s) {
            data = d == null ? new byte[0] : d;
            spans = s;
            owner = new int[data.length];
            java.util.Arrays.fill(owner, -1);
            for (int i = 0; i < spans.size(); i++) {
                Decoded.Span sp = spans.get(i);
                if (sp.length <= 0) continue;
                for (int b = sp.offset; b < sp.offset + sp.length && b < data.length; b++) {
                    if (b < 0) continue;
                    int cur = owner[b];
                    if (cur < 0 || spans.get(cur).length > sp.length) owner[b] = i;
                }
            }
            selected = -1;
            hover = -1;
            revalidate();
            repaint();
        }

        void select(int span) {
            selected = span;
            repaint();
            if (span >= 0 && span < spans.size() && spans.get(span).length > 0) {
                Decoded.Span sp = spans.get(span);
                Rectangle a = cellRect(Math.min(sp.offset, data.length - 1));
                Rectangle b = cellRect(Math.min(sp.offset + sp.length - 1, data.length - 1));
                if (a != null && b != null) scrollRectToVisible(a.union(b));
            }
        }

        private String tip(int b) {
            int i = owner[b];
            String head = String.format("ofset %d (0x%04X): %02X = %d", b, b, data[b] & 0xFF, data[b] & 0xFF);
            if (i < 0) return head;
            Decoded.Span sp = spans.get(i);
            return "<html><b>" + esc(sp.path) + "</b> = " + esc(sp.text) + "<br>" + head + "</html>";
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        // ---- yerlesim ----

        private int cw() { return getFontMetrics(getFont()).charWidth('0'); }

        private int lh() { return getFontMetrics(getFont()).getHeight() + 3; }

        private int hexX(int col) { return PAD + cw() * (6 + col * 3 + (col >= 8 ? 1 : 0)); }

        private int asciiX(int col) { return PAD + cw() * (6 + PER_ROW * 3 + 3) + col * cw(); }

        private Rectangle cellRect(int b) {
            if (b < 0) return null;
            int row = b / PER_ROW;
            return new Rectangle(hexX(b % PER_ROW) - 2, PAD + row * lh(), cw() * 2 + 4, lh());
        }

        private int byteAt(int x, int y) {
            int row = (y - PAD) / lh();
            if (y < PAD || row < 0) return -1;
            int cw = cw();
            for (int col = 0; col < PER_ROW; col++) {
                int b = row * PER_ROW + col;
                if (b >= data.length) break;
                int hx = hexX(col);
                int ax = asciiX(col);
                if ((x >= hx - 3 && x < hx + 2 * cw + 3) || (x >= ax && x < ax + cw)) return b;
            }
            return -1;
        }

        @Override public Dimension getPreferredSize() {
            int rows = Math.max(1, (data.length + PER_ROW - 1) / PER_ROW);
            return new Dimension(asciiX(PER_ROW) + PAD, PAD * 2 + rows * lh());
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int lh = lh();
            int cw = cw();
            Color fg = UIManager.getColor("Table.foreground");
            Color muted = Theme.muted();
            Color accent = Theme.accent();
            Color bandA = Theme.tint(accent, Theme.dark() ? 40 : 34);
            Color bandB = Theme.tint(Theme.protocol("MB"), Theme.dark() ? 40 : 40);
            Color sel = Theme.tint(accent, Theme.dark() ? 160 : 130);
            Color hov = Theme.tint(accent, Theme.dark() ? 90 : 75);
            Rectangle clip = g.getClipBounds();
            int first = clip == null ? 0 : Math.max(0, (clip.y - PAD) / lh);
            int last = clip == null ? Integer.MAX_VALUE : (clip.y + clip.height - PAD) / lh + 1;
            int rows = (data.length + PER_ROW - 1) / PER_ROW;
            for (int row = first; row < rows && row <= last; row++) {
                int y = PAD + row * lh;
                int base = y + (lh + fm.getAscent() - fm.getDescent()) / 2;
                g.setColor(muted);
                g.drawString(String.format("%04X", row * PER_ROW), PAD, base);
                for (int col = 0; col < PER_ROW; col++) {
                    int b = row * PER_ROW + col;
                    if (b >= data.length) break;
                    int o = owner[b];
                    Color bg = o < 0 ? null : o == selected ? sel : o == hover ? hov : (o % 2 == 0 ? bandA : bandB);
                    int hx = hexX(col);
                    int ax = asciiX(col);
                    if (bg != null) {
                        g.setColor(bg);
                        // ayni alanin ardisik byte'lari kesintisiz bir serit olarak boyanir
                        boolean joinNext = col < PER_ROW - 1 && b + 1 < data.length && owner[b + 1] == o;
                        int w = joinNext ? hexX(col + 1) - hx : 2 * cw;
                        g.fillRect(hx - 2, y + 1, w + (joinNext ? 0 : 4), lh - 2);
                        g.fillRect(ax, y + 1, cw, lh - 2);
                    }
                    int v = data[b] & 0xFF;
                    g.setColor(o == selected && o >= 0 ? fg : o < 0 ? muted : fg);
                    g.drawString(Hex.byteHex(v), hx, base);
                    g.setColor(v >= 0x20 && v < 0x7F ? fg : muted);
                    g.drawString(v >= 0x20 && v < 0x7F ? String.valueOf((char) v) : "·", ax, base);
                }
            }
            if (data.length == 0) {
                g.setColor(muted);
                g.drawString("(boş çerçeve)", PAD, PAD + fm.getAscent());
            }
            g.setColor(Theme.separator());
            int sx = asciiX(0) - cw - cw / 2;
            g.drawLine(sx, PAD, sx, PAD + Math.max(1, rows) * lh);
            g.dispose();
        }

        // ---- Scrollable ----

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return lh(); }

        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return o == SwingConstants.VERTICAL ? Math.max(lh(), r.height - lh()) : r.width;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() != null && getParent().getWidth() > getPreferredSize().width;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
