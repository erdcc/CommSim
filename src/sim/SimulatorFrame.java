package sim;

import sim.Model.FieldDef;
import sim.Model.Message;
import sim.Model.MessageDef;
import sim.Model.MessageListener;
import sim.Node.GateState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ana pencere. Haritadaki her platform bir TCP istemci gate'idir; radarlar tum platformlarca
 * tespit edilmis sayilir ve YAYIN_LISTESI mesajiyla sunucuya bildirilir.
 */
public class SimulatorFrame extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String PUBLISH_MESSAGE = "YAYIN_LISTESI";
    private static final int PUBLISH_DELAY_MS = 400;

    private final List<MessageDef> defs;
    private final Codec codec;
    private final MapPanel map;
    private final List<MessageListener> listeners = new ArrayList<>();

    // platform gate'leri (sadece EDT)
    private final Map<Node, Gate> gates = new LinkedHashMap<>();
    private boolean armed;
    private String connHost = "";
    private int connPort;
    private final Timer publishTimer;

    // baglanti
    private final JTextField host = new JTextField("127.0.0.1", 10);
    private final JTextField port = new JTextField("5555", 5);
    private final JToggleButton connect = new JToggleButton("Bağlan");

    // gonderim
    private final JComboBox<MessageDef> messageBox;
    private final JPanel form = new JPanel(new GridBagLayout());
    private final Map<String, JComponent> inputs = new LinkedHashMap<>();

    // gecmis
    private final DefaultTableModel historyModel = new DefaultTableModel(
            new String[] {"Zaman", "Yön", "Gate", "Mesaj", "İçerik"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable history = new JTable(historyModel);
    private final List<String> details = new ArrayList<>();
    private final JTextArea detail = new JTextArea();
    private final JLabel status = new JLabel(" ");

    /** Bir platformun TCP istemci baglantisi. */
    private final class Gate implements TcpTransport.Listener {
        final Node node;
        final TcpTransport tcp = new TcpTransport(this);

        Gate(Node node) { this.node = node; }

        private boolean current() { return gates.get(node) == this; }

        @Override public void onPacket(byte[] data, String from) {
            SwingUtilities.invokeLater(() -> {
                if (!current()) return;
                try {
                    record(codec.decode(data, data.length), data, data.length, true, node.name + " ← " + from);
                } catch (IllegalArgumentException e) {
                    setStatus(node.name + ": alınan paket çözülemedi: " + e.getMessage());
                }
            });
        }

        @Override public void onConnected() {
            SwingUtilities.invokeLater(() -> {
                if (!current()) return;
                node.gate = GateState.CONNECTED;
                map.refresh();
                setStatus(node.name + ": sunucuya bağlandı");
                publishTo(this, false);
            });
        }

        @Override public void onStatus(String message) {
            SwingUtilities.invokeLater(() -> { if (current()) setStatus(node.name + ": " + message); });
        }

        @Override public void onClosed() {
            SwingUtilities.invokeLater(() -> {
                if (!current()) return;
                node.gate = GateState.FAILED;
                map.refresh();
            });
        }
    }

    public SimulatorFrame(List<MessageDef> defs, MapPanel map) {
        super("Mesaj Simülatörü");
        this.defs = defs;
        this.codec = new Codec(defs);
        this.map = map;
        this.messageBox = new JComboBox<>(defs.toArray(new MessageDef[0]));
        this.publishTimer = new Timer(PUBLISH_DELAY_MS, e -> publishAll());
        publishTimer.setRepeats(false);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), map);
        main.setResizeWeight(0.4);
        main.setDividerLocation(560);
        add(main, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(status, BorderLayout.SOUTH);

        messageBox.addActionListener(e -> { rebuildForm(); fillFromNode(map.getSelected()); });
        connect.addActionListener(e -> toggleConnection());
        map.setSelectionListener(this::fillFromNode);
        map.setNodeListener(this::onNodeChanged);
        history.getSelectionModel().addListSelectionListener(e -> {
            int r = history.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0) detail.setText(details.get(r));
        });
        rebuildForm();
        setSize(1200, 720);
        setLocationRelativeTo(null);
    }

    public void addMessageListener(MessageListener l) { listeners.add(l); }

    // ---------- arayuz ----------

    private JComponent buildLeft() {
        JPanel conn = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        conn.setBorder(BorderFactory.createTitledBorder("Sunucu (TCP) · her platform bir gate"));
        conn.add(new JLabel("Adres")); conn.add(host);
        conn.add(new JLabel("Port"));  conn.add(port);
        conn.add(connect);

        JButton send = new JButton("Gönder");
        JButton reset = new JButton("Varsayılanlar");
        JButton fromNode = new JButton("Node'dan doldur");
        JButton publish = new JButton("Yayın listesini tüm platformlardan gönder");
        send.addActionListener(e -> send());
        reset.addActionListener(e -> rebuildForm());
        fromNode.addActionListener(e -> {
            if (map.getSelected() == null) setStatus("Haritada seçili node yok");
            else fillFromNode(map.getSelected());
        });
        publish.addActionListener(e -> {
            publishTimer.stop();
            publishAll();
        });
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        row1.add(fromNode);
        row1.add(reset);
        row1.add(send);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        row2.add(publish);
        JPanel buttons = new JPanel(new GridLayout(2, 1));
        buttons.add(row1);
        buttons.add(row2);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createTitledBorder("Mesaj Gönder (seçili platformun gate'i üzerinden)"));
        top.add(messageBox, BorderLayout.NORTH);
        top.add(new JScrollPane(form), BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);

        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.getColumnModel().getColumn(0).setPreferredWidth(90);
        history.getColumnModel().getColumn(1).setPreferredWidth(40);
        history.getColumnModel().getColumn(2).setPreferredWidth(140);
        history.getColumnModel().getColumn(3).setPreferredWidth(110);
        history.getColumnModel().getColumn(4).setPreferredWidth(300);
        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton clear = new JButton("Temizle");
        clear.addActionListener(e -> { historyModel.setRowCount(0); details.clear(); detail.setText(""); });

        JSplitPane histSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(history), new JScrollPane(detail));
        histSplit.setResizeWeight(0.65);
        JPanel hist = new JPanel(new BorderLayout(4, 4));
        hist.setBorder(BorderFactory.createTitledBorder("Mesaj Geçmişi (gönderilen / alınan)"));
        hist.add(histSplit, BorderLayout.CENTER);
        hist.add(clear, BorderLayout.SOUTH);

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, hist);
        center.setResizeWeight(0.55);

        JPanel left = new JPanel(new BorderLayout());
        left.add(conn, BorderLayout.NORTH);
        left.add(center, BorderLayout.CENTER);
        return left;
    }

    private void rebuildForm() {
        form.removeAll();
        inputs.clear();
        MessageDef def = (MessageDef) messageBox.getSelectedItem();
        if (def == null) return;
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        for (FieldDef f : def.fields()) {
            JComponent in = switch (f.type()) {
                case ENUM -> {
                    JComboBox<String> cb = new JComboBox<>(f.enumValues().toArray(new String[0]));
                    cb.setSelectedItem(f.defaultValue());
                    yield cb;
                }
                case BOOL -> {
                    JCheckBox cb = new JCheckBox();
                    cb.setSelected(Boolean.parseBoolean(f.defaultValue()));
                    yield cb;
                }
                case LIST -> {   // haritadan otomatik dolar; sadece onizleme
                    JTextField tf = new JTextField(18);
                    tf.setEditable(false);
                    yield tf;
                }
                default -> new JTextField(f.defaultValue(), 18);
            };
            inputs.put(f.name(), in);
            c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            form.add(new JLabel(f.name() + " (" + f.type().name().toLowerCase() + ")"), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            form.add(in, c);
            row++;
        }
        c.gridx = 0; c.gridy = row; c.weighty = 1; c.fill = GridBagConstraints.VERTICAL;
        form.add(new JLabel(), c); // alt bosluk
        refreshListPreview();
        form.revalidate();
        form.repaint();
    }

    /** Liste alanlarinin onizlemesi: haritadaki kayit sayisi ve adlari. */
    private void refreshListPreview() {
        MessageDef def = (MessageDef) messageBox.getSelectedItem();
        if (def == null) return;
        for (FieldDef f : def.fields()) {
            if (f.type() != Model.Type.LIST || !(inputs.get(f.name()) instanceof JTextField tf)) continue;
            List<Map<String, Object>> records = listValue(f);
            StringBuilder sb = new StringBuilder(records.size() + " kayıt (haritadaki " + f.bindKey() + ")");
            List<String> names = new ArrayList<>();
            for (Map<String, Object> r : records) names.add(String.valueOf(r.getOrDefault("name", r.get("nodeId"))));
            if (!names.isEmpty()) sb.append(": ").append(String.join(", ", names));
            tf.setText(sb.toString());
            tf.setCaretPosition(0);
        }
    }

    // ---------- node degisiklikleri / gate'ler ----------

    private void onNodeChanged(Node n, MapPanel.Change change) {
        boolean platform = n.type == Node.Type.PLATFORM;
        switch (change) {
            case ADDED -> {
                if (!platform) publishSoon();
                else if (armed) openGate(n);
            }
            case DELETED -> {
                if (platform) closeGate(n);
                else publishSoon();
            }
            case MOVED, EDITED -> { if (!platform) publishSoon(); }
        }
        refreshListPreview();
    }

    private void openGate(Node n) {
        Gate g = new Gate(n);
        gates.put(n, g);
        n.gate = GateState.CONNECTING;
        g.tcp.connect(connHost, connPort);
        map.refresh();
    }

    private void closeGate(Node n) {
        Gate g = gates.remove(n);
        if (g != null) g.tcp.close();
        n.gate = GateState.OFF;
    }

    private void toggleConnection() {
        if (!connect.isSelected()) {
            armed = false;
            publishTimer.stop();
            for (Node n : new ArrayList<>(gates.keySet())) closeGate(n);
            map.refresh();
            setConnected(false);
            setStatus("Tüm gate'ler kapatıldı");
            return;
        }
        try {
            connHost = host.getText().trim();
            if (connHost.isEmpty()) throw new IllegalArgumentException("adres boş");
            connPort = Integer.parseInt(port.getText().trim());
        } catch (IllegalArgumentException e) {
            connect.setSelected(false);
            setStatus("Geçersiz sunucu adresi/portu: " + e.getMessage());
            return;
        }
        armed = true;
        setConnected(true);
        int count = 0;
        for (Node n : map.nodes()) {
            if (n.type == Node.Type.PLATFORM) { openGate(n); count++; }
        }
        setStatus(count == 0
                ? "Hazır: eklenen her platform " + connHost + ":" + connPort + " adresine bağlanacak"
                : count + " platform gate'i " + connHost + ":" + connPort + " adresine bağlanıyor ...");
    }

    private void setConnected(boolean on) {
        connect.setSelected(on);
        connect.setText(on ? "Kes" : "Bağlan");
        host.setEnabled(!on);
        port.setEnabled(!on);
    }

    // ---------- yayin listesi ----------

    private void publishSoon() {
        if (armed) publishTimer.restart();
    }

    private void publishAll() {
        for (Gate g : gates.values()) {
            if (g.node.gate == GateState.CONNECTED) publishTo(g, true);
        }
    }

    /** Platformun YAYIN_LISTESI mesajini (tum radar kayitlariyla) kendi gate'inden gonderir. */
    private void publishTo(Gate g, boolean sendEmpty) {
        MessageDef def = defs.stream().filter(d -> d.name().equals(PUBLISH_MESSAGE)).findFirst().orElse(null);
        if (def == null) {
            setStatus(PUBLISH_MESSAGE + " mesajı messages.def içinde tanımlı değil");
            return;
        }
        Message m = new Message(def);
        boolean empty = true;
        for (FieldDef f : def.fields()) {
            if (f.type() == Model.Type.LIST) {
                List<Map<String, Object>> records = listValue(f);
                if (!records.isEmpty()) empty = false;
                m.values().put(f.name(), records);
            } else {
                m.values().put(f.name(), valueFor(f, g.node));
            }
        }
        if (empty && !sendEmpty) return;
        try {
            byte[] data = codec.encode(m);
            g.tcp.send(data);
            record(m, data, data.length, false, g.node.name + " → " + connHost + ":" + connPort);
            setStatus(g.node.name + ": " + PUBLISH_MESSAGE + " gönderildi (" + data.length + " bayt)");
        } catch (IllegalArgumentException | IOException e) {
            setStatus(g.node.name + ": " + PUBLISH_MESSAGE + " gönderilemedi: " + e.getMessage());
        }
    }

    /** Liste alanini haritadan uretir. 'radars' tum radar node'lari, 'platforms' tum platformlar. */
    private List<Map<String, Object>> listValue(FieldDef f) {
        Node.Type type = switch (f.bindKey()) {
            case "radars" -> Node.Type.RADAR;
            case "platforms" -> Node.Type.PLATFORM;
            default -> null;
        };
        List<Map<String, Object>> out = new ArrayList<>();
        if (type == null) return out;
        for (Node n : map.nodes()) {
            if (n.type != type) continue;
            Map<String, Object> rec = new LinkedHashMap<>();
            for (FieldDef sf : f.struct().fields()) rec.put(sf.name(), valueFor(sf, n));
            out.add(rec);
        }
        return out;
    }

    /** Alanin degerini node ozelliginden alir (tipe cevirerek); yoksa/uymazsa varsayilani kullanir. */
    private static Object valueFor(FieldDef f, Node n) {
        Object v = n.attributes().get(f.bindKey());
        try {
            if (v != null) {
                switch (f.type()) {
                    case INT -> { return ((Number) v).intValue(); }
                    case DOUBLE -> { return ((Number) v).doubleValue(); }
                    case BOOL -> { return (Boolean) v; }
                    case STRING -> { return String.valueOf(v); }
                    case ENUM -> { if (f.enumValues().contains(String.valueOf(v))) return String.valueOf(v); }
                    default -> { }
                }
            }
        } catch (ClassCastException ignored) {
            // uyumsuz tip: varsayilana dus
        }
        return Model.parseValue(f, f.defaultValue());
    }

    // ---------- gonderme / alma ----------

    private void send() {
        MessageDef def = (MessageDef) messageBox.getSelectedItem();
        if (def == null) return;
        Node src = map.getSelected();
        Gate gate = src == null ? null : gates.get(src);
        if (gate == null) {
            setStatus("Göndermek için haritada bir platform seçin (Bağlan açık olmalı)");
            return;
        }
        try {
            Message m = new Message(def);
            for (FieldDef f : def.fields()) {
                if (f.type() == Model.Type.LIST) {
                    m.values().put(f.name(), listValue(f));
                    continue;
                }
                JComponent in = inputs.get(f.name());
                String text = switch (in) {
                    case JComboBox<?> cb -> String.valueOf(cb.getSelectedItem());
                    case JCheckBox cb -> String.valueOf(cb.isSelected());
                    default -> ((JTextField) in).getText();
                };
                m.values().put(f.name(), Model.parseValue(f, text));
            }
            byte[] data = codec.encode(m);
            gate.tcp.send(data);
            record(m, data, data.length, false, src.name + " → " + connHost + ":" + connPort);
            setStatus(src.name + ": " + def.name() + " gönderildi (" + data.length + " bayt)");
        } catch (IllegalArgumentException | IOException e) {
            setStatus(src.name + ": gönderilemedi: " + e.getMessage());
        }
    }

    /** Secili node'un ozelliklerini, eslesen form alanlarina aktarir. */
    private void fillFromNode(Node o) {
        MessageDef def = (MessageDef) messageBox.getSelectedItem();
        if (o == null || def == null) return;
        Map<String, Object> attrs = o.attributes();
        int n = 0;
        for (FieldDef f : def.fields()) {
            Object v = attrs.get(f.bindKey());
            JComponent in = inputs.get(f.name());
            if (v == null || in == null) continue;
            String text = v instanceof Double d ? Model.fmt(d) : String.valueOf(v);
            switch (in) {
                case JComboBox<?> cb -> {
                    for (int i = 0; i < cb.getItemCount(); i++)
                        if (text.equals(cb.getItemAt(i))) { cb.setSelectedIndex(i); n++; break; }
                }
                case JTextField tf -> { tf.setText(text); n++; }
                default -> { }
            }
        }
        if (n > 0) setStatus(def.name() + ": " + n + " alan '" + o.name + "' node'undan dolduruldu");
    }

    private void record(Message m, byte[] data, int length, boolean incoming, String gate) {
        StringBuilder d = new StringBuilder(m.def().name() + "  (id " + m.def().id() + ", " + length + " bayt)\n"
                + "Gate: " + gate + "\n\n");
        m.values().forEach((k, v) -> d.append(String.format("%-12s %s%n", k, Model.describe(v))));
        d.append("\nHam veri:\n").append(Codec.hex(data, length));
        details.add(d.toString());
        historyModel.addRow(new Object[] {LocalTime.now().format(TIME), incoming ? "RX" : "TX",
                gate, m.def().name(), m.summary()});
        int last = historyModel.getRowCount() - 1;
        history.scrollRectToVisible(history.getCellRect(last, 0, true));
        for (MessageListener l : listeners) l.onMessage(m, incoming);
    }

    private void setStatus(String s) { status.setText(s); }
}
