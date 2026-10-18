package comm.ui;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.LogEntry;
import comm.modbus.ModbusMasterEndpoint;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.runtime.LiveGenerators;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.Values;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Canli grafik: sema mesaj alanlari (her alinan mesajda) ve Modbus register'lari (yarim saniyede bir)
 * zamana gore cizilir. Yalnizca EDT'den kullanilir.
 */
final class ChartPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int MAX_POINTS = 20_000;
    private static final long[] WINDOWS = {30_000, 60_000, 300_000, 900_000};
    private static final String[] WINDOW_LABELS = {"30 sn", "1 dk", "5 dk", "15 dk"};
    private static final Color[] PALETTE_LIGHT = {new Color(0x2F6FEB), new Color(0xE8590C), new Color(0x2B8A3E),
            new Color(0xAE3EC9), new Color(0xC92A2A), new Color(0x0B7285), new Color(0x5C940D), new Color(0x862E9C)};
    private static final Color[] PALETTE_DARK = {new Color(0x6EA8FE), new Color(0xFFA94D), new Color(0x69DB7C),
            new Color(0xDA77F2), new Color(0xFF8787), new Color(0x66D9E8), new Color(0xA9E34B), new Color(0xE599F7)};

    static final class Series {
        final Endpoint endpoint;
        /** Mesaj adi; Modbus kaynaginda null. */
        final String message;
        /** Mesajda alan yolu ("başlık." onekli baslik alanlari dahil) ya da Modbus hedefi ("hr:100"). */
        final String path;
        final int colorIndex;
        final ArrayDeque<double[]> points = new ArrayDeque<>();

        Series(Endpoint endpoint, String message, String path, int colorIndex) {
            this.endpoint = endpoint;
            this.message = message;
            this.path = path;
            this.colorIndex = colorIndex;
        }

        String label() {
            return endpoint.name() + " · " + (message == null ? path : message + "." + path);
        }

        void add(long t, double v) {
            points.addLast(new double[]{t, v});
            while (points.size() > MAX_POINTS) points.removeFirst();
        }
    }

    private final List<Series> series = new ArrayList<>();
    private final Canvas canvas = new Canvas();
    private final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
    private final JComboBox<String> windowBox = new JComboBox<>(WINDOW_LABELS);
    private final JToggleButton pause = new JToggleButton("Duraklat");
    private final Supplier<List<Endpoint>> endpoints;
    private int nextColor;
    private long frozenAt;

    ChartPanel(Supplier<List<Endpoint>> endpoints) {
        super(new BorderLayout(0, 4));
        this.endpoints = endpoints;
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton add = UiKit.button("Seri ekle…", Icons.ADD);
        JButton clear = UiKit.button("Temizle", Icons.CLEAR);
        bar.add(add);
        bar.add(UiKit.muted("Pencere"));
        windowBox.setSelectedIndex(1);
        bar.add(windowBox);
        bar.add(pause);
        bar.add(clear);
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(legend, BorderLayout.SOUTH);
        add.addActionListener(e -> addDialog(null, null));
        clear.addActionListener(e -> {
            for (Series s : series) s.points.clear();
            canvas.repaint();
        });
        windowBox.addActionListener(e -> canvas.repaint());
        pause.addActionListener(e -> {
            frozenAt = pause.isSelected() ? System.currentTimeMillis() : 0;
            canvas.repaint();
        });
        rebuildLegend();
    }

    // ---- veri ----

    /** Alinan mesajlardan serileri besler (drain'den). */
    void offer(LogEntry e) {
        if (series.isEmpty() || e.dir != Direction.RX || e.decoded == null || e.decoded.message == null) return;
        Map<String, String> values = null;
        for (Series s : series) {
            if (s.message == null || s.endpoint != e.endpoint || !s.message.equals(e.decoded.message.name)) continue;
            if (values == null) values = texts(e.decoded);
            Double v = number(values.get(s.path));
            if (v != null) s.add(e.time, v);
        }
    }

    /** Modbus serilerini ornekler ve yeniden cizer (yarim saniyede bir). */
    void tick() {
        long now = System.currentTimeMillis();
        for (Series s : series) {
            if (s.message != null) continue;
            ModbusTables t = s.endpoint instanceof ModbusSlaveEndpoint ? ((ModbusSlaveEndpoint) s.endpoint).tables()
                    : s.endpoint instanceof ModbusMasterEndpoint ? ((ModbusMasterEndpoint) s.endpoint).tables() : null;
            if (t == null || s.endpoint.state() != Endpoint.State.RUNNING) continue;
            LiveGenerators.ModbusTarget mt = LiveGenerators.modbusTarget(s.path);
            s.add(now, t.get(mt.table, mt.address));
        }
        if (!pause.isSelected() && isShowing()) canvas.repaint();
    }

    private static Map<String, String> texts(Decoded d) {
        Map<String, String> m = Values.receivedTexts(d.message.body, d.body, "");
        for (Map.Entry<String, Object> h : d.header.entrySet()) m.put("başlık." + h.getKey(), String.valueOf(h.getValue()));
        return m;
    }

    private static Double number(String s) {
        if (s == null) return null;
        if (s.equals("true")) return 1.0;
        if (s.equals("false")) return 0.0;
        try {
            return s.startsWith("0x") ? (double) Long.parseLong(s.substring(2), 16) : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- seri ekleme ----

    /** Seri ekleme penceresi; baglanti/mesaj verilirse onceden secili gelir. */
    void addDialog(Endpoint preset, String presetMessage) {
        List<Endpoint> eps = new ArrayList<>();
        for (Endpoint ep : endpoints.get()) {
            if (ep.schema() != null || ep instanceof ModbusSlaveEndpoint || ep instanceof ModbusMasterEndpoint) eps.add(ep);
        }
        if (eps.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Grafiğe eklenebilecek bağlantı yok (şemalı bağlantı ya da Modbus gerekir).");
            return;
        }
        JComboBox<Endpoint> epBox = new JComboBox<>(eps.toArray(new Endpoint[0]));
        JComboBox<String> msgBox = new JComboBox<>();
        JComboBox<String> fieldBox = new JComboBox<>();
        JTextField modbusField = new JTextField("hr:0", 10);
        JLabel msgLabel = new JLabel("Mesaj");
        JLabel fieldLabel = new JLabel("Alan");
        JLabel mbLabel = new JLabel("Register (hr:100, ir:5, coil:3)");
        Runnable fillFields = () -> {
            fieldBox.removeAllItems();
            Endpoint ep = (Endpoint) epBox.getSelectedItem();
            String msg = (String) msgBox.getSelectedItem();
            Schema s = ep == null ? null : ep.schema();
            MessageDef m = s == null || msg == null ? null : s.byName(msg);
            if (m == null) return;
            for (Values.Row r : Values.flatten(s.header, Values.defaults(s.header), "başlık.", false)) {
                if (numeric(r)) fieldBox.addItem(r.path);
            }
            for (Values.Row r : Values.flatten(m.body, Values.complete(m.body, new java.util.LinkedHashMap<>()), "", false)) {
                if (numeric(r)) fieldBox.addItem(r.path);
            }
        };
        Runnable fillMessages = () -> {
            Endpoint ep = (Endpoint) epBox.getSelectedItem();
            boolean modbus = ep instanceof ModbusSlaveEndpoint || ep instanceof ModbusMasterEndpoint;
            DefaultComboBoxModel<String> mm = new DefaultComboBoxModel<>();
            if (!modbus && ep != null && ep.schema() != null) for (MessageDef m : ep.schema().messages()) mm.addElement(m.name);
            msgBox.setModel(mm);
            for (JComponent c : new JComponent[]{msgLabel, msgBox, fieldLabel, fieldBox}) c.setVisible(!modbus);
            mbLabel.setVisible(modbus);
            modbusField.setVisible(modbus);
            fillFields.run();
        };
        epBox.addActionListener(e -> fillMessages.run());
        msgBox.addActionListener(e -> fillFields.run());
        if (preset != null && eps.contains(preset)) epBox.setSelectedItem(preset);
        fillMessages.run();
        if (presetMessage != null) msgBox.setSelectedItem(presetMessage);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;
        Object[][] rows = {{new JLabel("Bağlantı"), epBox}, {msgLabel, msgBox}, {fieldLabel, fieldBox}, {mbLabel, modbusField}};
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            form.add((JComponent) rows[i][0], c);
            c.gridx = 1;
            form.add((JComponent) rows[i][1], c);
        }
        if (JOptionPane.showConfirmDialog(this, form, "Grafiğe seri ekle", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Endpoint ep = (Endpoint) epBox.getSelectedItem();
        try {
            if (ep instanceof ModbusSlaveEndpoint || ep instanceof ModbusMasterEndpoint) {
                LiveGenerators.modbusTarget(modbusField.getText());
                addSeries(ep, null, modbusField.getText().trim());
            } else {
                if (fieldBox.getSelectedItem() == null) throw new IllegalArgumentException("sayısal alan seçin");
                addSeries(ep, (String) msgBox.getSelectedItem(), (String) fieldBox.getSelectedItem());
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Seri eklenemedi", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static boolean numeric(Values.Row r) {
        if (r.type != Values.Row.Type.VALUE && r.type != Values.Row.Type.AUTO) return false;
        switch (r.field.kind) {
            case INT: case FLOAT: case BOOL: return true;
            default: return false;
        }
    }

    void addSeries(Endpoint ep, String message, String path) {
        for (Series s : series) {
            if (s.endpoint == ep && java.util.Objects.equals(s.message, message) && s.path.equals(path)) return;
        }
        series.add(new Series(ep, message, path, nextColor++));
        rebuildLegend();
        canvas.repaint();
    }

    /** Silinen baglantilarin serilerini kaldirir. */
    void retain(List<Endpoint> eps) {
        if (series.removeIf(s -> !eps.contains(s.endpoint))) rebuildLegend();
    }

    private Color color(Series s) {
        Color[] p = Theme.dark() ? PALETTE_DARK : PALETTE_LIGHT;
        return p[s.colorIndex % p.length];
    }

    private void rebuildLegend() {
        legend.removeAll();
        if (series.isEmpty()) {
            JLabel l = UiKit.muted("Seri yok. \"Seri ekle…\" ile bir mesaj alanı ya da Modbus register'ı seçin; trafik logunda ve Parametreler'de sağ tık da kullanılabilir.");
            legend.add(l);
        }
        for (Series s : series) {
            JLabel dot = new JLabel("●");
            dot.setForeground(color(s));
            JLabel name = new JLabel(s.label());
            JButton remove = UiKit.tool(null, Icons.CLEAR, "Seriyi kaldır");
            remove.addActionListener(e -> {
                series.remove(s);
                rebuildLegend();
                canvas.repaint();
            });
            JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            item.add(dot);
            item.add(name);
            item.add(remove);
            legend.add(item);
        }
        legend.revalidate();
        legend.repaint();
    }

    // ---- cizim ----

    private final class Canvas extends JComponent {
        private static final long serialVersionUID = 1L;
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g.setColor(Theme.surface());
            g.fillRect(0, 0, w, h);
            Font small = UIManager.getFont("Label.font").deriveFont(11f);
            g.setFont(small);
            FontMetrics fm = g.getFontMetrics();
            int left = 60;
            int right = 16;
            int top = 12;
            int bottom = 26;
            int pw = w - left - right;
            int ph = h - top - bottom;
            if (pw < 50 || ph < 30) return;

            long end = frozenAt > 0 ? frozenAt : System.currentTimeMillis();
            long span = WINDOWS[windowBox.getSelectedIndex()];
            long start = end - span;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Series s : series) {
                for (double[] p : s.points) {
                    if (p[0] < start || p[0] > end) continue;
                    min = Math.min(min, p[1]);
                    max = Math.max(max, p[1]);
                }
            }
            if (min == Double.POSITIVE_INFINITY) {
                min = 0;
                max = 1;
            } else if (max - min < 1e-9) {
                min -= 1;
                max += 1;
            } else {
                double pad = (max - min) * 0.08;
                min -= pad;
                max += pad;
            }

            // izgara
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i <= 4; i++) {
                int y = top + ph * i / 4;
                g.setColor(Theme.separator());
                g.drawLine(left, y, left + pw, y);
                double v = max - (max - min) * i / 4;
                String label = format(v);
                g.setColor(Theme.muted());
                g.drawString(label, left - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            }
            for (int i = 0; i <= 5; i++) {
                int x = left + pw * i / 5;
                String label = fmt.format(new Date(start + span * i / 5));
                g.setColor(Theme.muted());
                g.drawString(label, Math.min(Math.max(x - fm.stringWidth(label) / 2, 0), w - fm.stringWidth(label)), top + ph + 18);
            }
            if (series.isEmpty()) {
                String msg = "Grafikte gösterilecek seri yok";
                g.setColor(Theme.muted());
                g.drawString(msg, left + (pw - fm.stringWidth(msg)) / 2, top + ph / 2);
                g.dispose();
                return;
            }
            // seriler
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.clipRect(left, top - 2, pw + 1, ph + 4);
            for (Series s : series) {
                Path2D path = new Path2D.Double();
                boolean first = true;
                double[] last = null;
                for (double[] p : s.points) {
                    if (p[0] < start - span / 10 || p[0] > end) continue;
                    double x = left + (p[0] - start) / span * pw;
                    double y = top + (max - p[1]) / (max - min) * ph;
                    if (first) path.moveTo(x, y);
                    else path.lineTo(x, y);
                    first = false;
                    last = p;
                }
                g.setColor(color(s));
                g.draw(path);
                if (last != null) {
                    double x = left + (last[0] - start) / span * pw;
                    double y = top + (max - last[1]) / (max - min) * ph;
                    g.fillOval((int) x - 3, (int) y - 3, 6, 6);
                    String v = format(last[1]);
                    g.drawString(v, (int) Math.min(x + 6, left + pw - fm.stringWidth(v)), (int) y - 6);
                }
            }
            g.dispose();
        }

        private String format(double v) {
            if (Math.abs(v) >= 1000 || v == Math.rint(v)) return String.format(java.util.Locale.ROOT, "%.0f", v);
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }
}
