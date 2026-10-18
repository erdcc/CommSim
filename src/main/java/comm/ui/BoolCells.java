package comm.ui;

import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;

/** Mantiksal (bool / bit) degerler icin onay kutusu hucreleri; deger "true"/"false" metni olarak gelir. */
final class BoolCells {
    private BoolCells() { }

    static final class Renderer extends JCheckBox implements TableCellRenderer {
        private static final long serialVersionUID = 1L;

        Renderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorderPainted(false);
            setOpaque(true);
        }

        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus,
                                                               int row, int col) {
            setSelected(value != null && Boolean.parseBoolean(value.toString().trim()));
            Color alt = UIManager.getColor("Table.alternateRowColor");
            setBackground(sel ? t.getSelectionBackground() : alt != null && row % 2 == 1 ? alt : t.getBackground());
            setForeground(sel ? t.getSelectionForeground() : t.getForeground());
            setEnabled(t.isCellEditable(row, col));
            return this;
        }
    }

    static TableCellEditor editor() {
        JCheckBox box = new JCheckBox();
        box.setHorizontalAlignment(SwingConstants.LEFT);
        return new DefaultCellEditor(box);
    }
}
