package comm.ui;

import comm.runtime.ParameterStore;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Hex;
import comm.schema.MessageDef;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Secili baglanti icin gonderim: "Mesaj" sekmesi semadan form, "Ham" sekmesi hex/kacisli metin.
 * Hedef es secimi iki sekmede ortaktir.
 */
final class SendPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String TO_ALL = "(tüm eşler / varsayılan hedef)";
    private static final String[] FORMATS = {"Hex", "Metin (\\r \\n \\t \\xHH)"};
    private static final int HISTORY_MAX = 30;

    private final JLabel title = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");
    private final JComboBox<String> peerBox = new JComboBox<>();
    private final JLabel status = new JLabel(" ");
    private final JTabbedPane tabs = new JTabbedPane();
    private final MessagePanel messagePanel = new MessagePanel(this::sendBytes);
    private final ModbusPanel modbusPanel = new ModbusPanel();
    private final SnmpPanel snmpPanel = new SnmpPanel();
    private RulesPanel rulesPanel;
    private final JPanel rawPanel;
    private JComponent parametersPanel;
    private PeriodicTasks periodic;
    private final JSpinner periodMs = new JSpinner(new SpinnerNumberModel(1000, 10, 3_600_000, 100));
    private final JButton periodStart = UiKit.button("Periyodik başlat", Icons.REPEAT);
    private final JButton periodStop = UiKit.button("Durdur", Icons.STOP);
    private final JLabel periodLabel = new JLabel(" ");

    private final JComboBox<String> formatBox = new JComboBox<>(FORMATS);
    private final JComboBox<String> historyBox = new JComboBox<>();
    private final JTextArea input = new JTextArea(5, 40);
    private final JLabel parseLabel = new JLabel(" ");
    private final JButton rawSendButton = UiKit.primary("Gönder", Icons.SEND);

    /** Gonderimler EDT'yi bloklamasin diye sirayla bu thread'de yapilir. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "send");
        t.setDaemon(true);
        return t;
    });
    private Endpoint endpoint;
    private final Chip stateChip = new Chip(" ");
    private JPanel peerRow;

    SendPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(10, 14, 4, 14));

        UiKit.style(title, "h3");
        UiKit.style(summary, "small");
        summary.setForeground(Theme.muted());
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titleRow.add(title);
        titleRow.add(stateChip);
        ((FlowLayout) titleRow.getLayout()).setHgap(0);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        JPanel head = new JPanel(new BorderLayout(0, 2));
        head.add(titleRow, BorderLayout.NORTH);
        head.add(summary, BorderLayout.CENTER);
        peerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        ((FlowLayout) peerRow.getLayout()).setHgap(6);
        peerRow.setBorder(BorderFactory.createEmptyBorder(4, -6, 0, 0));
        peerRow.add(UiKit.muted("Hedef"));
        peerBox.setPrototypeDisplayValue("255.255.255.255:65535 ----------");
        peerRow.add(peerBox);
        peerRow.add(javax.swing.Box.createHorizontalStrut(12));
        peerRow.add(UiKit.muted("Periyodik: her"));
        peerRow.add(periodMs);
        peerRow.add(UiKit.muted("ms"));
        peerRow.add(periodStart);
        peerRow.add(periodStop);
        peerRow.add(periodLabel);
        peerRow.add(status);
        periodStart.setToolTipText("Mesaj sekmesindeki mesajı (güncel değerlerle) ya da Ham sekmesindeki byte'ları periyodik gönderir");
        periodStart.addActionListener(e -> startPeriodic());
        periodStop.addActionListener(e -> {
            if (periodic != null && endpoint != null) periodic.stop(endpoint);
            updatePeriodic();
        });
        head.add(peerRow, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        rawPanel = rawPanel();
        add(tabs, BorderLayout.CENTER);

        setEndpoint(null, null);
    }

    private JPanel rawPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        opts.add(new JLabel("Biçim:"));
        opts.add(formatBox);
        opts.add(new JLabel("Geçmiş:"));
        historyBox.setPrototypeDisplayValue("00 00 00 00 00 00 00 00 00 00 00 00");
        opts.add(historyBox);
        p.add(opts, BorderLayout.NORTH);

        input.setFont(Theme.mono(13));
        input.setLineWrap(true);
        p.add(new JScrollPane(input), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(parseLabel, BorderLayout.CENTER);
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        rawSendButton.setToolTipText((menuMask == ActionEvent.META_MASK ? "⌘" : "Ctrl+") + "Enter");
        bottom.add(rawSendButton, BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);

        rawSendButton.addActionListener(e -> sendRaw());
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask), "send");
        input.getActionMap().put("send", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override public void actionPerformed(ActionEvent e) { sendRaw(); }
        });
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validateInput(); }
            @Override public void removeUpdate(DocumentEvent e) { validateInput(); }
            @Override public void changedUpdate(DocumentEvent e) { validateInput(); }
        });
        formatBox.addActionListener(e -> validateInput());
        historyBox.addActionListener(e -> {
            Object h = historyBox.getSelectedItem();
            if (h != null && historyBox.getSelectedIndex() >= 0) input.setText(h.toString());
        });
        return p;
    }

    void setEndpoint(Endpoint ep, ParameterStore store) {
        endpoint = ep;
        boolean on = ep != null;
        title.setText(on ? ep.name() : "Bağlantı seçin");
        summary.setText(on ? ep.config().describe() : "Soldaki listeden bir bağlantı seçin ya da yeni ekleyin.");
        input.setEnabled(on);
        peerBox.setEnabled(on);
        formatBox.setEnabled(on);
        historyBox.setEnabled(on);
        status.setText(" ");
        rebuildTabs(ep);
        updatePeriodic();
        messagePanel.setStore(store);
        modbusPanel.setEndpoint(on && ep.config().kind.isModbus() ? ep : null);
        snmpPanel.setEndpoint(on && ep.config().kind.isSnmp() ? ep : null);
        if (rulesPanel != null) rulesPanel.setEndpoint(on && !ep.config().kind.isModbus() && !ep.config().kind.isSnmp() ? ep : null);
        if (on && !ep.config().kind.isModbus() && !ep.config().kind.isSnmp() && store != null && store.schema() == null) tabs.setSelectedIndex(1);
        refreshState();
        validateInput();
    }

    /** Durum ya da es listesi degisti. */
    void refreshState() {
        Endpoint ep = endpoint;
        Object selected = peerBox.getSelectedItem();
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
        m.addElement(TO_ALL);
        if (ep != null) {
            List<String> peers = ep.peers();
            for (String p : peers) m.addElement(p);
        }
        peerBox.setModel(m);
        if (selected != null && m.getIndexOf(selected) >= 0) peerBox.setSelectedItem(selected);
        if (ep != null) {
            summary.setText(ep.config().describe());
            Endpoint.State st = ep.state();
            stateChip.set(st.label, () -> Theme.state(st));
        }
        stateChip.setVisible(ep != null);
        boolean running = ep != null && ep.state() == Endpoint.State.RUNNING;
        rawSendButton.setEnabled(running);
        messagePanel.setCanSend(running);
    }

    void setPeriodicTasks(PeriodicTasks p) { periodic = p; }

    /** Basit/Gelismis gorunum degisti. */
    void modeChanged() {
        rebuildTabs(endpoint);
        messagePanel.modeChanged();
        modbusPanel.modeChanged();
        snmpPanel.modeChanged();
    }

    void setRuleEngine(comm.runtime.RuleEngine engine) {
        rulesPanel = new RulesPanel(engine);
        rebuildTabs(endpoint);
    }

    private void startPeriodic() {
        if (endpoint == null || periodic == null) return;
        int i = tabs.getSelectedIndex();
        String tab = i < 0 ? "" : tabs.getTitleAt(i);
        String peer = peerBox.getSelectedIndex() <= 0 ? null : (String) peerBox.getSelectedItem();
        int ms = (Integer) periodMs.getValue();
        if (tab.equals("Mesaj")) {
            java.util.function.Supplier<byte[]> p = messagePanel.payloadSupplier();
            if (p == null) {
                showStatus("Önce bir mesaj seçin (şema gerekli).", true);
                return;
            }
            periodic.start(endpoint, messagePanel.selectedName(), ms, peer, p);
        } else if (tab.equals("Ham")) {
            byte[] data;
            try {
                data = parseRaw();
            } catch (IllegalArgumentException e) {
                showStatus(e.getMessage(), true);
                return;
            }
            periodic.start(endpoint, "Ham " + data.length + " B", ms, peer, () -> data);
        } else {
            showStatus("Bu sekmede periyodik gönderim yok; Modbus için periyodik okuma, SNMP için senaryo kullanın.", true);
            return;
        }
        updatePeriodic();
    }

    private void updatePeriodic() {
        java.util.List<PeriodicTasks.Task> list = periodic == null || endpoint == null
                ? java.util.Collections.<PeriodicTasks.Task>emptyList() : periodic.of(endpoint);
        periodStop.setEnabled(!list.isEmpty());
        if (list.isEmpty()) {
            periodLabel.setText(" ");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (PeriodicTasks.Task t : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.summary());
        }
        periodLabel.setText(sb.toString());
        PeriodicTasks.Task last = list.get(list.size() - 1);
        periodLabel.setToolTipText(last.lastError.isEmpty() ? null : "Son hata: " + last.lastError);
    }

    private void showStatus(String msg, boolean error) {
        status.setForeground(error ? Theme.err() : Theme.muted());
        status.setText(msg);
    }

    /** Sema tabanli baglantilarda gosterilecek Parametreler paneli. */
    void setParametersPanel(JComponent c) {
        parametersPanel = c;
        rebuildTabs(endpoint);
    }

    /** Sekmeleri baglanti turune gore kurar; mumkunse secili sekme korunur. */
    private void rebuildTabs(Endpoint ep) {
        int i = tabs.getSelectedIndex();
        String selected = i >= 0 ? tabs.getTitleAt(i) : null;
        tabs.removeAll();
        EndpointConfig.Kind k = ep == null ? EndpointConfig.Kind.TCP_SERVER : ep.config().kind;
        if (k.isModbus()) {
            tabs.addTab("Modbus", modbusPanel);
            tabs.addTab("Ham", rawPanel);
        } else if (k.isSnmp()) {
            tabs.addTab("SNMP", snmpPanel);
            tabs.addTab("Ham", rawPanel);
        } else {
            tabs.addTab("Mesaj", messagePanel);
            tabs.addTab("Ham", rawPanel);
            if (parametersPanel != null) tabs.addTab("Parametreler", parametersPanel);
            if (rulesPanel != null && UiMode.advanced()) tabs.addTab("Kurallar", rulesPanel);
        }
        for (int t = 0; t < tabs.getTabCount(); t++) Theme.sync((JComponent) tabs.getComponentAt(t));
        int idx = selected == null ? -1 : tabs.indexOfTab(selected);
        if (idx >= 0) tabs.setSelectedIndex(idx);
        // Hedef es ve periyodik gonderim yalnizca TCP/UDP'de anlamli
        peerRow.setVisible(!k.isModbus() && !k.isSnmp());
    }

    /** Parametre deposu degistiyse mesaj formunu yeniler. */
    void refreshValues() {
        updatePeriodic();
        messagePanel.refreshIfChanged();
        modbusPanel.refresh();
        snmpPanel.refresh();
        if (rulesPanel != null) rulesPanel.refresh();
    }

    /** Ham sekmesine hex yazar ve o sekmeyi acar. */
    void setRaw(String hex) {
        formatBox.setSelectedIndex(0);
        input.setText(hex);
        int i = tabs.indexOfTab("Ham");
        if (i >= 0) tabs.setSelectedIndex(i);
    }

    /** Mesaj sekmesini acip verilen mesaji secer. */
    void showMessage(MessageDef m) {
        tabs.setSelectedIndex(0);
        messagePanel.select(m);
    }

    private byte[] parseRaw() {
        String s = input.getText();
        return formatBox.getSelectedIndex() == 0 ? Hex.parse(s) : Hex.parseText(s);
    }

    private void validateInput() {
        if (endpoint == null) {
            parseLabel.setText(" ");
            return;
        }
        try {
            byte[] b = parseRaw();
            parseLabel.setForeground(Theme.muted());
            parseLabel.setText(b.length + " byte");
        } catch (IllegalArgumentException e) {
            parseLabel.setForeground(Theme.err());
            parseLabel.setText(e.getMessage());
        }
    }

    private void sendRaw() {
        if (endpoint == null || !rawSendButton.isEnabled()) return;
        byte[] data;
        try {
            data = parseRaw();
        } catch (IllegalArgumentException e) {
            parseLabel.setForeground(Theme.err());
            parseLabel.setText(e.getMessage());
            return;
        }
        remember(input.getText());
        sendBytes(data);
    }

    /** Secili hedefe arka planda gonderir; hata olursa durum etiketinde gosterir. */
    void sendBytes(byte[] data) {
        Endpoint ep = endpoint;
        if (ep == null) return;
        String peer = peerBox.getSelectedIndex() <= 0 ? null : (String) peerBox.getSelectedItem();
        status.setText(" ");
        sender.execute(() -> {
            try {
                ep.send(data, peer);
            } catch (IOException | RuntimeException e) {
                String msg = "Gönderilemedi: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    status.setForeground(Theme.err());
                    status.setText(msg);
                });
            }
        });
    }

    private void remember(String text) {
        if (text.trim().isEmpty()) return;
        DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) historyBox.getModel();
        m.removeElement(text);
        m.insertElementAt(text, 0);
        while (m.getSize() > HISTORY_MAX) m.removeElementAt(m.getSize() - 1);
        historyBox.setSelectedIndex(-1);
    }
}
