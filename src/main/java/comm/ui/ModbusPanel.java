package comm.ui;

import comm.core.Endpoint;
import comm.core.ModbusPoll;
import comm.modbus.Modbus;
import comm.modbus.ModbusMasterEndpoint;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.modbus.ModbusTables.Table;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Modbus ucu icin: veri tablosu (slave'de dogrudan, master'da yazma istegiyle duzenlenir),
 * master icin istek olusturucu ve periyodik okuma listesi.
 */
final class ModbusPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int PAGE = 100;
    private static final String[] FORMATS = {"İşaretsiz", "İşaretli", "Hex", "İkili"};

    private final JComboBox<Table> tableBox = new JComboBox<>(Table.values());
    private final JSpinner startSpinner = new JSpinner(new SpinnerNumberModel(0, 0, ModbusTables.SIZE - 1, PAGE));
    private final JComboBox<String> formatBox = new JComboBox<>(FORMATS);
    private final JButton clearBtn = UiKit.button("Tabloyu sıfırla", Icons.CLEAR);
    private final JLabel info = new JLabel(" ");
    private final ValueModel values = new ValueModel();
    private final JTable valueTable = new JTable(values);

    // master
    private final JPanel masterPanel = new JPanel(new BorderLayout(0, 4));
    private final JComboBox<String> fcBox = new JComboBox<>();
    private final JTextField addrField = new JTextField("0", 6);
    private final JTextField countField = new JTextField("10", 5);
    private final JTextField writeAddrField = new JTextField("0", 6);
    private final JTextField valuesField = new JTextField(18);
    private final JLabel status = new JLabel(" ");
    private final PollModel polls = new PollModel();
    private final JTable pollTable = new JTable(polls);
    private final JSplitPane split;
    private final LiveGeneratorPanel livePanel = new LiveGeneratorPanel("Canlı değerler", "hr:100, coil:3", "hr:0");

    private Endpoint endpoint;
    private long seenVersion = -1;

    ModbusPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("Tablo:"));
        tableBox.setSelectedItem(Table.HOLDING);
        bar.add(tableBox);
        bar.add(new JLabel("Başlangıç:"));
        startSpinner.setEditor(new JSpinner.NumberEditor(startSpinner, "#"));
        bar.add(startSpinner);
        bar.add(new JLabel("Biçim:"));
        bar.add(formatBox);
        bar.add(clearBtn);
        bar.add(info);
        add(bar, BorderLayout.NORTH);

        valueTable.setRowHeight(Math.max(valueTable.getRowHeight(), 20));
        valueTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        Font mono = Theme.mono(12);
        valueTable.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f,
                                                                            int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col);
                setFont(mono);
                return this;
            }
        });

        buildMaster();
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(valueTable), masterPanel);
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);

        tableBox.addActionListener(e -> values.reload());
        startSpinner.addChangeListener(e -> values.reload());
        formatBox.addActionListener(e -> values.reload());
        clearBtn.addActionListener(e -> {
            ModbusTables t = tables();
            if (t != null && JOptionPane.showConfirmDialog(this, "Dört tablodaki tüm değerler sıfırlansın mı?",
                    "Tabloyu sıfırla", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                t.clear();
            }
        });
        setEndpoint(null);
    }

    private void buildMaster() {
        for (int fc : Modbus.FUNCTIONS) fcBox.addItem(String.format("FC%02d %s", fc, Modbus.functionName(fc)));
        fcBox.setSelectedIndex(2);
        // istek: uc kompakt satir
        JPanel req = new JPanel();
        req.setLayout(new javax.swing.BoxLayout(req, javax.swing.BoxLayout.Y_AXIS));
        req.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 4));
        JLabel reqTitle = UiKit.style(new JLabel("İstek"), "semibold");
        JPanel r0 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        r0.add(reqTitle);
        r0.add(fcBox);
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        r1.add(UiKit.muted("Adres"));
        r1.add(addrField);
        r1.add(UiKit.muted("Adet"));
        r1.add(countField);
        r1.add(UiKit.muted("Yazma adresi"));
        r1.add(writeAddrField);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        r2.add(UiKit.muted("Değer(ler)"));
        UiKit.placeholder(valuesField, "1, 2, 0x10  (coil: 0/1)");
        r2.add(valuesField);
        JButton send = UiKit.primary("İsteği gönder", Icons.SEND);
        r2.add(send);
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        r3.add(status);
        for (JPanel r : new JPanel[]{r0, r1, r2, r3}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            req.add(r);
        }
        valuesField.setToolTipText("Virgülle ayrılmış: 1, 2, 0x10, -5  (coil için 0/1)");

        JPanel pollBox = new JPanel(new BorderLayout(0, 4));
        pollBox.setBorder(BorderFactory.createEmptyBorder(4, 10, 0, 4));
        pollBox.add(UiKit.style(new JLabel("Periyodik okuma"), "semibold"), BorderLayout.NORTH);
        pollTable.setPreferredScrollableViewportSize(new java.awt.Dimension(320, 72));
        JComboBox<String> fcEditor = new JComboBox<>();
        for (int fc = 1; fc <= 4; fc++) fcEditor.addItem(fcLabel(fc));
        pollTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(fcEditor));
        pollTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        pollTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        pollBox.add(new JScrollPane(pollTable), BorderLayout.CENTER);
        JPanel pollBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton add = UiKit.button("Ekle", Icons.ADD);
        JButton del = UiKit.button("Sil", Icons.DELETE);
        pollBtns.add(add);
        pollBtns.add(del);
        pollBox.add(pollBtns, BorderLayout.SOUTH);

        masterPanel.add(req, BorderLayout.NORTH);
        masterPanel.add(pollBox, BorderLayout.CENTER);

        fcBox.addActionListener(e -> updateRequestFields());
        send.addActionListener(e -> sendRequest());
        add.addActionListener(e -> {
            if (endpoint == null) return;
            ModbusPoll p = new ModbusPoll();
            Table t = (Table) tableBox.getSelectedItem();
            p.function = t.readFunction;
            p.address = (Integer) startSpinner.getValue();
            endpoint.config().modbusPolls.add(p);
            polls.fireTableDataChanged();
        });
        del.addActionListener(e -> {
            int r = pollTable.getSelectedRow();
            if (endpoint != null && r >= 0) {
                endpoint.config().modbusPolls.remove(r);
                polls.fireTableDataChanged();
            }
        });
        updateRequestFields();
    }

    void setEndpoint(Endpoint ep) {
        endpoint = ep;
        boolean master = ep instanceof ModbusMasterEndpoint;
        masterPanel.setVisible(master);
        boolean slave = ep instanceof ModbusSlaveEndpoint;
        placeRight();
        livePanel.setEndpoint(slave ? ep : null);
        clearBtn.setVisible(ep instanceof ModbusSlaveEndpoint);
        if (master) split.setDividerLocation(0.45);
        valueTable.setToolTipText(master ? "Coil ve holding register değerini değiştirmek yazma isteği gönderir"
                : "Değerler doğrudan slave tablosuna yazılır");
        status.setText(" ");
        seenVersion = -1;
        values.reload();
        polls.fireTableDataChanged();
        refresh();
    }

    /** Sag taraf: master'da istek/periyodik okuma; slave'de canli degerler (yalnizca Gelismis). */
    private void placeRight() {
        boolean slave = endpoint instanceof ModbusSlaveEndpoint;
        java.awt.Component want = slave ? (UiMode.advanced() ? livePanel : null) : masterPanel;
        if (split.getRightComponent() == want) return;
        int div = split.getDividerLocation();
        split.setRightComponent(want);
        if (want != null && div > 0 && div < split.getWidth() - 50) split.setDividerLocation(div);
        else if (want != null) split.setDividerLocation(0.45);
        if (want instanceof javax.swing.JComponent) Theme.sync((javax.swing.JComponent) want);
    }

    void modeChanged() { placeRight(); }

    private ModbusTables tables() {
        if (endpoint instanceof ModbusSlaveEndpoint) return ((ModbusSlaveEndpoint) endpoint).tables();
        if (endpoint instanceof ModbusMasterEndpoint) return ((ModbusMasterEndpoint) endpoint).tables();
        return null;
    }

    /** Zamanlayicidan cagrilir: tablo degistiyse yeniler, sayaclari gunceller. */
    void refresh() {
        ModbusTables t = tables();
        if (t == null) {
            info.setText(" ");
            return;
        }
        if (endpoint instanceof ModbusSlaveEndpoint) livePanel.refresh();
        if (t.version() != seenVersion && !valueTable.isEditing()) {
            seenVersion = t.version();
            values.fireTableRowsUpdated(0, Math.max(0, values.getRowCount() - 1));
        }
        if (endpoint instanceof ModbusMasterEndpoint) {
            ModbusMasterEndpoint m = (ModbusMasterEndpoint) endpoint;
            long us = m.lastLatencyMicros();
            info.setText("son yanıt " + (us < 0 ? "—" : String.format(Locale.ROOT, "%.1f ms", us / 1000.0))
                    + "  ·  başarılı " + m.okCount() + "  ·  hatalı " + m.failCount() + "  ·  bekleyen " + m.pendingCount());
        } else {
            info.setText("unit " + (endpoint.config().modbusAnyUnit ? "hepsi" : endpoint.config().modbusUnitId)
                    + (endpoint.config().modbusDelayMs > 0 ? "  ·  gecikme " + endpoint.config().modbusDelayMs + " ms" : ""));
        }
    }

    private int selectedFc() { return Modbus.FUNCTIONS[fcBox.getSelectedIndex()]; }

    private void updateRequestFields() {
        int fc = selectedFc();
        boolean read = fc <= 4 || fc == Modbus.READ_WRITE_REGISTERS;
        countField.setEnabled(read);
        writeAddrField.setEnabled(fc == Modbus.READ_WRITE_REGISTERS);
        valuesField.setEnabled(!(fc <= 4));
    }

    private void sendRequest() {
        if (!(endpoint instanceof ModbusMasterEndpoint)) return;
        byte[] pdu;
        try {
            int fc = selectedFc();
            int addr = parseInt(addrField.getText(), "adres");
            switch (fc) {
                case Modbus.WRITE_COIL:
                    pdu = Modbus.writeCoil(addr, parseList(valuesField.getText())[0] != 0);
                    break;
                case Modbus.WRITE_REGISTER:
                    pdu = Modbus.writeRegister(addr, parseList(valuesField.getText())[0]);
                    break;
                case Modbus.WRITE_COILS:
                    pdu = Modbus.writeCoils(addr, parseList(valuesField.getText()));
                    break;
                case Modbus.WRITE_REGISTERS:
                    pdu = Modbus.writeRegisters(addr, parseList(valuesField.getText()));
                    break;
                case Modbus.READ_WRITE_REGISTERS:
                    pdu = Modbus.readWriteRegisters(addr, parseInt(countField.getText(), "adet"),
                            parseInt(writeAddrField.getText(), "yazma adresi"), parseList(valuesField.getText()));
                    break;
                default:
                    pdu = Modbus.readRequest(fc, addr, parseInt(countField.getText(), "adet"));
            }
        } catch (IllegalArgumentException e) {
            showStatus(e.getMessage(), true);
            return;
        }
        request(pdu);
    }

    private void request(byte[] pdu) {
        ModbusMasterEndpoint m = (ModbusMasterEndpoint) endpoint;
        try {
            m.request(pdu);
            showStatus("gönderildi: " + Modbus.describe(pdu), false);
        } catch (IOException e) {
            showStatus("Gönderilemedi: " + e.getMessage(), true);
        }
    }

    private void showStatus(String s, boolean error) {
        status.setForeground(error ? Theme.err() : Theme.muted());
        status.setText(" " + s);
    }

    static int parseInt(String s, String what) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace("_", "");
        try {
            if (t.startsWith("0x")) return Integer.parseInt(t.substring(2), 16);
            if (t.startsWith("0b")) return Integer.parseInt(t.substring(2), 2);
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + ": geçersiz sayı '" + s.trim() + "'");
        }
    }

    static int[] parseList(String s) {
        String[] parts = s.trim().split("[,;\\s]+");
        if (s.trim().isEmpty()) throw new IllegalArgumentException("değer girin");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = parseInt(parts[i], "değer");
        return v;
    }

    private String format(int v) {
        switch (formatBox.getSelectedIndex()) {
            case 1: return String.valueOf((short) v);
            case 2: return String.format("0x%04X", v);
            case 3: {
                StringBuilder b = new StringBuilder(Integer.toBinaryString(0x10000 | v).substring(1));
                for (int i = 12; i > 0; i -= 4) b.insert(i, ' ');
                return b.toString();
            }
            default: return String.valueOf(v);
        }
    }

    /** Sayfa (100 adres) tablosu. */
    private final class ValueModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        void reload() { fireTableStructureChanged(); }

        Table table() { return (Table) tableBox.getSelectedItem(); }

        int start() { return (Integer) startSpinner.getValue(); }

        @Override public int getRowCount() { return tables() == null ? 0 : Math.min(PAGE, ModbusTables.SIZE - start()); }

        @Override public int getColumnCount() { return table().bit ? 2 : 3; }

        @Override public String getColumnName(int c) { return c == 0 ? "Adres" : c == 1 ? "Değer" : "Hex"; }

        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Integer.class : c == 1 && table().bit ? Boolean.class : String.class;
        }

        @Override public Object getValueAt(int r, int c) {
            int addr = start() + r;
            if (c == 0) return addr;
            int v = tables().get(table(), addr);
            if (table().bit) return v != 0;
            return c == 1 ? format(v) : String.format("%04X", v);
        }

        @Override public boolean isCellEditable(int r, int c) {
            if (c != 1) return false;
            return endpoint instanceof ModbusSlaveEndpoint || (endpoint instanceof ModbusMasterEndpoint && table().writable);
        }

        @Override public void setValueAt(Object v, int r, int c) {
            int addr = start() + r;
            int value;
            try {
                value = v instanceof Boolean ? ((Boolean) v ? 1 : 0) : parseInt(v.toString().replace(" ", ""), "değer");
                if (!table().bit && (value < -32768 || value > 65535)) throw new IllegalArgumentException("değer 16 bit olmalı");
            } catch (IllegalArgumentException e) {
                showStatus(e.getMessage(), true);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(ModbusPanel.this, e.getMessage(),
                        "Geçersiz değer", JOptionPane.WARNING_MESSAGE));
                return;
            }
            if (endpoint instanceof ModbusSlaveEndpoint) {
                tables().set(table(), addr, value);
            } else {
                request(table().bit ? Modbus.writeCoil(addr, value != 0) : Modbus.writeRegister(addr, value & 0xFFFF));
            }
        }
    }

    /** Periyodik okuma listesi; dogrudan baglanti ayarindaki listeyi duzenler. */
    private final class PollModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Fonksiyon", "Adres", "Adet", "Aralık (ms)", "Etkin"};

        java.util.List<ModbusPoll> list() {
            return endpoint == null ? java.util.Collections.<ModbusPoll>emptyList() : endpoint.config().modbusPolls;
        }

        @Override public int getRowCount() { return list().size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : c == 4 ? Boolean.class : Integer.class;
        }

        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            ModbusPoll p = list().get(r);
            switch (c) {
                case 0: return fcLabel(p.function);
                case 1: return p.address;
                case 2: return p.count;
                case 3: return p.intervalMs;
                default: return p.enabled;
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            ModbusPoll p = list().get(r);
            switch (c) {
                case 0:
                    p.function = Integer.parseInt(v.toString().substring(2, 4));
                    break;
                case 1:
                    p.address = clamp((Integer) v, 0, 65535);
                    break;
                case 2:
                    p.count = clamp((Integer) v, 1, p.function <= 2 ? 2000 : 125);
                    break;
                case 3:
                    p.intervalMs = clamp((Integer) v, 10, 3_600_000);
                    break;
                default:
                    p.enabled = (Boolean) v;
            }
            fireTableRowsUpdated(r, r);
        }
    }

    static String fcLabel(int fc) { return String.format("FC%02d %s", fc, Modbus.functionName(fc)); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
