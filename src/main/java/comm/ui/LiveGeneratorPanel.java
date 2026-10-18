package comm.ui;

import comm.core.Endpoint;
import comm.core.LiveGenerator;
import comm.runtime.Expr;
import comm.runtime.LiveGenerators;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Collections;
import java.util.List;

/** Modbus slave / SNMP agent icin "canli degerler": hedef + ifade + aralik. */
final class LiveGeneratorPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final String targetHint;
    private Endpoint endpoint;

    LiveGeneratorPanel(String title, String targetHint, String defaultTarget) {
        super(new BorderLayout(0, 4));
        this.targetHint = targetHint;
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 4));
        JPanel head = new JPanel(new BorderLayout());
        head.add(UiKit.style(new JLabel(title), "semibold"), BorderLayout.WEST);
        JLabel hint = UiKit.muted("ör. sine(0,100,5000)  counter(0,1)  random(10,20)  list(1|2|3)");
        UiKit.style(hint, "small");
        head.add(hint, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setPreferredScrollableViewportSize(new Dimension(360, 80));
        int[] w = {40, 140, 190, 80};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton add = UiKit.button("Ekle", Icons.ADD);
        JButton del = UiKit.button("Sil", Icons.DELETE);
        btns.add(add);
        btns.add(del);
        btns.add(status);
        add(btns, BorderLayout.SOUTH);
        // JTable basliklari alan atamasindan once olusturuldu; hedef ipucunu simdi yaz
        table.getColumnModel().getColumn(1).setHeaderValue("Hedef (" + targetHint + ")");
        add.addActionListener(e -> {
            if (endpoint == null) return;
            LiveGenerator g = new LiveGenerator();
            g.target = defaultTarget;
            g.expr = "sine(0,100,10000)";
            endpoint.config().liveGenerators.add(g);
            model.fireTableDataChanged();
        });
        del.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (endpoint != null && r >= 0) {
                endpoint.config().liveGenerators.remove(r);
                model.fireTableDataChanged();
            }
        });
    }

    void setEndpoint(Endpoint ep) {
        endpoint = ep;
        status.setText(" ");
        model.fireTableDataChanged();
    }

    /** Hata ile kapatilan uretecleri gostermek icin periyodik cagrilir. */
    void refresh() {
        if (!table.isEditing()) model.fireTableRowsUpdated(0, Math.max(0, model.getRowCount() - 1));
    }

    private void showError(String s) {
        status.setForeground(Theme.err());
        status.setText(s);
    }

    private final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Etkin", "Hedef", "İfade", "Aralık (ms)"};

        List<LiveGenerator> list() {
            return endpoint == null ? Collections.<LiveGenerator>emptyList() : endpoint.config().liveGenerators;
        }

        @Override public int getRowCount() { return list().size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return c == 1 ? cols[1] + " (" + targetHint + ")" : cols[c]; }

        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 3 ? Integer.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            LiveGenerator g = list().get(r);
            switch (c) {
                case 0: return g.enabled;
                case 1: return g.target;
                case 2: return g.expr;
                default: return g.intervalMs;
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            LiveGenerator g = list().get(r);
            try {
                switch (c) {
                    case 0: g.enabled = (Boolean) v; break;
                    case 1:
                        LiveGenerators.checkTarget(endpoint, v.toString());
                        g.target = v.toString().trim();
                        break;
                    case 2:
                        Expr.parse(v.toString());
                        g.expr = v.toString().trim();
                        break;
                    default: g.intervalMs = Math.max(20, Math.min(3_600_000, (Integer) v));
                }
                status.setText(" ");
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
