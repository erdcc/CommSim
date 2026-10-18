package comm.ui;

import comm.core.Endpoint;
import comm.core.Stats;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Tum baglantilarin sayaclari ve saniyelik hizlari. */
final class StatsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final class Rate {
        long rxFrames;
        long txFrames;
        long rxBytes;
        long txBytes;
        long time;
        double rxPerSec;
        double txPerSec;
        double rxBps;
        double txBps;
    }

    private final Supplier<List<Endpoint>> source;
    private final Map<Endpoint, Rate> rates = new IdentityHashMap<>();
    private final Model model = new Model();
    private List<Endpoint> rows = new ArrayList<>();

    StatsPanel(Supplier<List<Endpoint>> source) {
        super(new BorderLayout());
        this.source = source;
        JTable table = new JTable(model);
        int[] w = {150, 120, 80, 40, 80, 80, 150, 80, 80, 150, 50, 220};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton reset = UiKit.button("Sayaçları sıfırla", Icons.RELOAD);
        bar.add(reset);
        add(bar, BorderLayout.NORTH);
        reset.addActionListener(e -> {
            for (Endpoint ep : source.get()) ep.stats().reset();
            rates.clear();
            refresh();
        });
    }

    /** Saniyede bir cagrilir. */
    void refresh() {
        rows = source.get();
        long now = System.nanoTime();
        for (Endpoint ep : rows) {
            Stats s = ep.stats();
            Rate r = rates.get(ep);
            if (r == null) {
                r = new Rate();
                rates.put(ep, r);
            } else {
                double dt = (now - r.time) / 1e9;
                if (dt > 0) {
                    r.rxPerSec = (s.rxFrames() - r.rxFrames) / dt;
                    r.txPerSec = (s.txFrames() - r.txFrames) / dt;
                    r.rxBps = (s.rxBytes() - r.rxBytes) / dt;
                    r.txBps = (s.txBytes() - r.txBytes) / dt;
                }
            }
            r.rxFrames = s.rxFrames();
            r.txFrames = s.txFrames();
            r.rxBytes = s.rxBytes();
            r.txBytes = s.txBytes();
            r.time = now;
        }
        rates.keySet().retainAll(rows);
        model.fireTableDataChanged();
    }

    static String bytes(double n) {
        if (n < 1024) return String.format(Locale.ROOT, "%.0f B", n);
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024);
        return String.format(Locale.ROOT, "%.1f MB", n / (1024 * 1024));
    }

    private final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Bağlantı", "Tür", "Durum", "Eş", "RX çerçeve", "RX byte", "RX/s",
                "TX çerçeve", "TX byte", "TX/s", "Hata", "Son hata"};

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Object getValueAt(int row, int c) {
            Endpoint ep = rows.get(row);
            Stats s = ep.stats();
            Rate r = rates.get(ep);
            switch (c) {
                case 0: return ep.name();
                case 1: return ep.config().kind.label;
                case 2: return ep.state().label;
                case 3: return ep.peers().size();
                case 4: return s.rxFrames();
                case 5: return bytes(s.rxBytes());
                case 6: return r == null ? "" : String.format(Locale.ROOT, "%.1f  (%s/s)", r.rxPerSec, bytes(r.rxBps));
                case 7: return s.txFrames();
                case 8: return bytes(s.txBytes());
                case 9: return r == null ? "" : String.format(Locale.ROOT, "%.1f  (%s/s)", r.txPerSec, bytes(r.txBps));
                case 10: return s.errors();
                default: return s.lastError();
            }
        }
    }
}
