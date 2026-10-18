package comm.ui;

import comm.core.Endpoint;
import comm.runtime.Recording;
import comm.runtime.Replayer;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Kayit dosyasini acip eslenen baglantilardan, zamanlamasini koruyarak yeniden gonderir. */
final class ReplayPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String[] SPEEDS = {"0.25×", "0.5×", "1×", "2×", "5×", "10×", "Beklemeden"};
    private static final double[] SPEED_VALUES = {0.25, 0.5, 1, 2, 5, 10, 0};
    private static final String NONE = "(atla)";

    private static final class Group {
        final String key;
        final String endpoint;
        final String dir;
        final int count;
        boolean include;
        String target;

        Group(String key, int count) {
            this.key = key;
            this.endpoint = key.substring(0, key.lastIndexOf('|'));
            this.dir = key.substring(key.lastIndexOf('|') + 1);
            this.count = count;
        }
    }

    private final Supplier<List<Endpoint>> endpoints;
    private final Replayer replayer = new Replayer();
    private final List<Group> groups = new ArrayList<>();
    private final GroupModel model = new GroupModel();
    private final JTable table = new JTable(model);
    private final JLabel fileLabel = new JLabel("Kayıt dosyası açılmadı");
    private final JComboBox<String> speedBox = new JComboBox<>(SPEEDS);
    private final JCheckBox loop = new JCheckBox("Döngü");
    private final JCheckBox keepPeer = new JCheckBox("Kayıttaki eşe gönder");
    private final JButton startBtn = UiKit.primary("Başlat", Icons.PLAY);
    private final JButton stopBtn = UiKit.button("Durdur", Icons.STOP);
    private final JProgressBar progress = new JProgressBar();
    private final JComboBox<String> targetEditor = new JComboBox<>();
    private Recording recording;

    ReplayPanel(Supplier<List<Endpoint>> endpoints) {
        super(new BorderLayout(0, 6));
        this.endpoints = endpoints;
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton open = UiKit.button("Kayıt aç…", Icons.OPEN);
        bar.add(open);
        bar.add(fileLabel);
        add(bar, BorderLayout.NORTH);

        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(targetEditor));
        int[] w = {50, 220, 60, 70, 240};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        south.add(UiKit.muted("Hız"));
        speedBox.setSelectedIndex(2);
        south.add(speedBox);
        south.add(loop);
        south.add(keepPeer);
        keepPeer.setToolTipText("Sunucu/UDP bağlantılarında kayıttaki adrese gönderir; kapalıysa tüm eşlere / varsayılan hedefe");
        south.add(startBtn);
        south.add(stopBtn);
        progress.setStringPainted(true);
        progress.setString("hazır");
        south.add(progress);
        add(south, BorderLayout.SOUTH);

        open.addActionListener(e -> open());
        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> replayer.stop());
        stopBtn.setEnabled(false);
        startBtn.setEnabled(false);
    }

    void stop() { replayer.stop(); }

    private void open() {
        JFileChooser fc = new JFileChooser(new File("."));
        fc.setFileFilter(new FileNameExtensionFilter("Trafik kaydı (*.rec)", "rec"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        load(fc.getSelectedFile());
    }

    void load(File f) {
        try {
            recording = Recording.load(f.toPath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Kayıt açılamadı", JOptionPane.ERROR_MESSAGE);
            return;
        }
        groups.clear();
        List<String> names = new ArrayList<>();
        for (Endpoint ep : endpoints.get()) names.add(ep.name());
        for (Map.Entry<String, Integer> g : recording.groups().entrySet()) {
            Group gr = new Group(g.getKey(), g.getValue());
            gr.target = names.contains(gr.endpoint) ? gr.endpoint : NONE;
            gr.include = gr.dir.equals("TX") && !gr.target.equals(NONE); // varsayilan: gonderilenleri tekrar gonder
            groups.add(gr);
        }
        fileLabel.setText(f.getName() + "  ·  " + recording.entries.size() + " çerçeve  ·  "
                + String.format(Locale.ROOT, "%.1f s", recording.duration() / 1000.0));
        refreshTargets();
        model.fireTableDataChanged();
        startBtn.setEnabled(!replayer.isRunning());
    }

    private void refreshTargets() {
        targetEditor.removeAllItems();
        targetEditor.addItem(NONE);
        for (Endpoint ep : endpoints.get()) targetEditor.addItem(ep.name());
    }

    private void start() {
        if (recording == null) return;
        Map<String, Endpoint> mapping = new LinkedHashMap<>();
        for (Group g : groups) {
            if (!g.include || g.target.equals(NONE)) continue;
            for (Endpoint ep : endpoints.get()) {
                if (ep.name().equals(g.target)) {
                    if (ep.state() != Endpoint.State.RUNNING) {
                        JOptionPane.showMessageDialog(this, ep.name() + " çalışmıyor; önce başlatın.", "Tekrar oynat", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    mapping.put(g.key, ep);
                }
            }
        }
        if (mapping.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Gönderilecek grup seçilmedi (Dahil + hedef bağlantı).", "Tekrar oynat", JOptionPane.WARNING_MESSAGE);
            return;
        }
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        replayer.start(recording, mapping, SPEED_VALUES[speedBox.getSelectedIndex()], loop.isSelected(), keepPeer.isSelected(),
                new Replayer.Listener() {
                    @Override public void progress(int sent, int total, long elapsedMs, int errors) {
                        SwingUtilities.invokeLater(() -> {
                            progress.setMaximum(Math.max(1, total));
                            progress.setValue(total == 0 ? 0 : sent % (total + 1));
                            progress.setString(sent + " / " + total + "  ·  " + String.format(Locale.ROOT, "%.1f s", elapsedMs / 1000.0)
                                    + (errors > 0 ? "  ·  " + errors + " hata" : ""));
                        });
                    }

                    @Override public void finished(String summary) {
                        SwingUtilities.invokeLater(() -> {
                            progress.setString(summary);
                            startBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                        });
                    }
                });
    }

    private final class GroupModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Dahil", "Kayıttaki bağlantı", "Yön", "Çerçeve", "Gönderecek bağlantı"};

        @Override public int getRowCount() { return groups.size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 3 ? Integer.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) { return c == 0 || c == 4; }

        @Override public Object getValueAt(int r, int c) {
            Group g = groups.get(r);
            switch (c) {
                case 0: return g.include;
                case 1: return g.endpoint;
                case 2: return g.dir;
                case 3: return g.count;
                default: return g.target;
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Group g = groups.get(r);
            if (c == 0) g.include = (Boolean) v;
            else g.target = v == null ? NONE : v.toString();
            fireTableRowsUpdated(r, r);
        }
    }
}
