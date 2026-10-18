package comm.ui;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.schema.Decoded;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Trafik logu: filtre cubugu, tablo ve secilen satirin hex dump'i. */
final class TrafficPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String ALL = "Tümü";
    private static final String[] DIRECTIONS = {"Tümü", "Alınan", "Gönderilen", "Olaylar"};

    private final TrafficTableModel model = new TrafficTableModel();
    private final JTable table = new JTable(model);
    private final JComboBox<Object> endpointBox = new JComboBox<>();
    private final JComboBox<String> dirBox = new JComboBox<>(DIRECTIONS);
    private final JTextField search = new JTextField(18);
    private final JCheckBox autoScroll = new JCheckBox("Otomatik kaydır", true);
    private final JLabel countLabel = new JLabel();
    private final JTextArea detail = new JTextArea();

    TrafficPanel() {
        super(new BorderLayout());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        UiKit.search(search, "Ara: hex (01 0A), metin, mesaj adı");
        search.setColumns(22);
        bar.add(search);
        bar.add(UiKit.muted("  Bağlantı"));
        endpointBox.addItem(ALL);
        bar.add(endpointBox);
        bar.add(UiKit.muted("Yön"));
        bar.add(dirBox);
        bar.add(autoScroll);
        JButton clear = UiKit.button("Temizle", Icons.CLEAR);
        bar.add(clear);
        UiKit.style(countLabel, "small");
        countLabel.setForeground(Theme.muted());
        bar.add(countLabel);
        add(bar, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setToolTipText("Yanıt (ms): alınan çerçevede, aynı bağlantıdan yapılan son gönderimden bu yana geçen süre");
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }

            private void popup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger() || actions == null) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0) return;
                if (!table.isRowSelected(r)) table.setRowSelectionInterval(r, r);
                contextMenu().show(table, e.getX(), e.getY());
            }
        });
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        Font mono = Theme.mono(12);
        table.setDefaultRenderer(Object.class, new RowRenderer(mono));
        table.setDefaultRenderer(String.class, new RowRenderer(mono));
        table.setDefaultRenderer(Long.class, new RowRenderer(mono));
        table.setRowHeight(24);
        TableColumnModel cols = table.getColumnModel();
        cols.getColumn(3).setCellRenderer(new DirectionRenderer());
        int[] widths = {48, 116, 150, 58, 130, 44, 70, 170, 330, 140};
        for (int i = 0; i < widths.length; i++) cols.getColumn(i).setPreferredWidth(widths[i]);

        detail.setEditable(false);
        detail.setFont(mono);
        detail.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);

        endpointBox.addActionListener(e -> applyFilter());
        dirBox.addActionListener(e -> applyFilter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        clear.addActionListener(e -> {
            model.clear();
            detail.setText("");
            updateCount();
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });
        updateCount();
        modeChanged();
    }

    /** Baglanti listesi degisti; filtre kutusunu gunceller (secim korunur). */
    void setEndpoints(List<Endpoint> endpoints) {
        Object selected = endpointBox.getSelectedItem();
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
        m.addElement(ALL);
        for (Endpoint ep : endpoints) m.addElement(ep);
        endpointBox.setModel(m);
        endpointBox.setSelectedItem(endpoints.contains(selected) ? selected : ALL);
    }

    void append(List<LogEntry> batch) {
        if (batch.isEmpty()) return;
        boolean atEnd = autoScroll.isSelected();
        model.append(batch);
        updateCount();
        if (atEnd && model.getRowCount() > 0) {
            int last = model.getRowCount() - 1;
            SwingUtilities.invokeLater(() -> table.scrollRectToVisible(table.getCellRect(last, 0, true)));
        }
    }

    private void applyFilter() {
        Object ep = endpointBox.getSelectedItem();
        int dir = dirBox.getSelectedIndex();
        String q = search.getText().trim();
        Predicate<LogEntry> f = e -> {
            if (ep instanceof Endpoint && e.endpoint != ep) return false;
            switch (dir) {
                case 1: if (e.dir != Direction.RX) return false; break;
                case 2: if (e.dir != Direction.TX) return false; break;
                case 3: if (e.isFrame()) return false; break;
                default: break;
            }
            return true;
        };
        if (!q.isEmpty()) f = f.and(searchFilter(q));
        model.setFilter(f);
        updateCount();
    }

    /** Metin; es, olay metni ve ASCII'de aranir. Gecerli hex ise byte dizisi olarak da aranir. */
    private static Predicate<LogEntry> searchFilter(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        byte[] pattern;
        try {
            pattern = Hex.parse(q);
        } catch (IllegalArgumentException e) {
            pattern = null;
        }
        byte[] hex = pattern != null && pattern.length > 0 ? pattern : null;
        byte[] text = q.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return e -> {
            if (e.peer.toLowerCase(Locale.ROOT).contains(lower)) return true;
            if (e.decoded != null && e.decoded.message != null
                    && e.decoded.message.name.toLowerCase(Locale.ROOT).contains(lower)) return true;
            if (!e.isFrame()) return e.text.toLowerCase(Locale.ROOT).contains(lower);
            return (hex != null && indexOf(e.data, hex) >= 0) || indexOf(e.data, text) >= 0;
        };
    }

    static int indexOf(byte[] data, byte[] p) {
        outer:
        for (int i = 0; i + p.length <= data.length; i++) {
            for (int k = 0; k < p.length; k++) {
                if (data[i + k] != p[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void showDetail() {
        int r = table.getSelectedRow();
        if (r < 0) {
            detail.setText("");
            return;
        }
        detail.setText(describe(model.row(table.convertRowIndexToModel(r)).entry));
        detail.setCaretPosition(0);
    }

    /** Trafik satirlarindan tetiklenen eylemler (ana pencere saglar). */
    interface Actions {
        void resend(LogEntry e);

        void copyToForm(LogEntry e);

        void toRaw(LogEntry e);

        void compare(LogEntry a, LogEntry b);

        void chart(LogEntry e);
    }

    private Actions actions;
    private JSplitPane split;

    /** Yerlesim: tablo/detay orani ve sutun genislikleri. */
    double splitRatio() {
        int h = split.getHeight() - split.getDividerSize();
        return h <= 0 ? -1 : (double) split.getDividerLocation() / h;
    }

    void setSplitRatio(double r) {
        if (r > 0.05 && r < 0.98) split.setDividerLocation(r);
    }

    int[] columnWidths() {
        java.util.List<javax.swing.table.TableColumn> cols = allColumns;
        int[] w = new int[cols.size()];
        for (int i = 0; i < w.length; i++) w[i] = cols.get(i).getPreferredWidth();
        return w;
    }

    void setColumnWidths(int[] w) {
        java.util.List<javax.swing.table.TableColumn> cols = allColumns;
        if (w == null || w.length != cols.size()) return;
        for (int i = 0; i < w.length; i++) if (w[i] > 10) cols.get(i).setPreferredWidth(w[i]);
    }

    void setActions(Actions a) { actions = a; }

    /** Tum sutunlar (model sirasiyla); gizleme/gosterme ve yerlesim kaydi icin. */
    private java.util.List<javax.swing.table.TableColumn> allColumns;
    /** Basit gorunumde gizlenen sutunlar: Yanit (ms), ASCII. */
    private static final int[] ADVANCED_COLUMNS = {6, 9};

    void modeChanged() {
        javax.swing.table.TableColumnModel cm = table.getColumnModel();
        if (allColumns == null) {
            allColumns = new java.util.ArrayList<>();
            for (int i = 0; i < cm.getColumnCount(); i++) allColumns.add(cm.getColumn(i));
        }
        while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
        outer:
        for (javax.swing.table.TableColumn c : allColumns) {
            if (!UiMode.advanced()) for (int a : ADVANCED_COLUMNS) if (c.getModelIndex() == a) continue outer;
            cm.addColumn(c);
        }
    }

    private List<LogEntry> selectedEntries() {
        List<LogEntry> out = new java.util.ArrayList<>();
        for (int r : table.getSelectedRows()) out.add(model.row(r).entry);
        return out;
    }

    private javax.swing.JPopupMenu contextMenu() {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        List<LogEntry> sel = selectedEntries();
        LogEntry e = sel.isEmpty() ? null : sel.get(0);
        boolean frame = e != null && e.isFrame();
        boolean message = frame && e.decoded != null && e.decoded.message != null && e.endpoint.schema() != null
                && e.endpoint.schema().byName(e.decoded.message.name) == e.decoded.message;
        m.add(menu("Yeniden gönder", Icons.SEND, frame && sel.size() == 1, () -> actions.resend(e)));
        m.add(menu("Mesaj formuna kopyala", Icons.EDIT, message && sel.size() == 1, () -> actions.copyToForm(e)));
        m.add(menu("Ham sekmesine al", Icons.COPY, frame && sel.size() == 1, () -> actions.toRaw(e)));
        m.add(menu("Hex'i panoya kopyala", Icons.COPY, frame, () -> {
            StringBuilder sb = new StringBuilder();
            for (LogEntry x : sel) if (x.isFrame()) sb.append(sb.length() > 0 ? "\n" : "").append(Hex.toHex(x.data));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(sb.toString()), null);
        }));
        m.addSeparator();
        boolean two = sel.size() == 2 && sel.get(0).isFrame() && sel.get(1).isFrame();
        if (UiMode.advanced()) {
            m.add(menu(two ? "Karşılaştır" : "Karşılaştır (iki çerçeve seçin)", Icons.SCHEMA, two, () -> actions.compare(sel.get(0), sel.get(1))));
        }
        m.add(menu("Değeri grafiğe ekle…", Icons.REPEAT, message && sel.size() == 1, () -> actions.chart(e)));
        return m;
    }

    private static javax.swing.JMenuItem menu(String text, javax.swing.Icon icon, boolean enabled, Runnable r) {
        javax.swing.JMenuItem i = new javax.swing.JMenuItem(text, icon);
        i.setEnabled(enabled);
        i.addActionListener(ev -> r.run());
        return i;
    }

    /** Gorunen (filtreye uyan) kayitlar, tablodaki sirayla. */
    List<LogEntry> visibleEntries() {
        List<LogEntry> out = new java.util.ArrayList<>();
        for (TrafficTableModel.Row r : model.visibleRows()) out.add(r.entry);
        return out;
    }

    /** Bir kaydin tam metin dokumu: baslik, cozum ve hex dump. */
    static String describe(LogEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(e.time))).append("  ").append(e.endpoint.name()).append("  ")
                .append(e.dir.symbol).append(' ').append(directionName(e.dir));
        if (!e.peer.isEmpty()) sb.append("  ").append(e.peer);
        sb.append('\n');
        if (e.isFrame()) {
            sb.append(e.data.length).append(" byte\n");
            if (e.decoded != null) appendDecoded(sb, e.decoded, e.data);
            sb.append('\n').append(Hex.dump(e.data));
        } else {
            sb.append('\n').append(e.text);
        }
        return sb.toString();
    }

    /** Cozumu alan alan yazar: ofset, boy, yol = deger, ham byte'lar. */
    private static void appendDecoded(StringBuilder sb, Decoded d, byte[] data) {
        sb.append('\n');
        if (d.message != null) sb.append("Mesaj: ").append(d.message.id).append(" - ").append(d.message.name).append('\n');
        if (d.error != null) sb.append("HATA: ").append(d.error).append('\n');
        for (String w : d.warnings) sb.append("UYARI: ").append(w).append('\n');
        if (d.spans.isEmpty()) return;
        int width = 4;
        for (Decoded.Span sp : d.spans) width = Math.max(width, sp.path.length());
        sb.append('\n').append(String.format("%-6s %-4s %-" + width + "s   %s%n", "ofset", "boy", "alan", "değer"));
        for (Decoded.Span sp : d.spans) {
            if (sp.length == 0) { // ic alanin konumu bilinmiyor (orn. SNMP degiskenleri)
                sb.append(String.format("              %-" + width + "s = %s%n", sp.path, sp.text));
                continue;
            }
            String raw = Hex.toHex(data, sp.offset, Math.min(sp.length, 16), " ") + (sp.length > 16 ? " …" : "");
            sb.append(String.format("%04X   %-4d %-" + width + "s = %s   [%s]%n", sp.offset, sp.length, sp.path, sp.text, raw));
        }
    }

    private static String directionName(Direction d) {
        switch (d) {
            case RX: return "alındı";
            case TX: return "gönderildi";
            case ERROR: return "hata";
            default: return "olay";
        }
    }

    private void updateCount() {
        countLabel.setText(model.getRowCount() + " / " + model.totalCount() + " kayıt");
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Font mono;

        RowRenderer(Font mono) { this.mono = mono; }

        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                               boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            col = t.convertColumnIndexToModel(col); // gizli sutunlar gorunum sirasini kaydirir
            setFont(col == 2 || col == 7 ? t.getFont() : mono);
            setHorizontalAlignment(value instanceof Long ? RIGHT : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (!sel) {
                LogEntry e = model.row(row).entry;
                Color fg = t.getForeground();
                if (e.dir == Direction.ERROR) fg = Theme.err();
                else if (e.dir == Direction.INFO) fg = Theme.muted();
                else if (col == 7 && value != null && value.toString().contains("⚠")) fg = Theme.warn();
                else if (col == 0 || col == 1 || col == 6 || col == 9) fg = Theme.muted();
                setForeground(fg);
            }
            return this;
        }
    }

    /** Yon sutunu: renkli RX / TX / OLAY / HATA rozeti. */
    private final class DirectionRenderer extends javax.swing.JPanel implements javax.swing.table.TableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Chip chip = new Chip(" ");

        DirectionRenderer() {
            super(new java.awt.GridBagLayout());
            add(chip);
        }

        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                               boolean focus, int row, int col) {
            Direction d = model.row(row).entry.dir;
            switch (d) {
                case RX: chip.set("RX", Theme::rx); break;
                case TX: chip.set("TX", Theme::tx); break;
                case ERROR: chip.set("HATA", Theme::err); break;
                default: chip.set("OLAY", Theme::muted);
            }
            Color alt = javax.swing.UIManager.getColor("Table.alternateRowColor");
            setBackground(sel ? t.getSelectionBackground() : alt != null && row % 2 == 1 ? alt : t.getBackground());
            return this;
        }
    }
}
