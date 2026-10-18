package comm.ui;

import comm.core.Hex;
import comm.core.LogEntry;
import comm.schema.Decoded;
import comm.schema.Values;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Window;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Iki cerceveyi alan alan (cozulmusse) ve byte byte karsilastirir; farklar vurgulanir. */
final class CompareDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    CompareDialog(Window owner, LogEntry a, LogEntry b) {
        super(owner, "Karşılaştır", ModalityType.MODELESS);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
        String ha = "A: " + fmt.format(new Date(a.time)) + "  " + a.endpoint.name() + " " + a.dir.name() + "  " + a.data.length + " B";
        String hb = "B: " + fmt.format(new Date(b.time)) + "  " + b.endpoint.name() + " " + b.dir.name() + "  " + b.data.length + " B";

        // alanlar
        Map<String, String> fa = fields(a.decoded);
        Map<String, String> fb = fields(b.decoded);
        Set<String> keys = new LinkedHashSet<>(fa.keySet());
        keys.addAll(fb.keySet());
        List<String[]> rows = new ArrayList<>();
        int diffFields = 0;
        for (String k : keys) {
            String x = fa.get(k);
            String y = fb.get(k);
            boolean diff = !Objects.equals(x, y);
            if (diff) diffFields++;
            rows.add(new String[]{k, x == null ? "—" : x, y == null ? "—" : y, diff ? "1" : ""});
        }
        JTable fieldTable = table(rows, new String[]{"Alan", "A", "B"});

        // byte'lar: 16'sar satir, farkli byte'lar vurgulu
        List<String[]> hexRows = new ArrayList<>();
        int max = Math.max(a.data.length, b.data.length);
        int diffBytes = 0;
        for (int off = 0; off < max; off += 16) {
            StringBuilder sa = new StringBuilder("<html><tt>");
            StringBuilder sb = new StringBuilder("<html><tt>");
            boolean lineDiff = false;
            for (int i = off; i < Math.min(off + 16, max); i++) {
                boolean has1 = i < a.data.length;
                boolean has2 = i < b.data.length;
                boolean d = !has1 || !has2 || a.data[i] != b.data[i];
                if (d) {
                    diffBytes++;
                    lineDiff = true;
                }
                sa.append(cell(has1 ? String.format("%02X", a.data[i] & 0xFF) : "··", d)).append(' ');
                sb.append(cell(has2 ? String.format("%02X", b.data[i] & 0xFF) : "··", d)).append(' ');
            }
            hexRows.add(new String[]{String.format("%04X", off), sa + "</tt></html>", sb + "</tt></html>", lineDiff ? "1" : ""});
        }
        JTable hexTable = table(hexRows, new String[]{"Ofset", "A (hex)", "B (hex)"});
        hexTable.getColumnModel().getColumn(0).setMaxWidth(70);

        JLabel summary = new JLabel("<html><b>" + (a.decoded != null && a.decoded.message != null ? a.decoded.message.name : "?")
                + " ↔ " + (b.decoded != null && b.decoded.message != null ? b.decoded.message.name : "?") + "</b> &nbsp; · &nbsp; "
                + (keys.isEmpty() ? "çözülmüş alan yok" : diffFields + " / " + keys.size() + " alan farklı") + " &nbsp; · &nbsp; "
                + diffBytes + " byte farklı<br><font color='#888888'>" + esc(ha) + "<br>" + esc(hb) + "</font></html>");
        summary.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(fieldTable), new JScrollPane(hexTable));
        split.setResizeWeight(0.55);
        getContentPane().add(summary, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        setSize(860, 640);
        setLocationRelativeTo(owner);
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    private static String cell(String hex, boolean diff) {
        if (!diff) return hex;
        String c = String.format("#%06X", Theme.err().getRGB() & 0xFFFFFF);
        return "<b><font color='" + c + "'>" + hex + "</font></b>";
    }

    /** Cozumun baslik + govde degerleri (yoksa bos). */
    static Map<String, String> fields(Decoded d) {
        Map<String, String> out = new LinkedHashMap<>();
        if (d == null || d.message == null) return out;
        for (Decoded.Span s : d.spans) out.put(s.path, s.text);
        if (out.isEmpty()) out.putAll(Values.receivedTexts(d.message.body, d.body, ""));
        return out;
    }

    private static JTable table(List<String[]> rows, String[] cols) {
        JTable t = new JTable(new AbstractTableModel() {
            private static final long serialVersionUID = 1L;

            @Override public int getRowCount() { return rows.size(); }

            @Override public int getColumnCount() { return cols.length; }

            @Override public String getColumnName(int c) { return cols[c]; }

            @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        });
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(tb, v, sel, f, r, c);
                boolean diff = "1".equals(rows.get(r)[3]);
                setFont(c == 0 ? tb.getFont() : Theme.mono(12));
                if (!sel) {
                    Color base = r % 2 == 1 && javax.swing.UIManager.getColor("Table.alternateRowColor") != null
                            ? javax.swing.UIManager.getColor("Table.alternateRowColor") : tb.getBackground();
                    setBackground(diff ? Theme.tint(Theme.warn(), Theme.dark() ? 55 : 40) : base);
                    setForeground(tb.getForeground());
                }
                return this;
            }
        });
        return t;
    }
}
