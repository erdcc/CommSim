package comm.ui;

import comm.core.Endpoint;
import comm.core.ResponseRule;
import comm.runtime.RuleEngine;
import comm.schema.MessageDef;
import comm.schema.Schema;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Collections;
import java.util.List;

/** Otomatik yanit kurallari: gelen mesaja (kosulla) gecikmeli yanit. Ilk eslesen kural calisir. */
final class RulesPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<String> whenEditor = new JComboBox<>();
    private final JComboBox<String> replyEditor = new JComboBox<>();
    private final JLabel status = new JLabel(" ");
    private final RuleEngine engine;
    private Endpoint endpoint;

    RulesPanel(RuleEngine engine) {
        super(new BorderLayout(0, 4));
        this.engine = engine;
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JLabel help = UiKit.muted("<html>Gelen mesaj eşleşince yanıt, gönderen eşe gider; ilk eşleşen kural çalışır. "
                + "Koşul: <tt>command=GOTO; lat&gt;40</tt> &nbsp;·&nbsp; Atamalar: <tt>başlık.seq=$rx.başlık.seq; status=OK; uptime=counter(0,1)</tt>"
                + " &nbsp;·&nbsp; Gelen/yanıt: mesaj adı, <tt>*</tt>, <tt>hex:01 02</tt>, <tt>text:OK\\r\\n</tt></html>");
        UiKit.style(help, "small");
        add(help, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        whenEditor.setEditable(true);
        replyEditor.setEditable(true);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(whenEditor));
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(replyEditor));
        int[] w = {44, 150, 170, 80, 150, 330, 70};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton add = UiKit.button("Kural ekle", Icons.ADD);
        JButton del = UiKit.button("Sil", Icons.DELETE);
        JButton up = new JButton("↑");
        JButton down = new JButton("↓");
        btns.add(add);
        btns.add(del);
        btns.add(up);
        btns.add(down);
        btns.add(status);
        add(btns, BorderLayout.SOUTH);

        add.addActionListener(e -> {
            if (endpoint == null) return;
            ResponseRule r = new ResponseRule();
            Schema s = endpoint.schema();
            if (s != null && !s.messages().isEmpty()) {
                r.when = s.messages().get(0).name;
                r.reply = s.messages().get(0).name;
            } else {
                r.when = "*";
                r.reply = "text:OK\\r\\n";
            }
            endpoint.config().rules.add(r);
            model.fireTableDataChanged();
            int last = model.getRowCount() - 1;
            table.setRowSelectionInterval(last, last);
        });
        del.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (endpoint != null && r >= 0) {
                endpoint.config().rules.remove(r);
                model.fireTableDataChanged();
            }
        });
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
    }

    private void move(int dir) {
        int r = table.getSelectedRow();
        if (endpoint == null || r < 0) return;
        List<ResponseRule> list = endpoint.config().rules;
        int j = r + dir;
        if (j < 0 || j >= list.size()) return;
        ResponseRule a = list.get(r);
        list.set(r, list.get(j));
        list.set(j, a);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(j, j);
    }

    void setEndpoint(Endpoint ep) {
        endpoint = ep;
        whenEditor.removeAllItems();
        replyEditor.removeAllItems();
        whenEditor.addItem("*");
        Schema s = ep == null ? null : ep.schema();
        if (s != null) {
            for (MessageDef m : s.messages()) {
                whenEditor.addItem(m.name);
                replyEditor.addItem(m.name);
            }
        }
        whenEditor.addItem("hex:");
        replyEditor.addItem("hex:");
        replyEditor.addItem("text:");
        replyEditor.addItem("");
        status.setText(" ");
        model.fireTableDataChanged();
    }

    /** Tetiklenme sayilarini yeniler. */
    void refresh() {
        if (!table.isEditing() && model.getRowCount() > 0) model.fireTableRowsUpdated(0, model.getRowCount() - 1);
    }

    private final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Etkin", "Gelen", "Koşul", "Gecikme (ms)", "Yanıt", "Atamalar", "Tetiklendi"};

        List<ResponseRule> list() {
            return endpoint == null ? Collections.<ResponseRule>emptyList() : endpoint.config().rules;
        }

        @Override public int getRowCount() { return list().size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : c == 3 ? Integer.class : c == 6 ? Long.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) { return c != 6; }

        @Override public Object getValueAt(int r, int c) {
            ResponseRule x = list().get(r);
            switch (c) {
                case 0: return x.enabled;
                case 1: return x.when;
                case 2: return x.condition;
                case 3: return x.delayMs;
                case 4: return x.reply;
                case 5: return x.assignments;
                default: return engine.count(x);
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            ResponseRule x = list().get(r);
            ResponseRule trial = x.copy();
            String s = v == null ? "" : v.toString();
            switch (c) {
                case 0: trial.enabled = (Boolean) v; break;
                case 1: trial.when = s.trim(); break;
                case 2: trial.condition = s.trim(); break;
                case 3: trial.delayMs = Math.max(0, Math.min(600_000, (Integer) v)); break;
                case 4: trial.reply = s.trim(); break;
                default: trial.assignments = s.trim();
            }
            try {
                RuleEngine.validate(trial);
            } catch (IllegalArgumentException e) {
                status.setForeground(Theme.err());
                status.setText("Kural " + (r + 1) + ": " + e.getMessage());
                return;
            }
            x.enabled = trial.enabled;
            x.when = trial.when;
            x.condition = trial.condition;
            x.delayMs = trial.delayMs;
            x.reply = trial.reply;
            x.assignments = trial.assignments;
            status.setText(" ");
            fireTableRowsUpdated(r, r);
        }
    }
}
