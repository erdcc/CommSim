package comm.ui;

import comm.schema.Values.Row;

import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Duzlestirilmis alan satirlarini (Alan, Deger, Tip) gosteren ve degeri duzenleten tablo. */
final class FieldTable extends JTable {
    private static final long serialVersionUID = 1L;

    private final Model model;
    private final Consumer<String> onError;

    /** Istege bagli "Ureteç" sutunu icin erisim. */
    interface Generators {
        String get(Row row);

        /** @throws IllegalArgumentException ifade gecersizse */
        void set(Row row, String text);
    }

    /** Ureteç sutununu acar (null kapatir). */
    void setGenerators(Generators g) {
        model.gens = g;
        model.fireTableStructureChanged();
        getColumnModel().getColumn(0).setPreferredWidth(200);
        getColumnModel().getColumn(1).setPreferredWidth(170);
        getColumnModel().getColumn(2).setPreferredWidth(160);
        if (g != null) {
            getColumnModel().getColumn(3).setPreferredWidth(170);
            getColumnModel().getColumn(3).setHeaderValue("Üreteç");
        }
    }

    /**
     * @param rows    satirlari (yeniden) ureten kaynak
     * @param onEdit  basarili duzenlemeden sonra cagrilir
     * @param onError gecersiz girdide hata metniyle cagrilir
     */
    FieldTable(Supplier<List<Row>> rows, Runnable onEdit, Consumer<String> onError) {
        this.onError = onError;
        model = new Model(rows, onEdit, onError);
        setModel(model);
        setRowHeight(Math.max(getRowHeight(), 20));
        setSurrendersFocusOnKeystroke(true);
        putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        getColumnModel().getColumn(0).setPreferredWidth(220);
        getColumnModel().getColumn(1).setPreferredWidth(220);
        getColumnModel().getColumn(2).setPreferredWidth(160);
        Font mono = Theme.mono(12);
        setDefaultRenderer(Object.class, new ValueCells.CellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc,
                                                                   int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                Row row = model.row(r);
                int mc = t.convertColumnIndexToModel(c);
                chevron = mc == 1 && row.editable() && ValueCells.isEnum(row);
                setToolTipText(mc == 1 ? ValueCells.hint(row) : null);
                setFont(c == 1 || c == 3 ? mono : t.getFont());
                if (row.type == Row.Type.GROUP) setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) setForeground(c == 3 ? Theme.accent() : row.editable() || c == 0 ? t.getForeground() : Theme.muted());
                if (c == 3 && !sel && row.type == Row.Type.VALUE && row.editable() && "".equals(v)) {
                    setForeground(Theme.tint(Theme.muted(), 120));
                    setText("—");
                }
                if (c == 0 && row.type == Row.Type.AUTO) setFont(getFont().deriveFont(Font.ITALIC));
                return this;
            }
        });
    }

    private final BoolCells.Renderer boolRenderer = new BoolCells.Renderer();
    private final javax.swing.table.TableCellEditor boolEditor = BoolCells.editor();

    private boolean isBoolValue(int row, int col) {
        if (col != 1 || row < 0 || row >= model.getRowCount()) return false;
        Row r = model.row(row);
        return r.type == Row.Type.VALUE && r.field.kind == comm.schema.FieldDef.Kind.BOOL;
    }

    /** bool ve bit bayraklari onay kutusu olarak gosterilir ve duzenlenir. */
    @Override public javax.swing.table.TableCellRenderer getCellRenderer(int row, int col) {
        return isBoolValue(row, col) ? boolRenderer : super.getCellRenderer(row, col);
    }

    @Override public javax.swing.table.TableCellEditor getCellEditor(int row, int col) {
        if (isBoolValue(row, col)) return boolEditor;
        if (col == 1 && row >= 0 && row < model.getRowCount()) {
            javax.swing.table.TableCellEditor ed = ValueCells.editor(model.row(row), onError);
            if (ed != null) return ed;
        }
        return super.getCellEditor(row, col);
    }

    /** Satirlari kaynaktan yeniden uretir (duzenleme suruyorsa dokunmaz). */
    void reload() {
        if (isEditing()) return;
        model.reload();
    }

    private static final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final Supplier<List<Row>> source;
        private final Runnable onEdit;
        private final Consumer<String> onError;
        private List<Row> rows = Collections.emptyList();
        Generators gens;

        Model(Supplier<List<Row>> source, Runnable onEdit, Consumer<String> onError) {
            this.source = source;
            this.onEdit = onEdit;
            this.onError = onError;
        }

        void reload() {
            List<Row> r = source.get();
            rows = r == null ? Collections.<Row>emptyList() : new ArrayList<>(r);
            fireTableDataChanged();
        }

        Row row(int i) { return rows.get(i); }

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return gens == null ? 3 : 4; }

        @Override public String getColumnName(int c) {
            return c == 0 ? "Alan" : c == 1 ? "Değer" : c == 2 ? "Tip" : "Üreteç";
        }

        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            switch (c) {
                case 0: return indent(row.depth) + label(row);
                case 1: return row.display();
                case 2: return row.typeText();
                default: return row.type == Row.Type.VALUE && gens != null ? gens.get(row) : "";
            }
        }

        @Override public boolean isCellEditable(int r, int c) {
            Row row = rows.get(r);
            if (c == 3) return gens != null && row.type == Row.Type.VALUE && row.editable();
            return c == 1 && row.editable();
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Row row = rows.get(r);
            if (c == 3) {
                try {
                    gens.set(row, v == null ? "" : v.toString());
                } catch (IllegalArgumentException e) {
                    onError.accept("Üreteç: " + e.getMessage());
                    return;
                }
                fireTableRowsUpdated(r, r);
                onEdit.run();
                return;
            }
            try {
                row.setText(v == null ? "" : v.toString());
            } catch (IllegalArgumentException e) {
                onError.accept(e.getMessage());
                return;
            }
            if (row.type == Row.Type.COUNT) reload();
            else fireTableRowsUpdated(r, r);
            onEdit.run();
        }
    }

    static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("    ");
        return sb.toString();
    }

    static String label(Row row) {
        // ust seviyede tam yol (baslik alanlari "başlık.sync" gorunsun); icerde son parca: "tags[1].level" -> "level"
        String last = row.depth == 0 ? row.path : row.path.substring(row.path.lastIndexOf('.') + 1);
        return row.type == Row.Type.COUNT ? last + " (adet)" : last;
    }
}
