package comm.ui;

import comm.core.EndpointConfig;
import comm.core.EndpointConfig.Kind;
import comm.core.FramingConfig;
import comm.schema.SchemaException;
import comm.schema.SchemaOverrides;
import comm.schema.SchemaParser;
import comm.snmp.OidTable;
import comm.snmp.SnmpSupport;
import comm.transport.Framer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Baglanti ekleme/duzenleme formu. Ture ve cercevelemeye gore ilgisiz alanlari gizler. */
final class EndpointDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JTextField name = new JTextField(20);
    private final JComboBox<Kind> kind = new JComboBox<>(Kind.values());
    private final JTextField localAddress = new JTextField(14);
    private final JSpinner localPort = portSpinner();
    private final JTextField remoteHost = new JTextField(14);
    private final JSpinner remotePort = portSpinner();
    private final JSpinner reconnectMs = new JSpinner(new SpinnerNumberModel(0, 0, 600_000, 500));
    private final JTextField multicastGroup = new JTextField(14);
    private final JComboBox<String> networkInterface = new JComboBox<>();
    private final JSpinner ttl = new JSpinner(new SpinnerNumberModel(1, 0, 255, 1));
    private final JCheckBox broadcast = new JCheckBox("Broadcast gönderimine izin ver");
    private final JComboBox<FramingConfig.Type> framing = new JComboBox<>(FramingConfig.Type.values());
    private final JComboBox<Integer> lengthSize = new JComboBox<>(new Integer[]{1, 2, 4});
    private final JCheckBox bigEndian = new JCheckBox("Big endian");
    private final JCheckBox includesHeader = new JCheckBox("Uzunluk, alanın kendisini de sayar");
    private final JTextField delimiter = new JTextField(10);
    private final JSpinner fixedLength = new JSpinner(new SpinnerNumberModel(16, 1, 1 << 20, 1));
    private final JTextField schemaFile = new JTextField(20);
    private static final String SCHEMA = "Şemadaki";
    private final JComboBox<String> ovOrder = new JComboBox<>(new String[]{SCHEMA, "Big endian", "Little endian"});
    private final JCheckBox ovForce = new JCheckBox("Alan soneklerini de geçersiz kıl (uint16le gibi)");
    private final JComboBox<String> ovChecksum = new JComboBox<>(new String[]{SCHEMA, "SUM8", "XOR8", "CRC16", "CRC16_CCITT", "CRC32"});
    private final JCheckBox ovFromOn = new JCheckBox("değiştir:");
    private final JSpinner ovFrom = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JComboBox<String> ovLength = new JComboBox<>(new String[]{SCHEMA, "Gövde (body)", "Tüm çerçeve (all)", "Alandan sonrası (after)"});
    private final JCheckBox ovAdjustOn = new JCheckBox("değiştir:");
    private final JSpinner ovAdjust = new JSpinner(new SpinnerNumberModel(0, -65535, 65535, 1));
    private final JSpinner unitId = new JSpinner(new SpinnerNumberModel(1, 0, 255, 1));
    private final JCheckBox anyUnit = new JCheckBox("Her unit id'ye yanıt ver");
    private final JSpinner delayMs = new JSpinner(new SpinnerNumberModel(0, 0, 60_000, 10));
    private final JSpinner timeoutMs = new JSpinner(new SpinnerNumberModel(1000, 10, 600_000, 100));
    private final JComboBox<String> snmpVersion = new JComboBox<>(SnmpSupport.VERSIONS);
    private final JTextField readCommunity = new JTextField(12);
    private final JTextField writeCommunity = new JTextField(12);
    private final JTextField snmpUser = new JTextField(12);
    private final JComboBox<String> authProto = new JComboBox<>(SnmpSupport.AUTH);
    private final JPasswordField authPass = new JPasswordField(12);
    private final JComboBox<String> privProto = new JComboBox<>(SnmpSupport.PRIV);
    private final JPasswordField privPass = new JPasswordField(12);
    private final JSpinner snmpTimeout = new JSpinner(new SpinnerNumberModel(1500, 50, 600_000, 100));
    private final JSpinner snmpRetries = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
    private final JTextField oidFile = new JTextField(20);
    private final JTextField trapHost = new JTextField(14);
    private final JCheckBox showAdvanced = new JCheckBox("Gelişmiş ayarları göster");
    private final JTextField wsPath = new JTextField(14);
    private final JTextField wsProtocol = new JTextField(14);
    private final JCheckBox wsText = new JCheckBox("Geçerli UTF-8 yükleri metin çerçevesiyle gönder (aksi halde binary)");
    private final JSpinner wsPing = new JSpinner(new SpinnerNumberModel(0, 0, 3_600_000, 1000));
    private final JCheckBox fEnabled = new JCheckBox("Etkin (test amaçlı: gönderilen çerçeveler bozulur)");
    private final JSpinner fDrop = pct(), fDup = pct(), fChecksum = pct(), fLength = pct(), fFlip = pct(), fTrunc = pct();
    private final JSpinner fDelay = new JSpinner(new SpinnerNumberModel(0, 0, 600_000, 10));
    private final JSpinner fJitter = new JSpinner(new SpinnerNumberModel(0, 0, 600_000, 10));
    private final JSpinner trapPort = portSpinner();

    private final JPanel form = new JPanel(new GridBagLayout());
    private final List<JComponent[]> rows = new ArrayList<>();
    private JComponent[] localAddrRow, localPortRow, remoteHostRow, remotePortRow, reconnectRow,
            groupRow, ifaceRow, ttlRow, broadcastRow, framingRow, lenSizeRow, endianRow, headerRow,
            delimRow, fixedRow, schemaRow, ovOrderRow, ovForceRow, ovChecksumRow, ovFromRow, ovLengthRow,
            ovAdjustRow, unitRow, anyUnitRow, delayRow, timeoutRow, versionRow, readComRow,
            writeComRow, userRow, authRow, authPassRow, privRow, privPassRow, snmpTimeoutRow, retriesRow,
            oidRow, trapHostRow, trapPortRow, wsPathRow, wsProtoRow, wsTextRow, wsPingRow, fEnabledRow, fRow1, fRow2, fRow3, fRow4;
    private EndpointConfig result;
    private java.util.List<comm.core.ModbusPoll> polls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.List<comm.core.ResponseRule> rules = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.List<comm.core.LiveGenerator> liveGens = new java.util.concurrent.CopyOnWriteArrayList<>();

    private EndpointDialog(Window owner, String title, EndpointConfig cfg) {
        super(owner, title, ModalityType.APPLICATION_MODAL);

        networkInterface.setEditable(true);
        networkInterface.addItem("");
        for (String n : interfaceNames()) networkInterface.addItem(n);

        section("Genel");
        row("Ad", name);
        row("Tür", kind);
        section("Ağ");
        localAddrRow = row("Yerel adres", localAddress);
        localPortRow = row("Yerel port", localPort);
        remoteHostRow = row("Hedef adres", remoteHost);
        remotePortRow = row("Hedef port", remotePort);
        reconnectRow = row("Yeniden bağlanma (ms, 0 = kapalı)", reconnectMs);
        groupRow = row("Multicast grubu (boş = yok)", multicastGroup);
        ifaceRow = row("Ağ arayüzü", networkInterface);
        ttlRow = row("Multicast TTL", ttl);
        broadcastRow = row("", broadcast);
        section("Çerçeveleme");
        framingRow = row("Çerçeveleme", framing);
        lenSizeRow = row("Uzunluk alanı (byte)", lengthSize);
        endianRow = row("", bigEndian);
        headerRow = row("", includesHeader);
        delimRow = row("Ayraç (hex)", delimiter);
        fixedRow = row("Sabit uzunluk (byte)", fixedLength);
        section("WebSocket");
        wsPathRow = row("Yol", wsPath);
        wsProtoRow = row("Alt protokol", wsProtocol);
        wsTextRow = row("", wsText);
        wsPingRow = row("Ping aralığı (ms, 0 = yok)", wsPing);
        UiKit.placeholder(wsPath, "/  (sunucuda boş = her yol)");
        UiKit.placeholder(wsProtocol, "boş = alt protokol yok");
        section("Mesaj şeması");
        schemaRow = row("Şema dosyası (.def)", schemaPicker());
        section("Bu bağlantı için şema ayarları (dosya değişmez)");
        ovOrderRow = row("Byte sırası", ovOrder);
        ovForceRow = row("", ovForce);
        ovChecksumRow = row("Checksum algoritması", ovChecksum);
        ovFromRow = row("Checksum başlangıcı (byte)", pair(ovFromOn, ovFrom));
        ovLengthRow = row("Uzunluk alanı neyi sayar", ovLength);
        ovAdjustRow = row("Uzunluk düzeltmesi (±)", pair(ovAdjustOn, ovAdjust));
        ovFromOn.addActionListener(e -> updateVisibility());
        ovAdjustOn.addActionListener(e -> updateVisibility());
        ovOrder.addActionListener(e -> updateVisibility());
        schemaFile.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateVisibility(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateVisibility(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateVisibility(); }
        });
        section("Modbus");
        unitRow = row("Unit id", unitId);
        anyUnitRow = row("", anyUnit);
        delayRow = row("Yanıt gecikmesi (ms)", delayMs);
        timeoutRow = row("Yanıt zaman aşımı (ms)", timeoutMs);
        section("SNMP");
        versionRow = row("SNMP sürümü", snmpVersion);
        readComRow = row("Okuma community", readCommunity);
        writeComRow = row("Yazma community", writeCommunity);
        userRow = row("v3 kullanıcı", snmpUser);
        authRow = row("v3 auth", authProto);
        authPassRow = row("v3 auth parolası", authPass);
        privRow = row("v3 priv (şifreleme)", privProto);
        privPassRow = row("v3 priv parolası", privPass);
        snmpTimeoutRow = row("Zaman aşımı (ms)", snmpTimeout);
        retriesRow = row("Tekrar sayısı", snmpRetries);
        oidRow = row("OID tablosu (.oid)", oidPicker());
        trapHostRow = row("Trap hedefi adres", trapHost);
        trapPortRow = row("Trap hedefi port", trapPort);
        section("Hata enjeksiyonu");
        fEnabledRow = row("", fEnabled);
        fRow1 = row("Düşür %", pair(fDrop, new JLabel("   Çift gönder %"), fDup));
        fRow2 = row("Checksum boz %", pair(fChecksum, new JLabel("   Uzunluk kaydır %"), fLength));
        fRow3 = row("Byte değiştir %", pair(fFlip, new JLabel("   Sondan kes %"), fTrunc));
        fRow4 = row("Gecikme (ms)", pair(fDelay, new JLabel("   ± titreşim (ms)"), fJitter));
        fEnabled.addActionListener(e -> updateVisibility());

        load(cfg);
        // gelismis ayarlar: Gelismis gorunumde ya da bu baglantida kullaniliyorsa acik gelir
        showAdvanced.setSelected(UiMode.advanced() || usesAdvanced(cfg));
        showAdvanced.addActionListener(e -> updateVisibility());
        kind.addActionListener(e -> {
            Kind k = (Kind) kind.getSelectedItem();
            // Modbus'a gecerken varsayilan TCP portunu Modbus test portuna cevir
            if (k.isModbus()) {
                if ((Integer) localPort.getValue() == 5050) localPort.setValue(5020);
                if ((Integer) remotePort.getValue() == 5050) remotePort.setValue(5020);
            } else if (k.isWebSocket()) {
                if ((Integer) localPort.getValue() == 5050) localPort.setValue(8765);
                if ((Integer) remotePort.getValue() == 5050) remotePort.setValue(8765);
            } else if (k.isSnmp()) {
                if ((Integer) localPort.getValue() == 5050) localPort.setValue(k == Kind.SNMP_AGENT ? 1161 : 1162);
                if ((Integer) remotePort.getValue() == 5050) remotePort.setValue(1161);
            }
            updateVisibility();
        });
        anyUnit.addActionListener(e -> updateVisibility());
        snmpVersion.addActionListener(e -> updateVisibility());
        framing.addActionListener(e -> updateVisibility());

        JButton ok = UiKit.primary("Tamam", null);
        JButton cancel = new JButton("İptal");
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        form.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        UiKit.placeholder(localAddress, "0.0.0.0 = tüm arayüzler");
        UiKit.placeholder(remoteHost, "örn. 192.168.1.10");
        UiKit.placeholder(multicastGroup, "boş = multicast yok");
        UiKit.placeholder(schemaFile, "boş = yalnızca ham gönderim");
        UiKit.placeholder(oidFile, "boş = varsayılan system grubu");
        UiKit.placeholder(snmpUser, "v3 kullanıcı adı");
        UiKit.placeholder(delimiter, "örn. 0D 0A");
        getContentPane().add(form, BorderLayout.CENTER);
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 4));
        showAdvanced.setToolTipText("Şema geçersiz kılma, hata enjeksiyonu ve ince ayarlar");
        bottomBar.add(showAdvanced, BorderLayout.WEST);
        bottomBar.add(buttons, BorderLayout.EAST);
        getContentPane().add(bottomBar, BorderLayout.SOUTH);
        updateVisibility();
        setLocationRelativeTo(owner);
    }

    /** Formu gosterir; Tamam'a basilirsa yeni ayari, iptalde null dondurur. */
    static EndpointConfig show(Window owner, String title, EndpointConfig cfg) {
        EndpointDialog d = new EndpointDialog(owner, title, cfg);
        d.setVisible(true);
        return d.result;
    }

    /** Baslikli form bolumu; altindaki satirlarin hicbiri gorunmuyorsa baslik da gizlenir. */
    private static final class Section {
        final JComponent header;
        final List<JComponent[]> rows = new ArrayList<>();

        Section(JComponent header) { this.header = header; }
    }

    private final List<Section> sections = new ArrayList<>();

    private void section(String title) {
        JPanel h = new JPanel(new BorderLayout(8, 0));
        JLabel l = new JLabel(title);
        UiKit.style(l, "semibold");
        l.setForeground(Theme.accent());
        h.add(l, BorderLayout.WEST);
        javax.swing.JSeparator sep = new javax.swing.JSeparator();
        JPanel sepWrap = new JPanel(new java.awt.GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.weightx = 1;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sepWrap.add(sep, sc);
        h.add(sepWrap, BorderLayout.CENTER);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = rows.size();
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(sections.isEmpty() ? 4 : 14, 0, 4, 0);
        form.add(h, c);
        rows.add(new JComponent[]{h, h});
        sections.add(new Section(h));
    }

    private JComponent[] row(String label, JComponent field) {
        int y = rows.size();
        JLabel l = new JLabel(label);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 4, 3, 8);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(l, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 4);
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        JComponent[] r = {l, field};
        rows.add(r);
        if (!sections.isEmpty()) sections.get(sections.size() - 1).rows.add(r);
        return r;
    }

    private static void setRowVisible(JComponent[] row, boolean visible) {
        for (JComponent c : row) c.setVisible(visible);
    }

    private void updateVisibility() {
        Kind k = (Kind) kind.getSelectedItem();
        boolean udp = k == Kind.UDP;
        boolean tcp = k == Kind.TCP_SERVER || k == Kind.TCP_CLIENT;
        boolean ws = k.isWebSocket();
        setRowVisible(wsPathRow, ws);
        setRowVisible(wsProtoRow, ws);
        setRowVisible(wsTextRow, ws);
        setRowVisible(wsPingRow, ws);
        boolean agent = k == Kind.SNMP_AGENT;
        boolean manager = k == Kind.SNMP_MANAGER;
        setRowVisible(localAddrRow, !k.isClient());
        setRowVisible(localPortRow, !k.isClient());
        ((JLabel) localPortRow[0]).setText(manager ? "Trap dinleme portu (0 = rastgele)" : "Yerel port");
        setRowVisible(remoteHostRow, !k.isServer() && !agent);
        setRowVisible(remotePortRow, !k.isServer() && !agent);
        ((JLabel) remoteHostRow[0]).setText(udp ? "Varsayılan hedef adres" : manager ? "Agent adresi" : "Hedef adres");
        ((JLabel) remotePortRow[0]).setText(udp ? "Varsayılan hedef port" : manager ? "Agent portu" : "Hedef port");
        boolean snmp = k.isSnmp();
        boolean v3 = "V3".equals(snmpVersion.getSelectedItem());
        setRowVisible(versionRow, snmp);
        ((JLabel) versionRow[0]).setText(agent ? "Trap sürümü" : "SNMP sürümü");
        setRowVisible(readComRow, snmp);
        setRowVisible(writeComRow, snmp);
        // agent v3 isteklerine her zaman yanit verebilsin diye kullanici alanlari agent'ta hep gorunur
        setRowVisible(userRow, snmp && (v3 || agent));
        setRowVisible(authRow, snmp && (v3 || agent));
        setRowVisible(authPassRow, snmp && (v3 || agent));
        setRowVisible(privRow, snmp && (v3 || agent));
        setRowVisible(privPassRow, snmp && (v3 || agent));
        setRowVisible(snmpTimeoutRow, manager);
        setRowVisible(retriesRow, manager);
        setRowVisible(oidRow, agent);
        setRowVisible(trapHostRow, agent);
        setRowVisible(trapPortRow, agent);
        for (JComponent[] r : new JComponent[][]{fEnabledRow, fRow1, fRow2, fRow3, fRow4}) setRowVisible(r, !snmp);
        for (JSpinner sp : new JSpinner[]{fDrop, fDup, fChecksum, fLength, fFlip, fTrunc, fDelay, fJitter}) sp.setEnabled(fEnabled.isSelected());
        fChecksum.setEnabled(fEnabled.isSelected() && (tcp || udp || ws));
        fLength.setEnabled(fEnabled.isSelected() && (tcp || udp || ws));
        setRowVisible(schemaRow, tcp || udp || ws);
        boolean ov = (tcp || udp || ws) && !schemaFile.getText().trim().isEmpty();
        for (JComponent[] r : new JComponent[][]{ovOrderRow, ovChecksumRow, ovFromRow, ovLengthRow, ovAdjustRow}) {
            setRowVisible(r, ov);
        }
        setRowVisible(ovForceRow, ov && ovOrder.getSelectedIndex() > 0);
        ovFrom.setEnabled(ovFromOn.isSelected());
        ovAdjust.setEnabled(ovAdjustOn.isSelected());
        setRowVisible(unitRow, k.isModbus());
        setRowVisible(anyUnitRow, k == Kind.MODBUS_SLAVE);
        unitId.setEnabled(k != Kind.MODBUS_SLAVE || !anyUnit.isSelected());
        setRowVisible(delayRow, k == Kind.MODBUS_SLAVE);
        setRowVisible(timeoutRow, k == Kind.MODBUS_MASTER);
        ((JLabel) remoteHostRow[0]).setText(udp ? "Varsayılan hedef adres" : "Hedef adres");
        ((JLabel) remotePortRow[0]).setText(udp ? "Varsayılan hedef port" : "Hedef port");
        setRowVisible(reconnectRow, k.isClient());
        setRowVisible(groupRow, udp);
        setRowVisible(ifaceRow, udp);
        setRowVisible(ttlRow, udp);
        setRowVisible(broadcastRow, udp);
        FramingConfig.Type f = (FramingConfig.Type) framing.getSelectedItem();
        setRowVisible(framingRow, tcp);
        setRowVisible(lenSizeRow, tcp && f == FramingConfig.Type.LENGTH_PREFIX);
        setRowVisible(endianRow, tcp && f == FramingConfig.Type.LENGTH_PREFIX);
        setRowVisible(headerRow, tcp && f == FramingConfig.Type.LENGTH_PREFIX);
        setRowVisible(delimRow, tcp && f == FramingConfig.Type.DELIMITER);
        setRowVisible(fixedRow, tcp && f == FramingConfig.Type.FIXED);
        if (!showAdvanced.isSelected()) {
            for (JComponent[] r : new JComponent[][]{ovOrderRow, ovForceRow, ovChecksumRow, ovFromRow, ovLengthRow, ovAdjustRow,
                    fEnabledRow, fRow1, fRow2, fRow3, fRow4, wsProtoRow, wsPingRow, delayRow}) {
                setRowVisible(r, false);
            }
        }
        for (Section sec : sections) {
            boolean any = false;
            for (JComponent[] r : sec.rows) any |= r[1].isVisible();
            sec.header.setVisible(any);
        }
        pack();
    }

    private void load(EndpointConfig c) {
        name.setText(c.name);
        kind.setSelectedItem(c.kind);
        localAddress.setText(c.localAddress);
        localPort.setValue(c.localPort);
        remoteHost.setText(c.remoteHost);
        remotePort.setValue(c.remotePort);
        reconnectMs.setValue(c.reconnectMs);
        multicastGroup.setText(c.multicastGroup);
        networkInterface.setSelectedItem(c.networkInterface);
        ttl.setValue(c.multicastTtl);
        broadcast.setSelected(c.broadcast);
        framing.setSelectedItem(c.framing.type);
        lengthSize.setSelectedItem(c.framing.lengthSize);
        bigEndian.setSelected(c.framing.bigEndian);
        includesHeader.setSelected(c.framing.lengthIncludesHeader);
        delimiter.setText(c.framing.delimiterHex);
        fixedLength.setValue(c.framing.fixedLength);
        schemaFile.setText(c.schemaFile);
        SchemaOverrides o = c.schemaOverrides == null ? new SchemaOverrides() : c.schemaOverrides;
        ovOrder.setSelectedIndex(o.byteOrder.equals("BIG") ? 1 : o.byteOrder.equals("LITTLE") ? 2 : 0);
        ovForce.setSelected(o.forceAllFields);
        ovChecksum.setSelectedItem(o.checksum.isEmpty() ? SCHEMA : o.checksum);
        ovFromOn.setSelected(o.checksumFrom != null);
        ovFrom.setValue(o.checksumFrom == null ? 0 : o.checksumFrom);
        ovLength.setSelectedIndex(o.length.equals("LENGTH_BODY") ? 1 : o.length.equals("LENGTH_ALL") ? 2
                : o.length.equals("LENGTH_AFTER") ? 3 : 0);
        ovAdjustOn.setSelected(o.lengthAdjust != null);
        ovAdjust.setValue(o.lengthAdjust == null ? 0 : o.lengthAdjust);
        unitId.setValue(c.modbusUnitId);
        anyUnit.setSelected(c.modbusAnyUnit);
        delayMs.setValue(c.modbusDelayMs);
        timeoutMs.setValue(c.modbusTimeoutMs);
        polls = c.modbusPolls;
        rules = c.rules;
        liveGens = c.liveGenerators;
        snmpVersion.setSelectedItem(c.snmpVersion);
        readCommunity.setText(c.snmpReadCommunity);
        writeCommunity.setText(c.snmpWriteCommunity);
        snmpUser.setText(c.snmpUser);
        authProto.setSelectedItem(c.snmpAuthProtocol);
        authPass.setText(c.snmpAuthPassword);
        privProto.setSelectedItem(c.snmpPrivProtocol);
        privPass.setText(c.snmpPrivPassword);
        snmpTimeout.setValue(c.snmpTimeoutMs);
        snmpRetries.setValue(c.snmpRetries);
        oidFile.setText(c.oidFile);
        trapHost.setText(c.trapHost);
        trapPort.setValue(c.trapPort);
        wsPath.setText(c.wsPath);
        wsProtocol.setText(c.wsSubprotocol);
        wsText.setSelected(c.wsText);
        wsPing.setValue(c.wsPingMs);
        comm.core.FaultConfig fc = c.faults == null ? new comm.core.FaultConfig() : c.faults;
        fEnabled.setSelected(fc.enabled);
        fDrop.setValue(fc.dropPct);
        fDup.setValue(fc.duplicatePct);
        fChecksum.setValue(fc.checksumPct);
        fLength.setValue(fc.lengthPct);
        fFlip.setValue(fc.flipPct);
        fTrunc.setValue(fc.truncatePct);
        fDelay.setValue(fc.delayMs);
        fJitter.setValue(fc.jitterMs);
    }

    /** Baglanti gelismis ayarlardan birini kullaniyor mu (gizlenmesin diye). */
    private static boolean usesAdvanced(EndpointConfig c) {
        return (c.faults != null && c.faults.enabled)
                || (c.schemaOverrides != null && !c.schemaOverrides.isEmpty())
                || !c.wsSubprotocol.isEmpty() || c.wsPingMs > 0 || c.modbusDelayMs > 0;
    }

    private static JSpinner pct() { return new JSpinner(new SpinnerNumberModel(0, 0, 100, 5)); }

    private static JPanel pair(JComponent a, JComponent label, JComponent b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(a);
        p.add(label);
        p.add(b);
        return p;
    }

    private JPanel oidPicker() {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        JButton pick = UiKit.button("Seç…", Icons.OPEN);
        JButton clear = new JButton("Kaldır");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.add(pick);
        buttons.add(clear);
        p.add(oidFile, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        oidFile.setToolTipText("Boş bırakılırsa küçük bir varsayılan system grubu kullanılır.");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File("oids"));
            fc.setFileFilter(new FileNameExtensionFilter("OID tablosu (*.oid)", "oid"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) oidFile.setText(relative(fc.getSelectedFile()));
        });
        clear.addActionListener(e -> oidFile.setText(""));
        return p;
    }

    private static JPanel pair(JComponent a, JComponent b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(a);
        p.add(b);
        return p;
    }

    private JPanel schemaPicker() {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        JButton pick = UiKit.button("Seç…", Icons.OPEN);
        JButton clear = new JButton("Kaldır");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.add(pick);
        buttons.add(clear);
        p.add(schemaFile, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        schemaFile.setToolTipText("Boş bırakılırsa çerçeveler çözülmez; yalnızca ham gönderim yapılır.");
        pick.addActionListener(e -> {
            String cur = schemaFile.getText().trim();
            File start = cur.isEmpty() ? new File("schemas") : new File(cur);
            JFileChooser fc = new JFileChooser(start.isDirectory() ? start : start.getParentFile());
            fc.setFileFilter(new FileNameExtensionFilter("Mesaj şeması (*.def)", "def"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) schemaFile.setText(relative(fc.getSelectedFile()));
        });
        clear.addActionListener(e -> schemaFile.setText(""));
        return p;
    }

    /** Calisma dizini altindaysa goreli yol; proje klasoru tasininca ayar bozulmasin. */
    static String relative(File f) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path p = f.toPath().toAbsolutePath();
        return p.startsWith(cwd) ? cwd.relativize(p).toString() : p.toString();
    }

    private void accept() {
        EndpointConfig c = new EndpointConfig();
        c.name = name.getText().trim();
        c.kind = (Kind) kind.getSelectedItem();
        c.localAddress = orDefault(localAddress.getText(), "0.0.0.0");
        c.localPort = (Integer) localPort.getValue();
        c.remoteHost = orDefault(remoteHost.getText(), "127.0.0.1");
        c.remotePort = (Integer) remotePort.getValue();
        c.reconnectMs = (Integer) reconnectMs.getValue();
        c.multicastGroup = multicastGroup.getText().trim();
        Object iface = networkInterface.getSelectedItem();
        c.networkInterface = iface == null ? "" : iface.toString().trim();
        c.multicastTtl = (Integer) ttl.getValue();
        c.broadcast = broadcast.isSelected();
        c.framing.type = (FramingConfig.Type) framing.getSelectedItem();
        c.framing.lengthSize = (Integer) lengthSize.getSelectedItem();
        c.framing.bigEndian = bigEndian.isSelected();
        c.framing.lengthIncludesHeader = includesHeader.isSelected();
        c.framing.delimiterHex = delimiter.getText().trim();
        c.framing.fixedLength = (Integer) fixedLength.getValue();
        c.schemaFile = c.kind.isModbus() || c.kind.isSnmp() ? "" : schemaFile.getText().trim();
        SchemaOverrides o = new SchemaOverrides();
        o.byteOrder = new String[]{"", "BIG", "LITTLE"}[ovOrder.getSelectedIndex()];
        o.forceAllFields = ovForce.isSelected() && !o.byteOrder.isEmpty();
        o.checksum = ovChecksum.getSelectedIndex() == 0 ? "" : (String) ovChecksum.getSelectedItem();
        o.checksumFrom = ovFromOn.isSelected() ? (Integer) ovFrom.getValue() : null;
        o.length = new String[]{"", "LENGTH_BODY", "LENGTH_ALL", "LENGTH_AFTER"}[ovLength.getSelectedIndex()];
        o.lengthAdjust = ovAdjustOn.isSelected() ? (Integer) ovAdjust.getValue() : null;
        c.schemaOverrides = o;
        c.modbusUnitId = (Integer) unitId.getValue();
        c.modbusAnyUnit = anyUnit.isSelected();
        c.modbusDelayMs = (Integer) delayMs.getValue();
        c.modbusTimeoutMs = (Integer) timeoutMs.getValue();
        c.modbusPolls = polls; // periyodik okumalar Modbus sekmesinden duzenlenir
        c.snmpVersion = (String) snmpVersion.getSelectedItem();
        c.snmpReadCommunity = readCommunity.getText();
        c.snmpWriteCommunity = writeCommunity.getText();
        c.snmpUser = snmpUser.getText().trim();
        c.snmpAuthProtocol = (String) authProto.getSelectedItem();
        c.snmpAuthPassword = new String(authPass.getPassword());
        c.snmpPrivProtocol = (String) privProto.getSelectedItem();
        c.snmpPrivPassword = new String(privPass.getPassword());
        c.snmpTimeoutMs = (Integer) snmpTimeout.getValue();
        c.snmpRetries = (Integer) snmpRetries.getValue();
        c.oidFile = c.kind == Kind.SNMP_AGENT ? oidFile.getText().trim() : "";
        c.trapHost = orDefault(trapHost.getText(), "127.0.0.1");
        c.trapPort = (Integer) trapPort.getValue();
        c.wsPath = wsPath.getText().trim();
        if (c.kind == Kind.WS_CLIENT && !c.wsPath.startsWith("/")) c.wsPath = "/" + c.wsPath;
        c.wsSubprotocol = wsProtocol.getText().trim();
        c.wsText = wsText.isSelected();
        c.wsPingMs = (Integer) wsPing.getValue();
        c.faults = new comm.core.FaultConfig();
        c.faults.enabled = fEnabled.isSelected();
        c.faults.dropPct = (Integer) fDrop.getValue();
        c.faults.duplicatePct = (Integer) fDup.getValue();
        c.faults.checksumPct = (Integer) fChecksum.getValue();
        c.faults.lengthPct = (Integer) fLength.getValue();
        c.faults.flipPct = (Integer) fFlip.getValue();
        c.faults.truncatePct = (Integer) fTrunc.getValue();
        c.faults.delayMs = (Integer) fDelay.getValue();
        c.faults.jitterMs = (Integer) fJitter.getValue();
        c.rules = rules; // kurallar Kurallar sekmesinden duzenlenir
        c.liveGenerators = liveGens;

        String problem = null;
        if (c.name.isEmpty()) problem = "Ad boş olamaz.";
        else if (c.kind.isClient() && c.remotePort == 0) problem = "Hedef port 0 olamaz.";
        else if (c.kind == Kind.TCP_SERVER || c.kind == Kind.TCP_CLIENT) {
            try {
                Framer.create(c.framing);
            } catch (IllegalArgumentException e) {
                problem = "Çerçeveleme: " + e.getMessage();
            }
        }
        if (problem == null && c.kind.isSnmp() && ("V3".equals(c.snmpVersion) || !c.snmpUser.isEmpty())) {
            String v3 = SnmpSupport.validateV3(c);
            if (v3 != null) problem = v3;
        }
        if (problem == null && !c.oidFile.isEmpty()) {
            try {
                OidTable.load(Paths.get(c.oidFile));
            } catch (IOException e) {
                problem = "OID tablosu okunamadı:\n" + e.getMessage();
            }
        }
        if (problem == null && c.kind == Kind.SNMP_AGENT && c.trapPort == 0) problem = "Trap hedefi port 0 olamaz.";
        if (problem == null && !c.schemaFile.isEmpty()) {
            try {
                SchemaParser.parse(Paths.get(c.schemaFile));
            } catch (IOException e) {
                problem = "Şema dosyası okunamadı: " + e.getMessage();
            } catch (SchemaException e) {
                problem = "Şema hatası:\n" + e.getMessage();
            }
        }
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Geçersiz ayar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        result = c;
        dispose();
    }

    private static String orDefault(String s, String def) {
        s = s.trim();
        return s.isEmpty() ? def : s;
    }

    private static JSpinner portSpinner() {
        JSpinner s = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
        s.setEditor(new JSpinner.NumberEditor(s, "#"));
        return s;
    }

    private static List<String> interfaceNames() {
        List<String> names = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && ni.supportsMulticast()) names.add(ni.getName());
            }
        } catch (SocketException ignored) {
            // arayuz listesi alinamadi; alan elle yazilabilir
        }
        return names;
    }
}
