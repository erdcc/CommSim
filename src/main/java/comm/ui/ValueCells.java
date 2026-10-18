package comm.ui;

import comm.schema.FieldDef;
import comm.schema.FieldDef.Kind;
import comm.schema.FieldDef.Size;
import comm.schema.Values;
import comm.schema.Values.Row;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.function.Consumer;

/** Deger hucreleri icin tipe gore duzenleyici ve gosterim: enum acilir liste, sayilar dogrulamali metin. */
final class ValueCells {
    private ValueCells() { }

    static boolean isEnum(Row r) {
        return r != null && r.type == Row.Type.VALUE && r.field.kind == Kind.ENUM;
    }

    /** Duzenlenebilir deger satiri icin uygun editor; null ise tablonun varsayilani kullanilir. */
    static TableCellEditor editor(Row r, Consumer<String> onError) {
        if (r == null || !r.editable()) return null;
        if (isEnum(r)) return enumEditor(r);
        if (r.type == Row.Type.VALUE || r.type == Row.Type.COUNT) return textEditor(r, onError);
        return null;
    }

    private static TableCellEditor enumEditor(Row r) {
        JComboBox<String> combo = new JComboBox<>();
        for (FieldDef.EnumItem e : r.field.enums) combo.addItem(e.name);
        combo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
        combo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                FieldDef.EnumItem e = v == null ? null : r.field.enumByName(v.toString());
                if (e != null && i >= 0) setText(e.name + "   (" + e.value + ")");
                return this;
            }
        });
        DefaultCellEditor ed = new DefaultCellEditor(combo) {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int row, int col) {
                String cur = v == null ? "" : v.toString();
                // tanimsiz sayisal deger (alinan mesajdan) listede yoksa gecici olarak eklenir
                if (r.field.enumByName(cur) == null && !cur.isEmpty()) {
                    boolean found = false;
                    for (int i = 0; i < combo.getItemCount(); i++) found |= cur.equals(combo.getItemAt(i));
                    if (!found) combo.addItem(cur);
                }
                Component c = super.getTableCellEditorComponent(t, v, sel, row, col);
                SwingUtilities.invokeLater(() -> {
                    if (combo.isShowing()) combo.showPopup();
                });
                return c;
            }
        };
        ed.setClickCountToStart(1);
        return ed;
    }

    private static TableCellEditor textEditor(Row r, Consumer<String> onError) {
        JTextField field = new JTextField();
        field.setFont(Theme.mono(12));
        field.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        DefaultCellEditor ed = new DefaultCellEditor(field) {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int row, int col) {
                Component c = super.getTableCellEditorComponent(t, v, sel, row, col);
                // "3 öğe" -> "3": adet hucresinde yalniz sayi duzenlenir
                if (r.type == Row.Type.COUNT && v != null) field.setText(v.toString().replace(" öğe", ""));
                field.putClientProperty("JComponent.outline", null);
                field.setToolTipText(hint(r));
                SwingUtilities.invokeLater(field::selectAll);
                return c;
            }

            @Override public boolean stopCellEditing() {
                String err = validate(r, field.getText());
                if (err != null) {
                    field.putClientProperty("JComponent.outline", "error");
                    field.setToolTipText(err);
                    if (onError != null) onError.accept(err);
                    return false;
                }
                field.putClientProperty("JComponent.outline", null);
                return super.stopCellEditing();
            }
        };
        ed.setClickCountToStart(2);
        return ed;
    }

    /** Hata metni; gecerliyse null. */
    private static String validate(Row r, String text) {
        try {
            if (r.type == Row.Type.COUNT) {
                int n = Integer.parseInt(text.trim());
                if (n < 0 || n > 100_000) return "'" + r.field.name + "': adet 0 … 100000 olmalı";
            } else {
                Values.parse(r.field, text);
            }
            return null;
        } catch (NumberFormatException e) {
            return "'" + r.field.name + "': adet tam sayı olmalı";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Deger hucresi ipucu: tip ve gecerli aralik / secenekler. */
    static String hint(Row r) {
        if (r == null) return null;
        if (r.type == Row.Type.COUNT) return "Eleman sayısı (0 … 100000)";
        if (r.type != Row.Type.VALUE) return null;
        FieldDef f = r.field;
        switch (f.kind) {
            case INT: {
                int bits = f.bitWidth > 0 ? f.bitWidth : 8 * f.size;
                String range;
                if (bits >= 64) range = f.signed ? "-9223372036854775808 … 9223372036854775807" : "0 … 18446744073709551615";
                else if (f.signed) range = -(1L << (bits - 1)) + " … " + ((1L << (bits - 1)) - 1);
                else range = "0 … " + ((1L << bits) - 1);
                return "Tam sayı, " + range + "  (0x ile hex de yazılabilir)";
            }
            case FLOAT: return "Ondalık sayı (" + (f.size == 4 ? "float32" : "float64") + ")";
            case BOOL: return "Doğru / yanlış";
            case ENUM: {
                StringBuilder sb = new StringBuilder("Seçenekler: ");
                for (int i = 0; i < f.enums.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(f.enums.get(i).name).append('=').append(f.enums.get(i).value);
                }
                return sb.toString();
            }
            case STRING: case CSTRING:
                return f.length != null && f.length.mode == Size.Mode.FIXED ? "Metin, en fazla " + f.length.n + " byte" : "Metin";
            case BYTES:
                return "Hex byte'lar, ör. 01 A0 FF" + (f.length.mode == Size.Mode.FIXED ? " (en fazla " + f.length.n + " byte)" : "");
            default: return null;
        }
    }

    /** Enum hucrelerinde sagda acilir liste oku cizen hucre gostericisi. */
    static class CellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        /** Bu cizimde ok gosterilsin mi (getTableCellRendererComponent icinde ayarlanir). */
        protected boolean chevron;

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!chevron) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getForeground());
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x = getWidth() - 14;
            double y = getHeight() / 2.0 - 1.5;
            Path2D p = new Path2D.Double();
            p.moveTo(x, y);
            p.lineTo(x + 4, y + 4);
            p.lineTo(x + 8, y);
            g2.draw(p);
            g2.dispose();
        }
    }
}
