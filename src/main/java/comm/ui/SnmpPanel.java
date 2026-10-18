package comm.ui;

import comm.core.Endpoint;
import comm.snmp.OidTable;
import comm.snmp.SnmpAgentEndpoint;
import comm.snmp.SnmpManagerEndpoint;
import comm.snmp.SnmpValues;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** SNMP agent (OID tablosu, trap) ya da manager (istekler, sonuclar) paneli. */
final class SnmpPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String[] OPS = {"GET", "GETNEXT", "GETBULK", "WALK", "SET"};

    private final CardLayout cards = new CardLayout();
    private Endpoint endpoint;

    // agent
    private final OidModel oids = new OidModel();
    private final JTable oidTable = new JTable(oids);
    private final JLabel fileLabel = new JLabel(" ");
    private final JTextField trapOid = new JTextField("1.3.6.1.4.1.99999.0.1", 22);
    private final JCheckBox trapWithSelected = new JCheckBox("Seçili satırları ekle", true);
    private final JLabel agentStatus = new JLabel(" ");
    private long seenTableVersion = -1;
    private JPanel agentSouth;

    /** Canli degerler yalnizca Gelismis gorunumde. */
    void modeChanged() {
        boolean want = UiMode.advanced();
        boolean has = livePanel.getParent() == agentSouth;
        if (want == has) return;
        if (want) {
            agentSouth.setLayout(new java.awt.GridLayout(1, 2, 8, 0));
            agentSouth.add(livePanel);
            Theme.sync(livePanel);
        } else {
            agentSouth.remove(livePanel);
            agentSouth.setLayout(new java.awt.GridLayout(1, 1));
        }
        agentSouth.revalidate();
        agentSouth.repaint();
    }

    private final LiveGeneratorPanel livePanel = new LiveGeneratorPanel("Canlı değerler", "OID", "1.3.6.1.4.1.99999.1.1.0");
    private long lastUptimeRefresh;

    // manager
    private final JComboBox<String> opBox = new JComboBox<>(OPS);
    private final JTextField oidField = new JTextField("1.3.6.1.2.1.1", 26);
    private final JComboBox<String> setType = new JComboBox<>(SnmpValues.TYPES);
    private final JTextField setValue = new JTextField(14);
    private final JSpinner nonRep = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
    private final JSpinner maxRep = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
    private final JLabel managerStatus = new JLabel(" ");
    private final ResultModel results = new ResultModel();
    private final JTable resultTable = new JTable(results);
    private final JPanel setPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel bulkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private long seenResults = -1;

    SnmpPanel() {
        setLayout(cards);
        add(agentPanel(), "agent");
        add(managerPanel(), "manager");
        add(new JLabel(" "), "none");
        setEndpoint(null);
    }

    // =========================== agent ===========================

    private JPanel agentPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton load = UiKit.button("Dosyadan yükle…", Icons.OPEN);
        JButton save = UiKit.button("Kaydet", Icons.SAVE);
        JButton saveAs = UiKit.button("Farklı kaydet…", Icons.SAVE);
        JButton add = UiKit.button("Ekle", Icons.ADD);
        JButton del = UiKit.button("Sil", Icons.DELETE);
        bar.add(new JLabel("OID tablosu:"));
        bar.add(fileLabel);
        bar.add(load);
        bar.add(save);
        bar.add(saveAs);
        bar.add(add);
        bar.add(del);
        p.add(bar, BorderLayout.NORTH);

        oidTable.setRowHeight(Math.max(oidTable.getRowHeight(), 20));
        oidTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        oidTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        oidTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        oidTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        oidTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        oidTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(SnmpValues.TYPES)));
        Font mono = Theme.mono(12);
        oidTable.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, s, f, r, c);
                setFont(c == 1 ? t.getFont() : mono);
                return this;
            }
        });
        p.add(new JScrollPane(oidTable), BorderLayout.CENTER);

        JPanel trap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        trap.setBorder(BorderFactory.createTitledBorder("Trap"));
        trap.add(new JLabel("Trap OID:"));
        trap.add(trapOid);
        trap.add(trapWithSelected);
        JButton send = UiKit.primary("Trap gönder", Icons.SEND);
        trap.add(send);
        trap.add(agentStatus);
        agentSouth = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        JPanel south = agentSouth;
        south.add(trap);
        if (UiMode.advanced()) south.add(livePanel);
        else south.setLayout(new java.awt.GridLayout(1, 1));
        p.add(south, BorderLayout.SOUTH);

        load.addActionListener(e -> loadFile());
        save.addActionListener(e -> saveFile(false));
        saveAs.addActionListener(e -> saveFile(true));
        add.addActionListener(e -> addRow());
        del.addActionListener(e -> deleteRows());
        send.addActionListener(e -> sendTrap());
        return p;
    }

    private SnmpAgentEndpoint agent() { return endpoint instanceof SnmpAgentEndpoint ? (SnmpAgentEndpoint) endpoint : null; }

    private void updateFileLabel() {
        SnmpAgentEndpoint a = agent();
        fileLabel.setText(a == null ? " " : a.config().oidFile.isEmpty() ? "(varsayılan, kaydedilmedi)" : a.config().oidFile);
    }

    private void loadFile() {
        SnmpAgentEndpoint a = agent();
        if (a == null) return;
        JFileChooser fc = new JFileChooser(new File("oids"));
        fc.setFileFilter(new FileNameExtensionFilter("OID tablosu (*.oid)", "oid"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String old = a.config().oidFile;
        a.config().oidFile = EndpointDialog.relative(fc.getSelectedFile());
        try {
            a.loadOidFile();
            showAgent(a.table().size() + " OID yüklendi", false);
        } catch (IOException e) {
            a.config().oidFile = old;
            JOptionPane.showMessageDialog(this, e.getMessage(), "OID dosyası okunamadı", JOptionPane.ERROR_MESSAGE);
        }
        updateFileLabel();
        reloadOids();
    }

    private void saveFile(boolean ask) {
        SnmpAgentEndpoint a = agent();
        if (a == null) return;
        if (ask || a.config().oidFile.isEmpty()) {
            JFileChooser fc = new JFileChooser(new File("oids"));
            fc.setFileFilter(new FileNameExtensionFilter("OID tablosu (*.oid)", "oid"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (!f.getName().contains(".")) f = new File(f.getPath() + ".oid");
            a.config().oidFile = EndpointDialog.relative(f);
        }
        try {
            a.table().save(Paths.get(a.config().oidFile));
            showAgent("kaydedildi: " + a.config().oidFile, false);
        } catch (IOException e) {
            showAgent("Kaydedilemedi: " + e.getMessage(), true);
        }
        updateFileLabel();
    }

    private void addRow() {
        SnmpAgentEndpoint a = agent();
        if (a == null) return;
        String base = "1.3.6.1.4.1.99999.1.";
        int n = 1;
        while (a.table().contains(new OID(base + n + ".0"))) n++;
        a.table().put(OidTable.entry(base + n + ".0", "INTEGER", true, "0"));
        reloadOids();
    }

    private void deleteRows() {
        SnmpAgentEndpoint a = agent();
        int[] rows = oidTable.getSelectedRows();
        if (a == null || rows.length == 0) return;
        for (int r : rows) a.table().remove(oids.rows.get(r).oid);
        reloadOids();
    }

    private void sendTrap() {
        SnmpAgentEndpoint a = agent();
        if (a == null) return;
        try {
            OID t = SnmpValues.oid(trapOid.getText());
            List<VariableBinding> vbs = new ArrayList<>();
            if (trapWithSelected.isSelected()) {
                for (int r : oidTable.getSelectedRows()) {
                    OidTable.Entry e = oids.rows.get(r);
                    Variable v = a.table().get(e.oid);
                    if (v != null) vbs.add(new VariableBinding(e.oid, v));
                }
            }
            a.sendTrap(t, vbs);
            showAgent("trap gönderildi → " + a.config().trapHost + ":" + a.config().trapPort + " (" + a.config().snmpVersion + ")", false);
        } catch (IllegalArgumentException | IOException e) {
            showAgent(e.getMessage(), true);
        }
    }

    private void showAgent(String s, boolean error) {
        agentStatus.setForeground(error ? Theme.err() : Theme.muted());
        agentStatus.setText(s);
    }

    private void reloadOids() {
        SnmpAgentEndpoint a = agent();
        oids.rows = a == null ? Collections.<OidTable.Entry>emptyList() : a.table().snapshot();
        seenTableVersion = a == null ? -1 : a.table().version();
        oids.fireTableDataChanged();
    }

    private final class OidModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"OID", "Tip", "Değer", "Yazılabilir"};
        List<OidTable.Entry> rows = Collections.emptyList();

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) { return c == 3 ? Boolean.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) { return !(rows.get(r).uptime && c >= 1); }

        @Override public Object getValueAt(int r, int c) {
            OidTable.Entry e = rows.get(r);
            switch (c) {
                case 0: return e.oid.toDottedString();
                case 1: return SnmpValues.typeOf(e.value);
                case 2: return e.uptime ? SnmpValues.format(e.value) + "  (@uptime)" : SnmpValues.format(e.value);
                default: return e.writable;
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            SnmpAgentEndpoint a = agent();
            if (a == null) return;
            OidTable.Entry e = rows.get(r);
            try {
                switch (c) {
                    case 0: {
                        OID o = SnmpValues.oid(v.toString());
                        if (o.equals(e.oid)) return;
                        if (a.table().contains(o)) throw new IllegalArgumentException("bu OID zaten var: " + o);
                        a.table().remove(e.oid);
                        a.table().put(new OidTable.Entry(o, e.value, e.writable, e.uptime));
                        break;
                    }
                    case 1: {
                        String text = SnmpValues.format(e.value);
                        Variable nv;
                        try {
                            nv = SnmpValues.parse(v.toString(), text);
                        } catch (IllegalArgumentException ex) {
                            nv = SnmpValues.parse(v.toString(), v.toString().equals("OID") ? "0.0" : v.toString().equals("IPADDRESS") ? "0.0.0.0" : v.toString().contains("STRING") ? "" : "0");
                        }
                        a.table().put(new OidTable.Entry(e.oid, nv, e.writable, false));
                        break;
                    }
                    case 2:
                        a.table().put(new OidTable.Entry(e.oid, SnmpValues.parse(SnmpValues.typeOf(e.value), v.toString()), e.writable, false));
                        break;
                    default:
                        a.table().put(new OidTable.Entry(e.oid, e.value, (Boolean) v, e.uptime));
                }
                showAgent(" ", false);
            } catch (IllegalArgumentException ex) {
                showAgent(ex.getMessage(), true);
            }
            reloadOids();
        }
    }

    // =========================== manager ===========================

    private JPanel managerPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("İşlem:"));
        top.add(opBox);
        top.add(new JLabel("OID(ler):"));
        top.add(oidField);
        setPanel.add(new JLabel("Tip:"));
        setPanel.add(setType);
        setPanel.add(new JLabel("Değer:"));
        setPanel.add(setValue);
        top.add(setPanel);
        bulkPanel.add(new JLabel("non-rep:"));
        bulkPanel.add(nonRep);
        bulkPanel.add(new JLabel("max-rep:"));
        bulkPanel.add(maxRep);
        top.add(bulkPanel);
        JButton run = UiKit.primary("Çalıştır", Icons.PLAY);
        JButton clear = UiKit.button("Temizle", Icons.CLEAR);
        top.add(run);
        top.add(clear);
        p.add(top, BorderLayout.NORTH);

        resultTable.setRowHeight(Math.max(resultTable.getRowHeight(), 20));
        int[] w = {95, 150, 240, 110, 300};
        for (int i = 0; i < w.length; i++) resultTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        Font mono = Theme.mono(12);
        resultTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, s, f, r, c);
                setFont(c == 2 || c == 4 ? mono : t.getFont());
                if (!s) setForeground(results.rows.get(r).error ? Theme.err() : t.getForeground());
                return this;
            }
        });
        resultTable.setToolTipText("Çift tıklama OID'yi istek alanına alır");
        p.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        p.add(managerStatus, BorderLayout.SOUTH);

        opBox.addActionListener(e -> updateOpFields());
        run.addActionListener(e -> runOp());
        oidField.addActionListener(e -> runOp());
        clear.addActionListener(e -> {
            if (endpoint instanceof SnmpManagerEndpoint) ((SnmpManagerEndpoint) endpoint).clearResults();
        });
        resultTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = resultTable.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0 && !results.rows.get(r).oid.isEmpty()) {
                    SnmpManagerEndpoint.Result x = results.rows.get(r);
                    oidField.setText(x.oid);
                    if (!x.type.isEmpty() && !x.error) {
                        setType.setSelectedItem(x.type);
                        setValue.setText(x.value);
                    }
                }
            }
        });
        updateOpFields();
        return p;
    }

    private void updateOpFields() {
        String op = (String) opBox.getSelectedItem();
        setPanel.setVisible("SET".equals(op));
        bulkPanel.setVisible("GETBULK".equals(op));
        revalidate();
    }

    private void runOp() {
        if (!(endpoint instanceof SnmpManagerEndpoint)) return;
        SnmpManagerEndpoint m = (SnmpManagerEndpoint) endpoint;
        if (m.state() != Endpoint.State.RUNNING) {
            showManager("Önce bağlantıyı başlatın.", true);
            return;
        }
        try {
            List<OID> list = new ArrayList<>();
            for (String s : oidField.getText().trim().split("[,;\\s]+")) if (!s.isEmpty()) list.add(SnmpValues.oid(s));
            if (list.isEmpty()) throw new IllegalArgumentException("OID girin");
            switch ((String) opBox.getSelectedItem()) {
                case "GET": m.get(list); break;
                case "GETNEXT": m.getNext(list); break;
                case "GETBULK": m.getBulk(list, (Integer) nonRep.getValue(), (Integer) maxRep.getValue()); break;
                case "WALK": m.walk(list.get(0)); break;
                default: {
                    Variable v = SnmpValues.parse((String) setType.getSelectedItem(), setValue.getText());
                    List<VariableBinding> vbs = new ArrayList<>();
                    for (OID o : list) vbs.add(new VariableBinding(o, v));
                    m.set(vbs);
                }
            }
            showManager("gönderildi…", false);
        } catch (IllegalArgumentException e) {
            showManager(e.getMessage(), true);
        }
    }

    private void showManager(String s, boolean error) {
        managerStatus.setForeground(error ? Theme.err() : Theme.muted());
        managerStatus.setText(s);
    }

    private final class ResultModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Zaman", "İşlem", "OID", "Tip", "Değer"};
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
        List<SnmpManagerEndpoint.Result> rows = Collections.emptyList();

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Object getValueAt(int r, int c) {
            SnmpManagerEndpoint.Result x = rows.get(r);
            switch (c) {
                case 0: return fmt.format(new Date(x.time));
                case 1: return x.op;
                case 2: return x.oid;
                case 3: return x.type;
                default: return x.value;
            }
        }
    }

    // =========================== ortak ===========================

    void setEndpoint(Endpoint ep) {
        endpoint = ep;
        cards.show(this, ep instanceof SnmpAgentEndpoint ? "agent" : ep instanceof SnmpManagerEndpoint ? "manager" : "none");
        updateFileLabel();
        reloadOids();
        livePanel.setEndpoint(ep instanceof SnmpAgentEndpoint ? ep : null);
        seenResults = -1;
        showAgent(" ", false);
        showManager(" ", false);
        refresh();
    }

    /** Zamanlayicidan cagrilir. */
    void refresh() {
        SnmpAgentEndpoint a = agent();
        if (a != null) livePanel.refresh();
        if (a != null && !oidTable.isEditing()) {
            long now = System.currentTimeMillis();
            if (a.table().version() != seenTableVersion || now - lastUptimeRefresh > 1000) {
                lastUptimeRefresh = now;
                int[] sel = oidTable.getSelectedRows();
                reloadOids();
                for (int r : sel) if (r < oids.rows.size()) oidTable.addRowSelectionInterval(r, r);
            }
        }
        if (endpoint instanceof SnmpManagerEndpoint) {
            SnmpManagerEndpoint m = (SnmpManagerEndpoint) endpoint;
            if (m.resultsVersion() != seenResults) {
                seenResults = m.resultsVersion();
                results.rows = m.results();
                results.fireTableDataChanged();
                if (!results.rows.isEmpty()) {
                    int last = results.rows.size() - 1;
                    resultTable.scrollRectToVisible(resultTable.getCellRect(last, 0, true));
                    long ms = m.lastLatencyMs();
                    if (!managerStatus.getForeground().equals(Theme.err()) || managerStatus.getText().equals("gönderildi…")) {
                        showManager(results.rows.size() + " sonuç" + (ms >= 0 ? String.format(Locale.ROOT, "  ·  son yanıt %d ms", ms) : ""), false);
                    }
                }
            }
        }
    }
}
