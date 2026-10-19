package comm.ui;

import comm.runtime.ParameterStore;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.FramingConfig;
import comm.core.LogEntry;
import comm.core.ModbusPoll;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.snmp.SnmpAgentEndpoint;
import comm.core.Stats;
import comm.core.TrafficBus;
import comm.project.ProjectFile;
import comm.scenario.ScenarioContext;
import comm.schema.Codec;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.Values;
import comm.snmp.OidTable;
import comm.schema.SchemaException;
import comm.schema.SchemaLibrary;
import comm.transport.Endpoints;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ana pencere: solda baglantilar; sagda ustte gonderim (Mesaj/Ham/Parametreler, Modbus, SNMP),
 * altta Trafik/Senaryo/Istatistik sekmeleri. Senaryolara simulatoru ScenarioContext olarak acar.
 */
public final class MainFrame extends JFrame implements TrafficBus.Listener, ScenarioContext {
    private static final long serialVersionUID = 1L;
    private static final int DRAIN_MS = 100;
    private static final int STATS_MS = 500;
    private static final String DEMO_SCHEMA = "schemas/ornek.def";
    private static final String DEMO_OIDS = "oids/ornek.oid";
    private static final String TITLE = "Haberleşme Simülatörü";
    private static final String HEADER = "başlık.";

    private final TrafficBus bus = new TrafficBus();
    private final DefaultListModel<Endpoint> endpoints = new DefaultListModel<>();
    private final JList<Endpoint> list = new JList<Endpoint>(endpoints) {
        private static final long serialVersionUID = 1L;

        /** Kartlar panel genisligine uyar; yatay kaydirma cubugu cikmaz. */
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final SendPanel sendPanel = new SendPanel();
    private final ParametersPanel paramsPanel = new ParametersPanel(sendPanel::sendBytes,
            () -> list.getSelectedValue() != null && list.getSelectedValue().state() == Endpoint.State.RUNNING,
            sendPanel::showMessage);
    private final SchemaLibrary schemas = new SchemaLibrary();
    /** Baglanti basina mesaj parametreleri. */
    private final Map<Endpoint, ParameterStore> stores = new HashMap<>();
    private final TrafficPanel trafficPanel = new TrafficPanel();
    private final StatusBar statusBar = new StatusBar();
    private final java.awt.CardLayout listCards = new java.awt.CardLayout();
    private final JPanel listHolder = new JPanel(listCards);
    private final JLabel listCount = new JLabel(" ");
    /** G/C thread'lerinden gelen kayitlar burada birikir, EDT toplu olarak isler. */
    private final ConcurrentLinkedQueue<LogEntry> pending = new ConcurrentLinkedQueue<>();
    private final JButton editBtn = toolButton("Düzenle", Icons.EDIT, "Seçili bağlantının ayarları (⏎)");
    private final JButton copyBtn = toolButton("Kopyala", Icons.COPY, "Seçili bağlantının kopyasını ekler");
    private final JButton deleteBtn = toolButton("Sil", Icons.DELETE, "Seçili bağlantıyı siler (⌫)");
    private final JButton startBtn = toolButton("Başlat", Icons.PLAY, "Seçili bağlantıyı başlatır (⌘R)");
    private final JButton stopBtn = toolButton("Durdur", Icons.STOP, "Seçili bağlantıyı durdurur (⌘.)");
    private final PeriodicTasks periodic = new PeriodicTasks();
    private final ScenarioPanel scenarioPanel = new ScenarioPanel(this);
    private final StatsPanel statsPanel = new StatsPanel(this::all);
    private final ChartPanel chartPanel = new ChartPanel(this::all);
    private final ReplayPanel replayPanel = new ReplayPanel(this::all);
    private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-send");
        t.setDaemon(true);
        return t;
    });
    /** Arka plan servisleri icin baglanti listesinin thread-safe kopyasi. */
    private final java.util.concurrent.CopyOnWriteArrayList<Endpoint> liveList = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final comm.runtime.RuleEngine ruleEngine = new comm.runtime.RuleEngine(bus);
    private final comm.runtime.LiveGenerators liveGenerators = new comm.runtime.LiveGenerators(() -> liveList);
    private Path projectFile;
    private Toasts toasts;

    public MainFrame() {
        this(null);
    }

    /** @param project acilista yuklenecek proje dosyasi; null ise ornek baglantilar eklenir */
    public MainFrame(Path project) {
        super(TITLE);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        bus.addListener(this);

        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JButton addBtn = toolButton("Yeni bağlantı", Icons.ADD, "Yeni bağlantı ekler (⌘T)");
        JButton stopAllBtn = toolButton("Tümünü durdur", Icons.STOP_ALL, "Tüm bağlantıları durdurur");
        JButton reloadBtn = toolButton("Şemaları yükle", Icons.RELOAD, "Değiştirilen .def dosyalarını programı kapatmadan yeniden okur");
        JButton schemaEditBtn = toolButton("Şema düzenleyici", Icons.SCHEMA, "Seçili bağlantının şemasını (yoksa yeni bir şemayı) görsel olarak düzenler");
        JButton themeBtn = toolButton(null, Icons.THEME, "Görünüm: açık / koyu / sistem");
        schemaEditBtn.addActionListener(e -> openSchemaEditor());
        themeBtn.addActionListener(e -> themeMenu().show(themeBtn, 0, themeBtn.getHeight()));
        tools.add(addBtn);
        tools.addSeparator(new Dimension(14, 20));
        tools.add(startBtn);
        tools.add(stopBtn);
        tools.add(stopAllBtn);
        tools.addSeparator(new Dimension(14, 20));
        tools.add(editBtn);
        tools.add(copyBtn);
        tools.add(deleteBtn);
        tools.add(javax.swing.Box.createHorizontalGlue());
        JButton paletteBtn = toolButton("Komutlar", Icons.COMMAND, "Komut paleti: her eyleme, bağlantıya ve mesaja klavyeyle ulaşın (⌘K)");
        paletteBtn.addActionListener(e -> openPalette());
        tools.add(paletteBtn);
        tools.add(schemaEditBtn);
        tools.add(reloadBtn);
        tools.addSeparator(new Dimension(14, 20));
        tools.add(modeToggle());
        tools.addSeparator(new Dimension(10, 20));
        tools.add(themeBtn);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EndpointCell());
        list.setFixedCellHeight(66);
        JScrollPane listScroll = new JScrollPane(list, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel empty = new JPanel(new java.awt.GridBagLayout());
        JPanel emptyBox = new JPanel();
        emptyBox.setLayout(new javax.swing.BoxLayout(emptyBox, javax.swing.BoxLayout.Y_AXIS));
        emptyBox.setOpaque(false);
        JLabel emptyTitle = new JLabel("Henüz bağlantı yok");
        emptyTitle.putClientProperty("FlatLaf.styleClass", "h3");
        JLabel emptyHint = new JLabel("<html><center>TCP, UDP, Modbus ya da SNMP<br>bağlantısı ekleyerek başlayın.</center></html>");
        emptyHint.setForeground(Theme.muted());
        JButton emptyAdd = new JButton("Bağlantı ekle", Icons.ADD);
        emptyAdd.addActionListener(e -> addEndpoint());
        for (javax.swing.JComponent c : new javax.swing.JComponent[]{emptyTitle, emptyHint, emptyAdd}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
            emptyBox.add(c);
            emptyBox.add(javax.swing.Box.createVerticalStrut(8));
        }
        empty.add(emptyBox);
        listHolder.add(listScroll, "list");
        listHolder.add(empty, "empty");
        JPanel listHeader = new JPanel(new BorderLayout());
        listHeader.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 8));
        JLabel listTitle = new JLabel("Bağlantılar");
        listTitle.putClientProperty("FlatLaf.styleClass", "h4");
        listCount.setForeground(Theme.muted());
        JPanel titleRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        titleRow.setOpaque(false);
        titleRow.add(listTitle);
        titleRow.add(listCount);
        JButton headerAdd = toolButton(null, Icons.ADD, "Yeni bağlantı (⌘T)");
        headerAdd.addActionListener(e -> addEndpoint());
        listHeader.add(titleRow, BorderLayout.WEST);
        listHeader.add(headerAdd, BorderLayout.EAST);
        JPanel leftPane = new JPanel(new BorderLayout());
        leftPane.add(listHeader, BorderLayout.NORTH);
        leftPane.add(listHolder, BorderLayout.CENTER);
        leftPane.setPreferredSize(new Dimension(310, 400));
        leftPane.setMinimumSize(new Dimension(220, 100));
        installListActions();

        sendPanel.setParametersPanel(paramsPanel);
        sendPanel.setPeriodicTasks(periodic);
        sendPanel.setRuleEngine(ruleEngine);
        JTabbedPane bottom = new JTabbedPane();
        bottom.putClientProperty("JTabbedPane.tabAreaInsets", new java.awt.Insets(0, 8, 0, 0));
        bottomTabs = bottom;
        rebuildBottomTabs();
        trafficPanel.setActions(trafficActions());
        paramsPanel.setChartAction((m, path) -> {
            Endpoint ep = list.getSelectedValue();
            if (ep == null) return;
            chartPanel.addSeries(ep, m.name, path);
            bottom.setSelectedComponent(chartPanel);
        });
        bottomTabs = bottom;
        right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sendPanel, bottom);
        right.setResizeWeight(0.5);
        main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, right);
        main.setResizeWeight(0);

        JPanel content = new JPanel(new BorderLayout());
        content.add(tools, BorderLayout.NORTH);
        content.add(main, BorderLayout.CENTER);
        content.add(statusBar, BorderLayout.SOUTH);
        setContentPane(content);
        toasts = new Toasts(getRootPane());
        installNumberKeys();
        installDrop();
        setJMenuBar(menuBar());
        UiMode.onChange(this::modeChanged);
        recItem.setVisible(UiMode.advanced());
        scenarioHelpItem.setVisible(UiMode.advanced());

        addBtn.addActionListener(e -> addEndpoint());
        editBtn.addActionListener(e -> editSelected());
        copyBtn.addActionListener(e -> copySelected());
        deleteBtn.addActionListener(e -> deleteSelected());
        startBtn.addActionListener(e -> startSelected());
        stopBtn.addActionListener(e -> {
            Endpoint ep = list.getSelectedValue();
            if (ep != null) ep.stop();
        });
        stopAllBtn.addActionListener(e -> {
            for (Endpoint ep : all()) ep.stop();
        });
        reloadBtn.addActionListener(e -> reloadSchemas());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectionChanged();
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isPopupTrigger() && list.getSelectedValue() != null) toggleSelected();
            }

            @Override public void mousePressed(MouseEvent e) { popup(e); }

            @Override public void mouseReleased(MouseEvent e) { popup(e); }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    contextMenu().show(list, e.getX(), e.getY());
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (isShowing()) saveLayoutPrefs();
            }

            @Override public void windowClosed(WindowEvent e) {
                scenarioPanel.stop();
                replayPanel.stop();
                periodic.stopAll();
                liveGenerators.shutdown();
                ruleEngine.shutdown();
                for (Endpoint ep : all()) ep.stop();
            }
        });

        new Timer(DRAIN_MS, e -> drain()).start();
        new Timer(STATS_MS, e -> updateStatus()).start();
        new Timer(1000, e -> statsPanel.refresh()).start();
        new Timer(500, e -> chartPanel.tick()).start();

        setSize(1360, 900);
        setLocationRelativeTo(null);
        restoreLayoutPrefs();
        // macOS'ta ⌘Q pencere kapanis olayi uretmeden cikabilir; yerlesim yine de kaydedilsin
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isShowing()) saveLayoutPrefs(); // yalnizca ekranda gorunen pencerenin yerlesimi
        }, "layout-save"));
        if (project != null) {
            SwingUtilities.invokeLater(() -> openProject(project));
        } else {
            addDemoEndpoints();
            selectionChanged();
        }
    }

    // =========================== menu ===========================

    private static JButton toolButton(String text, javax.swing.Icon icon, String tip) {
        return UiKit.tool(text, icon, tip);
    }

    private JTabbedPane bottomTabs;
    private JMenuItem recItem;
    private JMenuItem scenarioHelpItem;
    private javax.swing.JRadioButtonMenuItem simpleItem;
    private javax.swing.JRadioButtonMenuItem advancedItem;
    private javax.swing.JToggleButton simpleBtn;
    private javax.swing.JToggleButton advancedBtn;

    /** Arac cubugundaki Basit | Gelismis anahtari. */
    private JComponent modeToggle() {
        simpleBtn = new javax.swing.JToggleButton("Basit", !UiMode.advanced());
        advancedBtn = new javax.swing.JToggleButton("Gelişmiş", UiMode.advanced());
        simpleBtn.setToolTipText("Sade görünüm: ileri özellikler gizlenir (ayarlar çalışmaya devam eder)");
        advancedBtn.setToolTipText("Tüm özellikler: kurallar, üreteçler, senaryo, tekrar oynatma, hata enjeksiyonu…");
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(simpleBtn);
        g.add(advancedBtn);
        simpleBtn.addActionListener(e -> UiMode.set(false));
        advancedBtn.addActionListener(e -> UiMode.set(true));
        JPanel p = new JPanel(new java.awt.GridLayout(1, 2, 0, 0));
        p.setOpaque(false);
        for (javax.swing.JToggleButton b : new javax.swing.JToggleButton[]{simpleBtn, advancedBtn}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            p.add(b);
        }
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    /** Alt sekmeler: Basit'te Trafik, Grafik, Istatistik; Gelismis'te Senaryo ve Tekrar oynat da. */
    private void rebuildBottomTabs() {
        java.awt.Component sel = bottomTabs.getSelectedComponent();
        bottomTabs.removeAll();
        boolean adv = UiMode.advanced();
        bottomTabs.addTab("Trafik", trafficPanel);
        if (adv) bottomTabs.addTab("Senaryo", scenarioPanel);
        bottomTabs.addTab("Grafik", chartPanel);
        if (adv) bottomTabs.addTab("Tekrar oynat", replayPanel);
        bottomTabs.addTab("İstatistik", statsPanel);
        for (int i = 0; i < bottomTabs.getTabCount(); i++) Theme.sync((JComponent) bottomTabs.getComponentAt(i));
        if (sel != null && bottomTabs.indexOfComponent(sel) >= 0) bottomTabs.setSelectedComponent(sel);
    }

    /** Mod degisti: tum gorunur parcalari yeniler. */
    private void modeChanged() {
        boolean adv = UiMode.advanced();
        simpleBtn.setSelected(!adv);
        advancedBtn.setSelected(adv);
        simpleItem.setSelected(!adv);
        advancedItem.setSelected(adv);
        recItem.setVisible(adv);
        scenarioHelpItem.setVisible(adv);
        rebuildBottomTabs();
        sendPanel.modeChanged();
        paramsPanel.modeChanged();
        trafficPanel.modeChanged();
        statusBar.setText(adv ? "Gelişmiş görünüm: tüm özellikler açık" : "Basit görünüm: ileri özellikler gizlendi (ayarları çalışmaya devam eder)");
        revalidate();
        repaint();
    }
    private JSplitPane main;
    private JSplitPane right;
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(MainFrame.class);

    /** Su anki yerlesim. */
    comm.project.LayoutState layoutState() {
        comm.project.LayoutState l = new comm.project.LayoutState();
        l.maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        java.awt.Rectangle b = l.maximized ? getBounds() : getBounds();
        l.x = b.x;
        l.y = b.y;
        l.width = b.width;
        l.height = b.height;
        l.listWidth = main.getDividerLocation();
        int h = right.getHeight() - right.getDividerSize();
        l.rightRatio = h <= 0 ? -1 : (double) right.getDividerLocation() / h;
        l.trafficRatio = trafficPanel.splitRatio();
        l.trafficColumns = trafficPanel.columnWidths();
        l.bottomTab = bottomTabs.getSelectedIndex();
        return l;
    }

    /** Yerlesimi uygular; ekran disinda kalacak konumlar yok sayilir. */
    void applyLayout(comm.project.LayoutState l) {
        if (l == null) return;
        if (l.width >= 600 && l.height >= 400) {
            java.awt.Rectangle r = new java.awt.Rectangle(l.x, l.y, l.width, l.height);
            boolean visible = false;
            for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (d.getDefaultConfiguration().getBounds().intersects(r)) visible = true;
            }
            if (visible) setBounds(r);
            else setSize(l.width, l.height);
        }
        if (l.maximized) setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
        trafficPanel.setColumnWidths(l.trafficColumns);
        if (l.bottomTab >= 0 && l.bottomTab < bottomTabs.getTabCount()) bottomTabs.setSelectedIndex(l.bottomTab);
        SwingUtilities.invokeLater(() -> {
            validate();
            if (l.listWidth >= 200) main.setDividerLocation(l.listWidth);
            if (l.rightRatio > 0.05 && l.rightRatio < 0.95) right.setDividerLocation(l.rightRatio);
            trafficPanel.setSplitRatio(l.trafficRatio);
        });
    }

    private void saveLayoutPrefs() {
        try {
            PREFS.put("layout", comm.project.ProjectFile.toJson(layoutState()));
        } catch (IOException | RuntimeException ignored) {
            // yerlesim kaydedilemezse onemli degil
        }
    }

    private void restoreLayoutPrefs() {
        String s = PREFS.get("layout", null);
        if (s == null) return;
        try {
            applyLayout(comm.project.ProjectFile.fromJson(s, comm.project.LayoutState.class));
        } catch (IOException | RuntimeException ignored) {
            // bozuk tercih: varsayilan yerlesim
        }
    }

    private TrafficPanel.Actions trafficActions() {
        return new TrafficPanel.Actions() {
            @Override public void resend(LogEntry e) {
                Endpoint ep = e.endpoint;
                String peer = ep.config().kind.isClient() ? null : e.peer.isEmpty() ? null : e.peer;
                byte[] data = e.data;
                background.execute(() -> {
                    try {
                        ep.send(data, peer);
                    } catch (IOException | RuntimeException ex) {
                        SwingUtilities.invokeLater(() -> announce(Toasts.Kind.ERROR, "Gönderilemedi", ex.getMessage()));
                    }
                });
            }

            @Override public void copyToForm(LogEntry e) {
                ParameterStore st = stores.get(e.endpoint);
                if (st == null || st.schema() == null) return;
                MessageDef def = st.schema().byName(e.decoded.message.name);
                if (def == null) return;
                st.copyFrom(def, e.decoded);
                list.setSelectedValue(e.endpoint, true);
                sendPanel.showMessage(def);
                statusBar.setText(def.name + " değerleri forma kopyalandı");
            }

            @Override public void toRaw(LogEntry e) {
                list.setSelectedValue(e.endpoint, true);
                sendPanel.setRaw(comm.core.Hex.toHex(e.data));
            }

            @Override public void compare(LogEntry a, LogEntry b) {
                new CompareDialog(MainFrame.this, a, b).setVisible(true);
            }

            @Override public void chart(LogEntry e) {
                chartPanel.addDialog(e.endpoint, e.decoded.message.name);
                bottomTabs.setSelectedComponent(chartPanel);
            }
        };
    }

    private void toggleSelected() {
        Endpoint ep = list.getSelectedValue();
        if (ep == null) return;
        if (ep.isActive()) ep.stop();
        else startSelected();
    }

    /** Liste klavye kisayollari: Enter duzenle, Delete/Backspace sil, Bosluk baslat/durdur. */
    private void installListActions() {
        javax.swing.InputMap im = list.getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        javax.swing.ActionMap am = list.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        am.put("edit", action(this::editSelected));
        am.put("delete", action(this::deleteSelected));
        am.put("toggle", action(this::toggleSelected));
    }

    private static javax.swing.Action action(Runnable r) {
        return new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    private javax.swing.JPopupMenu contextMenu() {
        Endpoint ep = list.getSelectedValue();
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        if (ep == null) return m;
        m.add(item(ep.isActive() ? "Durdur" : "Başlat", ep.isActive() ? Icons.STOP : Icons.PLAY, null, this::toggleSelected));
        m.addSeparator();
        m.add(item("Düzenle…", Icons.EDIT, null, this::editSelected));
        m.add(item("Kopyala", Icons.COPY, null, this::copySelected));
        if (!ep.config().kind.isModbus() && !ep.config().kind.isSnmp()) {
            m.add(item("Şemayı düzenle…", Icons.SCHEMA, null, this::openSchemaEditor));
        }
        m.addSeparator();
        m.add(item("Sil", Icons.DELETE, null, this::deleteSelected));
        return m;
    }

    // =========================== komutlar ===========================

    private void sendCurrent() {
        Endpoint ep = list.getSelectedValue();
        if (ep == null) {
            announce(Toasts.Kind.INFO, "Gönderilemedi", "Önce bir bağlantı seçin.");
        } else if (ep.state() != Endpoint.State.RUNNING) {
            announce(Toasts.Kind.WARN, ep.name() + " çalışmıyor", "Göndermeden önce bağlantıyı başlatın (⌘R).");
        } else if (!sendPanel.sendCurrent()) {
            announce(Toasts.Kind.INFO, "Gönderilemedi", "Açık sekmede gönderilecek bir içerik yok.");
        }
    }

    private void startAll() {
        for (Endpoint ep : all()) {
            if (ep.state() == Endpoint.State.RUNNING) continue;
            background.execute(() -> {
                try {
                    ep.start();
                } catch (Exception ignored) {
                    // hata trafik loguna ve bildirime zaten duser
                }
            });
        }
    }

    private void focusTrafficSearch() {
        bottomTabs.setSelectedComponent(trafficPanel);
        trafficPanel.focusSearch();
    }

    private void openPalette() {
        CommandPalette.show(this, commands());
    }

    /** Paletteki komutlar: genel eylemler, baglantilar, secili baglantinin mesajlari ve hazir mesajlari. */
    private List<CommandPalette.Command> commands() {
        List<CommandPalette.Command> c = new ArrayList<>();
        Endpoint sel = list.getSelectedValue();
        if (sel != null) {
            boolean running = sel.state() == Endpoint.State.RUNNING;
            for (comm.core.MessagePreset p : sendPanel.messagePanel().presets()) {
                c.add(new CommandPalette.Command("Hazır mesaj", "Gönder: " + p.name + "  (" + p.message + ")", null, Icons.STAR, () -> {
                    if (!running) announce(Toasts.Kind.WARN, sel.name() + " çalışmıyor", "Önce bağlantıyı başlatın.");
                    else sendPanel.messagePanel().sendPreset(p);
                }));
            }
            for (comm.schema.MessageDef m : sendPanel.messagePanel().messages()) {
                c.add(new CommandPalette.Command("Mesaj", "Gönder: " + m.name, null, Icons.SEND, () -> {
                    sendPanel.showMessage(m);
                    if (!running) announce(Toasts.Kind.WARN, sel.name() + " çalışmıyor", "Önce bağlantıyı başlatın.");
                    else sendPanel.messagePanel().sendNow();
                }));
                c.add(new CommandPalette.Command("Mesaj", "Forma aç: " + m.name, null, Icons.EDIT, () -> sendPanel.showMessage(m)));
            }
        }
        String mod = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? "⌘" : "Ctrl+";
        c.add(new CommandPalette.Command("Bağlantı", "Gönder (açık sekme)", mod + "⏎", Icons.SEND, this::sendCurrent));
        c.add(new CommandPalette.Command("Bağlantı", "Başlat", mod + "R", Icons.PLAY, this::startSelected));
        c.add(new CommandPalette.Command("Bağlantı", "Durdur", mod + ".", Icons.STOP, () -> {
            Endpoint ep = list.getSelectedValue();
            if (ep != null) ep.stop();
        }));
        c.add(new CommandPalette.Command("Bağlantı", "Tümünü başlat", mod + "⇧R", Icons.PLAY, this::startAll));
        c.add(new CommandPalette.Command("Bağlantı", "Tümünü durdur", null, Icons.STOP_ALL, () -> {
            for (Endpoint ep : all()) ep.stop();
        }));
        c.add(new CommandPalette.Command("Bağlantı", "Yeni bağlantı…", mod + "T", Icons.ADD, this::addEndpoint));
        c.add(new CommandPalette.Command("Bağlantı", "Düzenle…", mod + "E", Icons.EDIT, this::editSelected));
        c.add(new CommandPalette.Command("Bağlantı", "Kopyala", mod + "D", Icons.COPY, this::copySelected));
        c.add(new CommandPalette.Command("Bağlantı", "Sil", null, Icons.DELETE, this::deleteSelected));
        int n = 0;
        for (Endpoint ep : all()) {
            n++;
            String key = n <= 9 ? mod + n : null;
            c.add(new CommandPalette.Command("Git", ep.name() + "  · " + EndpointCell.kindLabel(ep.config().kind), key, null,
                    () -> list.setSelectedValue(ep, true)));
        }
        c.add(new CommandPalette.Command("Proje", "Kaydet", mod + "S", Icons.SAVE, () -> saveProject(false)));
        c.add(new CommandPalette.Command("Proje", "Proje aç…", mod + "O", Icons.OPEN, this::chooseAndOpenProject));
        c.add(new CommandPalette.Command("Proje", "Yeni proje", mod + "N", null, this::newProject));
        c.add(new CommandPalette.Command("Şema", "Şema düzenleyici…", null, Icons.SCHEMA, this::openSchemaEditor));
        c.add(new CommandPalette.Command("Şema", "Şemaları yeniden yükle", null, Icons.RELOAD, this::reloadSchemas));
        c.add(new CommandPalette.Command("Trafik", "Trafikte ara", mod + "F", Icons.SEARCH, this::focusTrafficSearch));
        c.add(new CommandPalette.Command("Trafik", "Trafiği temizle", mod + "L", Icons.CLEAR, trafficPanel::clear));
        c.add(new CommandPalette.Command("Trafik", "Trafiği dışa aktar (CSV)…", null, Icons.EXPORT, () -> exportTraffic(true)));
        for (int i = 0; i < bottomTabs.getTabCount(); i++) {
            java.awt.Component tab = bottomTabs.getComponentAt(i);
            c.add(new CommandPalette.Command("Sekme", bottomTabs.getTitleAt(i), null, null, () -> bottomTabs.setSelectedComponent(tab)));
        }
        for (Theme.Mode mode : Theme.Mode.values()) {
            c.add(new CommandPalette.Command("Tema", mode.label, null, Icons.THEME, () -> Theme.apply(mode)));
        }
        c.add(new CommandPalette.Command("Görünüm", UiMode.advanced() ? "Basit görünüme geç" : "Gelişmiş görünüme geç", null, null,
                () -> UiMode.set(!UiMode.advanced())));
        return c;
    }

    /** ⌘1…⌘9 ile listedeki bağlantıya geçiş. */
    private void installNumberKeys() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        javax.swing.InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getRootPane().getActionMap();
        for (int i = 1; i <= 9; i++) {
            int idx = i - 1;
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, mask), "select-" + i);
            am.put("select-" + i, action(() -> {
                if (idx < endpoints.size()) {
                    list.setSelectedIndex(idx);
                    list.ensureIndexIsVisible(idx);
                }
            }));
        }
    }

    private void fillRecent(JMenu menu) {
        menu.removeAll();
        List<Path> files = RecentProjects.list();
        if (files.isEmpty()) {
            JMenuItem none = new JMenuItem("(henüz yok)");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (Path f : files) {
            JMenuItem i = item(f.getFileName().toString(), null, null, () -> openProject(f));
            i.setToolTipText(f.toString());
            menu.add(i);
        }
        menu.addSeparator();
        menu.add(item("Listeyi temizle", null, RecentProjects::clear));
    }

    /** Pencereye birakilan .json proje dosyasini acar. */
    private void installDrop() {
        setTransferHandler(new javax.swing.TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override public boolean canImport(TransferSupport t) {
                return t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override public boolean importData(TransferSupport t) {
                try {
                    @SuppressWarnings("unchecked")
                    List<java.io.File> files = (List<java.io.File>) t.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    for (java.io.File f : files) {
                        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                        if (n.endsWith(".json")) {
                            SwingUtilities.invokeLater(() -> openProject(f.toPath()));
                            return true;
                        }
                    }
                    announce(Toasts.Kind.INFO, "Açılamadı", "Proje dosyası (.json) bırakın.");
                } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                    announce(Toasts.Kind.ERROR, "Açılamadı", e.getMessage());
                }
                return false;
            }
        });
    }

    private javax.swing.JPopupMenu themeMenu() {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        for (JMenuItem i : themeItems()) m.add(i);
        return m;
    }

    private List<JMenuItem> themeItems() {
        List<JMenuItem> out = new ArrayList<>();
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        for (Theme.Mode mode : Theme.Mode.values()) {
            javax.swing.JRadioButtonMenuItem i = new javax.swing.JRadioButtonMenuItem(mode.label, Theme.mode() == mode);
            i.addActionListener(e -> {
                Theme.apply(mode);
                statusBar.setText("Görünüm: " + mode.label);
            });
            g.add(i);
            out.add(i);
        }
        return out;
    }

    private JMenuBar menuBar() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Dosya");
        file.add(item("Yeni proje", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask), this::newProject));
        file.add(item("Proje aç…", KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), this::chooseAndOpenProject));
        file.add(item("Kaydet", KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), () -> saveProject(false)));
        file.add(item("Farklı kaydet…", KeyStroke.getKeyStroke(KeyEvent.VK_S, mask | InputEvent.SHIFT_DOWN_MASK),
                () -> saveProject(true)));
        JMenu recent = new JMenu("Son projeler");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { fillRecent(recent); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        file.add(recent);
        file.addSeparator();
        file.add(item("Şema düzenleyici…", null, this::openSchemaEditor));
        file.addSeparator();
        file.add(item("Trafiği dışa aktar (CSV)…", null, () -> exportTraffic(true)));
        file.add(item("Trafiği dışa aktar (metin)…", null, () -> exportTraffic(false)));
        recItem = item("Trafiği kayıt olarak dışa aktar (.rec)…", Icons.EXPORT, null, this::exportRecording);
        file.add(recItem);
        file.addSeparator();
        file.add(item("Çıkış", null, () -> {
            saveLayoutPrefs();
            dispose();
        }));
        bar.add(file);
        JMenu conn = new JMenu("Bağlantı");
        conn.add(item("Yeni bağlantı…", Icons.ADD, KeyStroke.getKeyStroke(KeyEvent.VK_T, mask), this::addEndpoint));
        conn.add(item("Düzenle…", Icons.EDIT, KeyStroke.getKeyStroke(KeyEvent.VK_E, mask), this::editSelected));
        conn.add(item("Kopyala", Icons.COPY, KeyStroke.getKeyStroke(KeyEvent.VK_D, mask), this::copySelected));
        conn.add(item("Sil", Icons.DELETE, null, this::deleteSelected));
        conn.addSeparator();
        conn.add(item("Başlat", Icons.PLAY, KeyStroke.getKeyStroke(KeyEvent.VK_R, mask), this::startSelected));
        conn.add(item("Durdur", Icons.STOP, KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, mask), () -> {
            Endpoint ep = list.getSelectedValue();
            if (ep != null) ep.stop();
        }));
        conn.add(item("Tümünü durdur", Icons.STOP_ALL, null, () -> {
            for (Endpoint ep : all()) ep.stop();
        }));
        bar.add(conn);
        conn.addSeparator();
        conn.add(item("Gönder", Icons.SEND, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), this::sendCurrent));
        conn.add(item("Tümünü başlat", Icons.PLAY, KeyStroke.getKeyStroke(KeyEvent.VK_R, mask | InputEvent.SHIFT_DOWN_MASK), this::startAll));
        JMenu view = new JMenu("Görünüm");
        view.add(item("Komut paleti…", Icons.COMMAND, KeyStroke.getKeyStroke(KeyEvent.VK_K, mask), this::openPalette));
        view.add(item("Trafikte ara", Icons.SEARCH, KeyStroke.getKeyStroke(KeyEvent.VK_F, mask), this::focusTrafficSearch));
        view.add(item("Trafiği temizle", Icons.CLEAR, KeyStroke.getKeyStroke(KeyEvent.VK_L, mask), trafficPanel::clear));
        view.addSeparator();
        javax.swing.ButtonGroup modes = new javax.swing.ButtonGroup();
        simpleItem = new javax.swing.JRadioButtonMenuItem("Basit görünüm", !UiMode.advanced());
        advancedItem = new javax.swing.JRadioButtonMenuItem("Gelişmiş görünüm", UiMode.advanced());
        simpleItem.addActionListener(e -> UiMode.set(false));
        advancedItem.addActionListener(e -> UiMode.set(true));
        modes.add(simpleItem);
        modes.add(advancedItem);
        view.add(simpleItem);
        view.add(advancedItem);
        view.addSeparator();
        for (JMenuItem i : themeItems()) view.add(i);
        bar.add(view);
        JMenu help = new JMenu("Yardım");
        scenarioHelpItem = help.add(item("Senaryo komutları", null, () -> {
            JTextArea t = new JTextArea(ScenarioPanel.HELP);
            t.setEditable(false);
            t.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
            JOptionPane.showMessageDialog(this, new JScrollPane(t), "Senaryo komutları", JOptionPane.PLAIN_MESSAGE);
        }));
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(String text, KeyStroke key, Runnable action) {
        return item(text, null, key, action);
    }

    private static JMenuItem item(String text, javax.swing.Icon icon, KeyStroke key, Runnable action) {
        JMenuItem i = new JMenuItem(text, icon);
        if (key != null) i.setAccelerator(key);
        i.addActionListener(e -> action.run());
        return i;
    }

    private void updateTitle() {
        setTitle(projectFile == null ? TITLE : TITLE + " — " + projectFile.getFileName());
    }

    // =========================== proje ===========================

    /** Tum baglantilari, periyodik gorevleri ve senaryoyu durdurup listeyi bosaltir. */
    private void clearAll() {
        scenarioPanel.stop();
        periodic.stopAll();
        for (Endpoint ep : all()) ep.stop();
        endpoints.clear();
        stores.clear();
        endpointsChanged();
        selectionChanged();
    }

    private void newProject() {
        if (JOptionPane.showConfirmDialog(this, "Tüm bağlantılar kapatılıp kaldırılsın mı?", "Yeni proje",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        clearAll();
        projectFile = null;
        updateTitle();
    }

    private JFileChooser projectChooser() {
        JFileChooser fc = new JFileChooser(projectFile != null ? projectFile.getParent().toFile() : new File("projects"));
        fc.setFileFilter(new FileNameExtensionFilter("Simülatör projesi (*.json)", "json"));
        return fc;
    }

    private void chooseAndOpenProject() {
        JFileChooser fc = projectChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openProject(fc.getSelectedFile().toPath());
    }

    void openProject(Path file) {
        ProjectFile p;
        try {
            p = ProjectFile.load(file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, file + "\n" + e.getMessage(), "Proje açılamadı", JOptionPane.ERROR_MESSAGE);
            return;
        }
        clearAll();
        List<String> problems = new ArrayList<>();
        for (ProjectFile.EndpointEntry e : p.endpoints) {
            Endpoint ep = create(e.config);
            ParameterStore st = stores.get(ep);
            Schema schema = st.schema();
            if (schema != null) {
                problems.addAll(prefix(ep, Values.applyTexts(schema.header, st.txHeader(), HEADER, e.txHeader)));
                for (Map.Entry<String, Map<String, String>> m : e.txMessages.entrySet()) {
                    MessageDef def = schema.byName(m.getKey());
                    if (def == null) {
                        problems.add(ep.name() + ": şemada mesaj yok: " + m.getKey());
                        continue;
                    }
                    problems.addAll(prefix(ep, Values.applyTexts(def.body, st.state(def).txBody, "", m.getValue())));
                }
                for (Map.Entry<String, Map<String, String>> g : e.generators.entrySet()) {
                    MessageDef def = g.getKey().isEmpty() ? null : schema.byName(g.getKey());
                    if (!g.getKey().isEmpty() && def == null) continue;
                    for (Map.Entry<String, String> x : g.getValue().entrySet()) {
                        try {
                            st.setGenerator(def, x.getKey(), x.getValue());
                        } catch (IllegalArgumentException ex) {
                            problems.add(ep.name() + ": üreteç " + x.getKey() + ": " + ex.getMessage());
                        }
                    }
                }
                st.changed();
            }
            if (ep instanceof ModbusSlaveEndpoint && e.modbusTables != null) {
                ModbusTables t = ((ModbusSlaveEndpoint) ep).tables();
                for (Map.Entry<String, Map<Integer, Integer>> tb : e.modbusTables.entrySet()) {
                    ModbusTables.Table table;
                    try {
                        table = ModbusTables.Table.valueOf(tb.getKey());
                    } catch (IllegalArgumentException ex) {
                        problems.add(ep.name() + ": bilinmeyen Modbus tablosu " + tb.getKey());
                        continue;
                    }
                    for (Map.Entry<Integer, Integer> v : tb.getValue().entrySet()) {
                        if (v.getKey() >= 0 && v.getKey() < ModbusTables.SIZE) t.set(table, v.getKey(), v.getValue());
                    }
                }
            }
            if (ep instanceof SnmpAgentEndpoint && e.oidTable != null) {
                try {
                    ((SnmpAgentEndpoint) ep).table().copyFrom(OidTable.parse(String.join("\n", e.oidTable), ep.name()));
                } catch (IOException ex) {
                    problems.add(ex.getMessage());
                }
            }
            endpoints.addElement(ep);
        }
        scenarioPanel.setText(p.scenario);
        applyLayout(p.layout);
        projectFile = file;
        if (isShowing()) RecentProjects.add(file);
        updateTitle();
        endpointsChanged();
        if (!endpoints.isEmpty()) list.setSelectedIndex(0);
        selectionChanged();
        announce(Toasts.Kind.OK, "Proje açıldı", file.getFileName() + " · " + endpoints.size() + " bağlantı");
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Bazı değerler uygulanamadı:\n" + String.join("\n",
                    problems.subList(0, Math.min(20, problems.size()))) + (problems.size() > 20 ? "\n…" : ""),
                    "Proje uyarıları", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static List<String> prefix(Endpoint ep, List<String> problems) {
        List<String> out = new ArrayList<>();
        for (String p : problems) out.add(ep.name() + ": " + p);
        return out;
    }

    private void saveProject(boolean ask) {
        Path file = projectFile;
        if (ask || file == null) {
            JFileChooser fc = projectChooser();
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (!f.getName().contains(".")) f = new File(f.getPath() + ".json");
            file = f.toPath();
        }
        ProjectFile p = new ProjectFile();
        for (Endpoint ep : all()) {
            ProjectFile.EndpointEntry e = new ProjectFile.EndpointEntry();
            e.config = ep.config().copy();
            ParameterStore st = stores.get(ep);
            if (st != null && st.schema() != null) {
                e.txHeader = Values.toTexts(st.schema().header, st.txHeader(), HEADER);
                for (ParameterStore.MsgState ms : st.states()) e.txMessages.put(ms.def.name, Values.toTexts(ms.def.body, ms.txBody, ""));
                e.generators = st.generatorTexts();
            }
            if (ep instanceof ModbusSlaveEndpoint) {
                e.modbusTables = new LinkedHashMap<>();
                for (ModbusTables.Table t : ModbusTables.Table.values()) {
                    Map<Integer, Integer> nz = ((ModbusSlaveEndpoint) ep).tables().nonZero(t);
                    if (!nz.isEmpty()) e.modbusTables.put(t.name(), nz);
                }
            }
            if (ep instanceof SnmpAgentEndpoint) {
                e.oidTable = new ArrayList<>();
                for (OidTable.Entry x : ((SnmpAgentEndpoint) ep).table().snapshot()) e.oidTable.add(OidTable.line(x));
            }
            p.endpoints.add(e);
        }
        p.scenario = scenarioPanel.text();
        p.layout = layoutState();
        try {
            p.save(file);
            projectFile = file;
            RecentProjects.add(file);
            updateTitle();
            announce(Toasts.Kind.OK, "Proje kaydedildi", file.toString());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Kaydedilemedi", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================== disa aktarma ===========================

    private void exportTraffic(boolean csv) {
        List<LogEntry> rows = trafficPanel.visibleEntries();
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Dışa aktarılacak kayıt yok (filtre boş olabilir).");
            return;
        }
        JFileChooser fc = new JFileChooser(new File("."));
        fc.setFileFilter(csv ? new FileNameExtensionFilter("CSV (*.csv)", "csv") : new FileNameExtensionFilter("Metin (*.txt)", "txt"));
        fc.setSelectedFile(new File("trafik." + (csv ? "csv" : "txt")));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            Files.write(f.toPath(), (csv ? TrafficExport.csv(rows) : TrafficExport.text(rows)).getBytes(StandardCharsets.UTF_8));
            announce(Toasts.Kind.OK, "Dışa aktarıldı", rows.size() + " kayıt · " + f.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Yazılamadı", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportRecording() {
        List<LogEntry> rows = trafficPanel.visibleEntries();
        comm.runtime.Recording rec = comm.runtime.Recording.of(rows);
        if (rec.entries.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Kaydedilecek çerçeve yok (filtre boş olabilir).");
            return;
        }
        JFileChooser fc = new JFileChooser(new File("."));
        fc.setFileFilter(new FileNameExtensionFilter("Trafik kaydı (*.rec)", "rec"));
        fc.setSelectedFile(new File("trafik.rec"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getPath() + ".rec");
        try {
            rec.save(f.toPath());
            announce(Toasts.Kind.OK, "Kayıt oluşturuldu", rec.entries.size() + " çerçeve · " + f.getName() + " (Tekrar oynat sekmesinden oynatılabilir)");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Yazılamadı", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================== ScenarioContext ===========================

    /** Islemi EDT'de calistirir; senaryo thread'inden cagrilabilir. */
    @SuppressWarnings("unchecked")
    private <T> T onEdt(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        Object[] result = new Object[1];
        RuntimeException[] error = new RuntimeException[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = c.call();
                } catch (RuntimeException e) {
                    error[0] = e;
                } catch (Exception e) {
                    error[0] = new IllegalStateException(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kesildi");
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        if (error[0] != null) throw error[0];
        return (T) result[0];
    }

    @Override public Endpoint endpoint(String name) {
        return onEdt(() -> {
            for (Endpoint ep : all()) if (ep.name().equals(name)) return ep;
            for (Endpoint ep : all()) if (ep.name().equalsIgnoreCase(name)) return ep;
            throw new IllegalArgumentException("bağlantı bulunamadı: " + name);
        });
    }

    private MessageDef messageOf(ParameterStore st, Endpoint ep, String message) {
        if (st == null || st.schema() == null) throw new IllegalArgumentException(ep.name() + " bağlantısına şema atanmamış");
        MessageDef m = st.schema().byName(message);
        if (m == null) throw new IllegalArgumentException("şemada mesaj yok: " + message);
        return m;
    }

    @Override public void assign(Endpoint ep, String message, Map<String, String> assignments) {
        onEdt(() -> {
            ParameterStore st = stores.get(ep);
            MessageDef m = messageOf(st, ep, message);
            Map<String, String> header = new LinkedHashMap<>();
            Map<String, String> body = new LinkedHashMap<>();
            for (Map.Entry<String, String> a : assignments.entrySet()) {
                String k = a.getKey();
                if (k.startsWith("baslik.")) k = HEADER + k.substring("baslik.".length());
                (k.startsWith(HEADER) ? header : body).put(k, a.getValue());
            }
            List<String> problems = new ArrayList<>(Values.applyTexts(st.schema().header, st.txHeader(), HEADER, header));
            problems.addAll(Values.applyTexts(m.body, st.state(m).txBody, "", body));
            st.changed();
            if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
            return null;
        });
    }

    @Override public byte[] message(Endpoint ep, String message, Map<String, String> assignments) {
        assign(ep, message, assignments);
        return onEdt(() -> {
            ParameterStore st = stores.get(ep);
            MessageDef m = messageOf(st, ep, message);
            return st.encode(m);
        });
    }

    @Override public Map<String, String> lastReceived(Endpoint ep, String message) {
        return onEdt(() -> {
            drain(); // senaryo mesaji gormus olabilir; depo zamanlayiciyi beklemeden guncellensin
            ParameterStore st = stores.get(ep);
            MessageDef m = messageOf(st, ep, message);
            ParameterStore.MsgState ms = st.state(m);
            if (ms.lastRx == null) return null;
            Map<String, String> out = Values.receivedTexts(st.schema().header, ms.lastRx.header, HEADER);
            out.putAll(Values.receivedTexts(m.body, ms.lastRx.body, ""));
            return out;
        });
    }

    @Override public TrafficBus bus() { return bus; }

    /** Ilk acilista hemen denenebilsin diye birbirine baglanan iki ornek. */
    private void addDemoEndpoints() {
        EndpointConfig server = new EndpointConfig();
        server.name = "Örnek TCP sunucu";
        server.kind = EndpointConfig.Kind.TCP_SERVER;
        server.localPort = 5050; // macOS AirPlay 5000 portunu kullanir
        EndpointConfig client = new EndpointConfig();
        client.name = "Örnek TCP istemci";
        client.kind = EndpointConfig.Kind.TCP_CLIENT;
        client.remotePort = 5050;
        boolean demoSchema = Files.isRegularFile(Paths.get(DEMO_SCHEMA));
        if (demoSchema) {
            server.schemaFile = DEMO_SCHEMA;
            client.schemaFile = DEMO_SCHEMA;
            server.framing.type = FramingConfig.Type.LENGTH_PREFIX;
            client.framing.type = FramingConfig.Type.LENGTH_PREFIX;
            // ornek kural: KOMUT gelince HEARTBEAT ile yanit ver (sira no gelen + 1)
            comm.core.ResponseRule rule = new comm.core.ResponseRule();
            rule.when = "KOMUT";
            rule.delayMs = 20;
            rule.reply = "HEARTBEAT";
            rule.assignments = "başlık.seq=$rx.başlık.seq + 1; uptime=counter(1,1)";
            server.rules.add(rule);
        }
        endpoints.addElement(create(server));
        endpoints.addElement(create(client));

        EndpointConfig slave = new EndpointConfig();
        slave.name = "Örnek Modbus slave";
        slave.kind = EndpointConfig.Kind.MODBUS_SLAVE;
        slave.localPort = 5020;
        ModbusSlaveEndpoint mbSlave = (ModbusSlaveEndpoint) create(slave);
        for (int a = 0; a < 10; a++) mbSlave.tables().set(ModbusTables.Table.HOLDING, a, 100 + a);
        mbSlave.tables().set(ModbusTables.Table.COILS, 0, 1);
        comm.core.LiveGenerator wave = new comm.core.LiveGenerator();
        wave.target = "hr:5";
        wave.expr = "sine(0, 1000, 10000)";
        wave.intervalMs = 500;
        slave.liveGenerators.add(wave);
        EndpointConfig master = new EndpointConfig();
        master.name = "Örnek Modbus master";
        master.kind = EndpointConfig.Kind.MODBUS_MASTER;
        master.remotePort = 5020;
        ModbusPoll poll = new ModbusPoll();
        poll.function = 3;
        poll.address = 0;
        poll.count = 10;
        poll.intervalMs = 1000;
        master.modbusPolls.add(poll);
        endpoints.addElement(mbSlave);
        endpoints.addElement(create(master));

        EndpointConfig agent = new EndpointConfig();
        agent.name = "Örnek SNMP agent";
        agent.kind = EndpointConfig.Kind.SNMP_AGENT;
        agent.localPort = 1161;
        agent.trapPort = 1162;
        if (Files.isRegularFile(Paths.get(DEMO_OIDS))) agent.oidFile = DEMO_OIDS;
        EndpointConfig manager = new EndpointConfig();
        manager.name = "Örnek SNMP manager";
        manager.kind = EndpointConfig.Kind.SNMP_MANAGER;
        manager.localPort = 1162;
        manager.remotePort = 1161;
        endpoints.addElement(create(agent));
        endpoints.addElement(create(manager));

        EndpointConfig wsServer = new EndpointConfig();
        wsServer.name = "Örnek WebSocket sunucu";
        wsServer.kind = EndpointConfig.Kind.WS_SERVER;
        wsServer.localPort = 8765;
        wsServer.wsPath = "/sim";
        EndpointConfig wsClient = new EndpointConfig();
        wsClient.name = "Örnek WebSocket istemci";
        wsClient.kind = EndpointConfig.Kind.WS_CLIENT;
        wsClient.remotePort = 8765;
        wsClient.wsPath = "/sim";
        if (demoSchema) {
            wsServer.schemaFile = DEMO_SCHEMA;
            wsClient.schemaFile = DEMO_SCHEMA;
        }
        endpoints.addElement(create(wsServer));
        endpoints.addElement(create(wsClient));
        endpointsChanged();
        list.setSelectedIndex(0);
    }

    /** Baglantiyi uretir, semasini ve parametre deposunu baglar. */
    private Endpoint create(EndpointConfig c) {
        return create(c, new ParameterStore());
    }

    private Endpoint create(EndpointConfig c, ParameterStore store) {
        Endpoint ep = Endpoints.create(c, bus);
        stores.put(ep, store);
        attachSchema(ep);
        if (ep instanceof SnmpAgentEndpoint) {
            try {
                ((SnmpAgentEndpoint) ep).loadOidFile();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, ep.name() + ":\n" + e.getMessage() + "\nVarsayılan OID tablosu kullanılıyor.",
                        "OID tablosu yüklenemedi", JOptionPane.ERROR_MESSAGE);
            }
        }
        return ep;
    }

    /** Semayi yukleyip baglantiya atar; hata olursa sema olmadan devam eder ve kullaniciya gosterir. */
    private boolean attachSchema(Endpoint ep) {
        Schema s = null;
        boolean ok = true;
        try {
            s = schemas.get(ep.config().schemaFile);
            if (s != null) s = s.withOverrides(ep.config().schemaOverrides);
        } catch (IOException e) {
            ok = false;
            schemaError(ep, "Şema dosyası okunamadı: " + e.getMessage());
        } catch (SchemaException e) {
            ok = false;
            schemaError(ep, e.getMessage());
        }
        ep.setSchema(s);
        stores.get(ep).setSchema(s);
        return ok;
    }

    private void schemaError(Endpoint ep, String msg) {
        JOptionPane.showMessageDialog(this, ep.name() + ":\n" + msg, "Şema yüklenemedi", JOptionPane.ERROR_MESSAGE);
    }

    private void openSchemaEditor() {
        Endpoint ep = list.getSelectedValue();
        String file = ep == null ? null : ep.config().schemaFile;
        SchemaEditor.open(this, file, saved -> reloadSchemas());
    }

    private void reloadSchemas() {
        schemas.clear();
        int ok = 0;
        int total = 0;
        for (Endpoint ep : all()) {
            if (ep.config().schemaFile.isEmpty()) continue;
            total++;
            if (attachSchema(ep)) ok++;
        }
        selectionChanged();
        if (total == 0) announce(Toasts.Kind.INFO, "Şemalar", "Şema atanmış bağlantı yok.");
        else announce(ok == total ? Toasts.Kind.OK : Toasts.Kind.WARN, "Şemalar yeniden yüklendi", ok + " / " + total + " bağlantı");
    }

    private List<Endpoint> all() { return Collections.list(endpoints.elements()); }

    private void endpointsChanged() {
        liveList.clear();
        liveList.addAll(all());
        chartPanel.retain(all());
        trafficPanel.setEndpoints(all());
        listCards.show(listHolder, endpoints.isEmpty() ? "empty" : "list");
        listCount.setText(endpoints.isEmpty() ? "" : String.valueOf(endpoints.size()));
    }

    private void selectionChanged() {
        Endpoint ep = list.getSelectedValue();
        ParameterStore store = ep == null ? null : stores.get(ep);
        sendPanel.setEndpoint(ep, store);
        paramsPanel.setStore(store);
        updateButtons();
        updateStatus();
    }

    private void updateButtons() {
        Endpoint ep = list.getSelectedValue();
        boolean sel = ep != null;
        boolean active = sel && ep.isActive();
        editBtn.setEnabled(sel);
        copyBtn.setEnabled(sel);
        deleteBtn.setEnabled(sel);
        startBtn.setEnabled(sel && !active);
        stopBtn.setEnabled(active);
    }

    private void addEndpoint() {
        EndpointConfig c = EndpointDialog.show(this, "Yeni bağlantı", new EndpointConfig());
        if (c == null) return;
        endpoints.addElement(create(c));
        endpointsChanged();
        list.setSelectedIndex(endpoints.size() - 1);
    }

    private void editSelected() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        Endpoint old = endpoints.get(i);
        EndpointConfig c = EndpointDialog.show(this, "Bağlantıyı düzenle", old.config().copy());
        if (c == null) return;
        boolean wasActive = old.isActive();
        old.stop();
        periodic.stop(old);
        Endpoint ep = create(c, stores.remove(old)); // gonderilecek degerler korunur
        if (old instanceof ModbusSlaveEndpoint && ep instanceof ModbusSlaveEndpoint) {
            ((ModbusSlaveEndpoint) ep).tables().copyFrom(((ModbusSlaveEndpoint) old).tables());
        }
        if (old instanceof SnmpAgentEndpoint && ep instanceof SnmpAgentEndpoint
                && old.config().oidFile.equals(c.oidFile)) {
            ((SnmpAgentEndpoint) ep).table().copyFrom(((SnmpAgentEndpoint) old).table()); // kaydedilmemis duzenlemeler
        }
        endpoints.set(i, ep);
        endpointsChanged();
        list.setSelectedIndex(i);
        selectionChanged();
        if (wasActive) start(ep);
    }

    private void copySelected() {
        Endpoint ep = list.getSelectedValue();
        if (ep == null) return;
        EndpointConfig c = ep.config().copy();
        c.name = c.name + " (kopya)";
        endpoints.addElement(create(c));
        endpointsChanged();
        list.setSelectedIndex(endpoints.size() - 1);
    }

    private void deleteSelected() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        Endpoint ep = endpoints.get(i);
        int answer = JOptionPane.showConfirmDialog(this, "\"" + ep.name() + "\" silinsin mi?", "Sil",
                JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        ep.stop();
        periodic.stop(ep);
        stores.remove(ep);
        endpoints.remove(i);
        endpointsChanged();
        if (!endpoints.isEmpty()) list.setSelectedIndex(Math.min(i, endpoints.size() - 1));
    }

    private void startSelected() {
        Endpoint ep = list.getSelectedValue();
        if (ep != null) start(ep);
    }

    private void start(Endpoint ep) {
        try {
            ep.start();
        } catch (IOException | RuntimeException e) {
            // ayrinti trafik loguna da yazildi
            JOptionPane.showMessageDialog(this, ep.name() + " başlatılamadı:\n" + e.getMessage(),
                    "Başlatılamadı", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void drain() {
        if (pending.isEmpty()) return;
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e;
        while ((e = pending.poll()) != null) {
            batch.add(e);
            if (e.dir == comm.core.Direction.ERROR && toasts != null) {
                // ayni baglantidan gelen hata yagmuru tek bildirime iner
                toasts.show(Toasts.Kind.ERROR, e.endpoint.name(), e.text, "err:" + System.identityHashCode(e.endpoint), 15000);
            }
            ParameterStore st = stores.get(e.endpoint);
            if (st != null) st.record(e);
            chartPanel.offer(e);
        }
        trafficPanel.append(batch);
        list.repaint(); // etkinlik vurgusu hemen yansisin
        sendPanel.refreshValues();
        paramsPanel.refreshIfChanged();
    }

    /** Durum cubuguna yazar ve kosede bildirim gosterir. */
    void announce(Toasts.Kind kind, String title, String text) {
        statusBar.setText(title + (text == null || text.isEmpty() ? "" : ": " + text));
        if (toasts != null) toasts.show(kind, title, text);
    }

    private void updateStatus() {
        statusBar.update(list.getSelectedValue(), all());
        list.repaint(); // kartlardaki canli sayaclar
    }

    private static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024));
    }

    // --- TrafficBus.Listener (G/C thread'lerinden) ---

    @Override public void onEntry(LogEntry entry) { pending.add(entry); }

    @Override public void onStateChanged(Endpoint endpoint) {
        SwingUtilities.invokeLater(() -> {
            int i = endpoints.indexOf(endpoint);
            if (i < 0) return;
            list.repaint();
            if (endpoint == list.getSelectedValue()) {
                sendPanel.refreshState();
                paramsPanel.updateButtons();
                updateButtons();
            }
        });
    }
}
