package comm.ui;

import comm.runtime.ParameterStore;

import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.StructDef;
import comm.schema.Values;
import comm.schema.Values.Row;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tanimli tum mesajlarin parametreleri: gonderilecek deger (duzenlenebilir), son alinan ve son gonderilen.
 * Mesaj satirina cift tiklamak alanlari acar/kapatir.
 */
final class ParametersPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private enum Kind { SECTION, MESSAGE, FIELD }

    private static final class Item {
        final Kind kind;
        final ParameterStore.MsgState state;
        final Row row;
        final String label;
        final String rx;
        final String tx;
        final boolean warn;

        Item(Kind kind, ParameterStore.MsgState state, Row row, String label, String rx, String tx, boolean warn) {
            this.kind = kind;
            this.state = state;
            this.row = row;
            this.label = label;
            this.rx = rx;
            this.tx = tx;
            this.warn = warn;
        }
    }

    private final Model model = new Model();
    private final BoolCells.Renderer boolRenderer = new BoolCells.Renderer();
    private final javax.swing.table.TableCellEditor boolEditor = BoolCells.editor();
    /** Gonderilecek sutununda bool/bit bayraklari onay kutusu. */
    private final JTable table = new JTable(model) {
        private static final long serialVersionUID = 1L;

        private boolean isBool(int row, int col) {
            if (col != 1 || row < 0 || row >= model.items.size()) return false;
            Item it = model.items.get(row);
            return it.kind == Kind.FIELD && it.row.type == Row.Type.VALUE
                    && it.row.field.kind == comm.schema.FieldDef.Kind.BOOL;
        }

        @Override public javax.swing.table.TableCellRenderer getCellRenderer(int row, int col) {
            return isBool(row, col) ? boolRenderer : super.getCellRenderer(row, col);
        }

        @Override public javax.swing.table.TableCellEditor getCellEditor(int row, int col) {
            if (isBool(row, col)) return boolEditor;
            if (col == 1 && row >= 0 && row < model.items.size() && model.items.get(row).kind == Kind.FIELD) {
                javax.swing.table.TableCellEditor ed = ValueCells.editor(model.items.get(row).row, ParametersPanel.this::showError);
                if (ed != null) return ed;
            }
            return super.getCellEditor(row, col);
        }
    };
    private final JTextField search = new JTextField(14);
    private final JCheckBox onlyReceived = new JCheckBox("Yalnız alınanlar");
    private final JLabel status = new JLabel(" ");
    private final JButton sendBtn = UiKit.primary("Seçili mesajı gönder", Icons.SEND);
    private final Set<String> collapsed = new HashSet<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private final Consumer<byte[]> sender;
    private final BooleanSupplier canSend;
    private final Consumer<MessageDef> openInForm;
    private ParameterStore store;
    private long seenVersion = -1;

    ParametersPanel(Consumer<byte[]> sender, BooleanSupplier canSend, Consumer<MessageDef> openInForm) {
        super(new BorderLayout(0, 4));
        this.sender = sender;
        this.canSend = canSend;
        this.openInForm = openInForm;
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        UiKit.search(search, "Ara: mesaj ya da alan");
        search.setColumns(18);
        bar.add(search);
        bar.add(onlyReceived);
        JButton expand = new JButton("Tümünü aç");
        JButton collapse = new JButton("Tümünü kapat");
        JButton form = UiKit.button("Formda aç", Icons.EDIT);
        JButton chartBtn = UiKit.button("Grafiğe ekle", Icons.REPEAT);
        chartBtn.setToolTipText("Seçili sayısal alanın alınan değerlerini Grafik sekmesinde çizer");
        chartBtn.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0 || chartAction == null) return;
            Item it = model.items.get(r);
            if (it.kind == Kind.FIELD && it.state != null) chartAction.accept(it.state.def, it.row.path);
        });
        bar.add(expand);
        bar.add(collapse);
        bar.add(form);
        bar.add(chartBtn);
        bar.add(sendBtn);
        add(bar, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(table.getRowHeight(), 20));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] widths = {220, 170, 170, 170, 150, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setDefaultRenderer(Object.class, new Renderer());
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        modeChanged();

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild(); }
        });
        onlyReceived.addActionListener(e -> rebuild());
        expand.addActionListener(e -> {
            collapsed.clear();
            rebuild();
        });
        collapse.addActionListener(e -> {
            if (store != null) for (ParameterStore.MsgState st : store.states()) collapsed.add(st.def.name);
            rebuild();
        });
        form.addActionListener(e -> {
            MessageDef m = selectedMessage();
            if (m != null) openInForm.accept(m);
        });
        sendBtn.addActionListener(e -> sendSelected());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0 && model.items.get(r).kind == Kind.MESSAGE) {
                    String n = model.items.get(r).state.def.name;
                    if (!collapsed.remove(n)) collapsed.add(n);
                    rebuild();
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        setStore(null);
    }

    private java.util.function.BiConsumer<MessageDef, String> chartAction;

    void setChartAction(java.util.function.BiConsumer<MessageDef, String> a) { chartAction = a; }

    private javax.swing.table.TableColumn generatorColumn;

    /** Ureteç sutunu yalnizca Gelismis gorunumde. */
    void modeChanged() {
        javax.swing.table.TableColumnModel cm = table.getColumnModel();
        if (generatorColumn == null) generatorColumn = cm.getColumn(cm.getColumnCount() - 1);
        boolean shown = false;
        for (int i = 0; i < cm.getColumnCount(); i++) shown |= cm.getColumn(i) == generatorColumn;
        if (UiMode.advanced() && !shown) cm.addColumn(generatorColumn);
        if (!UiMode.advanced() && shown) cm.removeColumn(generatorColumn);
    }

    void setStore(ParameterStore s) {
        store = s;
        seenVersion = -1;
        rebuild();
    }

    /** Depo degistiyse tabloyu yeniler (duzenleme suruyorsa bekler). */
    void refreshIfChanged() {
        if (store != null && store.version() != seenVersion && !table.isEditing()) rebuild();
    }

    void updateButtons() {
        sendBtn.setEnabled(selectedMessage() != null && canSend.getAsBoolean());
    }

    private MessageDef selectedMessage() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= model.items.size()) return null;
        ParameterStore.MsgState st = model.items.get(r).state;
        return st == null ? null : st.def;
    }

    private void sendSelected() {
        MessageDef m = selectedMessage();
        if (m == null || store == null) return;
        try {
            sender.accept(store.encode(m));
            status.setText(" ");
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String msg) {
        status.setForeground(Theme.err());
        status.setText(msg);
    }

    private void rebuild() {
        int sel = table.getSelectedRow();
        String selKey = sel >= 0 && sel < model.items.size() ? key(model.items.get(sel)) : null;
        List<Item> items = new ArrayList<>();
        if (store != null && store.schema() != null) {
            seenVersion = store.version();
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            StructDef header = store.schema().header;
            List<Row> headerRows = Values.flatten(header, store.txHeader(), "başlık.", true);
            if (!headerRows.isEmpty() && !onlyReceived.isSelected()) {
                items.add(new Item(Kind.SECTION, null, null, "Başlık (tüm mesajlarda ortak)", "", "", false));
                Map<String, String> rx = displays(header, store.lastRxAny == null ? null : store.lastRxAny.header, "başlık.");
                for (Row r : headerRows) {
                    if (q.isEmpty() || r.path.toLowerCase(Locale.ROOT).contains(q)) {
                        items.add(new Item(Kind.FIELD, null, r, null, rx.get(r.path), "", false));
                    }
                }
            }
            for (ParameterStore.MsgState st : store.states()) {
                if (onlyReceived.isSelected() && st.rxCount == 0) continue;
                boolean nameHit = q.isEmpty() || st.def.name.toLowerCase(Locale.ROOT).contains(q)
                        || Long.toString(st.def.id).equals(q);
                List<Row> rows = Values.flatten(st.def.body, st.txBody, "", true);
                List<Row> hits = new ArrayList<>();
                for (Row r : rows) {
                    if (nameHit || r.path.toLowerCase(Locale.ROOT).contains(q)) hits.add(r);
                }
                if (hits.isEmpty() && !nameHit) continue;
                Decoded lr = st.lastRx;
                boolean warn = lr != null && (lr.error != null || !lr.warnings.isEmpty());
                String rxSum = st.rxCount == 0 ? "—" : st.rxCount + " kez · " + time(st.rxTime) + " · " + st.rxPeer;
                String txSum = st.txCount == 0 ? "—" : st.txCount + " kez · " + time(st.txTime);
                items.add(new Item(Kind.MESSAGE, st, null, (collapsed.contains(st.def.name) ? "▸ " : "▾ ")
                        + st.def.id + " - " + st.def.name, warn ? "⚠ " + rxSum : rxSum, txSum, warn));
                if (collapsed.contains(st.def.name)) continue;
                Map<String, String> rx = displays(st.def.body, lr == null ? null : lr.body, "");
                Map<String, String> tx = displays(st.def.body, st.lastTx == null ? null : st.lastTx.body, "");
                for (Row r : hits) items.add(new Item(Kind.FIELD, st, r, null, rx.get(r.path), tx.get(r.path), false));
            }
        }
        model.set(items);
        if (selKey != null) {
            for (int i = 0; i < items.size(); i++) {
                if (selKey.equals(key(items.get(i)))) {
                    table.getSelectionModel().setSelectionInterval(i, i);
                    break;
                }
            }
        }
        updateButtons();
    }

    private static String key(Item it) {
        String m = it.state == null ? "" : it.state.def.name;
        return it.kind + "|" + m + "|" + (it.row == null ? "" : it.row.path);
    }

    private String time(long t) { return timeFmt.format(new Date(t)); }

    /** Alinan/gonderilen bir cozumdeki degerleri yol -> metin haritasina cevirir. */
    private static Map<String, String> displays(StructDef s, Map<String, Object> values, String prefix) {
        if (values == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (Row r : Values.flatten(s, values, prefix, false)) {
            if (r.type != Row.Type.GROUP) out.put(r.path, r.display());
        }
        return out;
    }

    private final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Alan", "Gönderilecek", "Son alınan", "Son gönderilen", "Tip", "Üreteç"};
        List<Item> items = new ArrayList<>();

        void set(List<Item> list) {
            items = list;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return items.size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Object getValueAt(int r, int c) {
            Item it = items.get(r);
            if (it.kind != Kind.FIELD) {
                switch (c) {
                    case 0: return it.label;
                    case 2: return it.rx;
                    case 3: return it.tx;
                    case 4: return it.state == null ? "" : "id " + it.state.def.id;
                    default: return "";
                }
            }
            Row row = it.row;
            switch (c) {
                case 0: return "    " + FieldTable.indent(row.depth) + FieldTable.label(row);
                case 1: return row.display();
                case 2: return it.rx == null ? "" : it.rx;
                case 3: return it.tx == null ? "" : it.tx;
                case 4: return row.typeText();
                default: return row.type == Row.Type.VALUE ? store.generator(it.state == null ? null : it.state.def, row.path) : "";
            }
        }

        @Override public boolean isCellEditable(int r, int c) {
            Item it = items.get(r);
            if (c == 5) return it.kind == Kind.FIELD && it.row.type == Row.Type.VALUE && it.row.editable();
            return c == 1 && it.kind == Kind.FIELD && it.row.editable();
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Item it = items.get(r);
            if (c == 5) {
                try {
                    store.setGenerator(it.state == null ? null : it.state.def, it.row.path, v == null ? "" : v.toString());
                } catch (IllegalArgumentException e) {
                    showError("Üreteç: " + e.getMessage());
                    return;
                }
                status.setText(" ");
                fireTableRowsUpdated(r, r);
                return;
            }
            try {
                it.row.setText(v == null ? "" : v.toString());
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
                return;
            }
            status.setText(" ");
            store.changed();
            if (it.row.type == Row.Type.COUNT) rebuild();
            else fireTableRowsUpdated(r, r);
        }
    }

    private final class Renderer extends ValueCells.CellRenderer {
        private static final long serialVersionUID = 1L;
        private final Font mono = Theme.mono(12);

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc,
                                                               int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            Item it = model.items.get(r);
            int mc = t.convertColumnIndexToModel(c);
            chevron = mc == 1 && it.kind == Kind.FIELD && it.row.editable() && ValueCells.isEnum(it.row);
            setToolTipText(it.kind == Kind.FIELD && mc >= 1 && mc <= 3 ? ValueCells.hint(it.row) : null);
            setFont(it.kind == Kind.FIELD && c >= 1 && c <= 3 ? mono : t.getFont());
            if (it.kind != Kind.FIELD) {
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) {
                    setBackground(Theme.groupBg());
                    setForeground(it.warn && c == 2 ? Theme.warn() : t.getForeground());
                }
            } else if (!sel) {
                setBackground(t.getBackground());
                boolean muted = (c == 1 && !it.row.editable()) || c == 4;
                setForeground(c == 5 ? Theme.accent() : muted ? Theme.muted() : t.getForeground());
            }
            return this;
        }
    }
}
