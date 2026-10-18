package harita;

import com.bbn.openmap.Layer;
import com.bbn.openmap.MapBean;
import com.bbn.openmap.event.ProjectionEvent;
import com.bbn.openmap.layer.GraticuleLayer;
import com.bbn.openmap.layer.OMGraphicHandlerLayer;
import com.bbn.openmap.layer.shape.ShapeLayer;
import com.bbn.openmap.omGraphics.OMGraphicList;
import com.bbn.openmap.omGraphics.OMWarpingImage;
import com.bbn.openmap.proj.Projection;
import com.bbn.openmap.proj.coords.LatLonGCT;
import com.bbn.openmap.util.DataBounds;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * OpenMap tabanli harita paneli (internet gerekmez).
 * Haritaya tiklayarak platform/radar node'u eklenir; degisiklikler NodeListener ile disari verilir.
 * <p>
 * {@code new MapPanel()} pakete gomulu harita verisini kullanir, ek dosya gerekmez.
 * {@code new MapPanel(dir)} ise verileri verilen klasorden okur.
 */
public class MapPanel extends JPanel {
    private static final float MIN_SCALE = 1_000f;
    private static final float MAX_SCALE = 150_000_000f;
    private static final int HIT_RADIUS = 11;

    private final MapBean map = new MapBean();
    private final ObjectLayer objectLayer = new ObjectLayer();
    private final List<Node> objects = new ArrayList<>();
    private Node selected;
    private int nextId = 1;
    private Consumer<Node> selectionListener = o -> { };

    public enum Change { ADDED, MOVED, EDITED, DELETED }

    /** Haritada node eklenince/tasininca/duzenlenince/silinince cagrilir (EDT). */
    public interface NodeListener {
        void onNode(Node n, Change change);
    }

    private NodeListener nodeListener = (n, c) -> { };

    private final JToggleButton addPlatform = new JToggleButton("Platform ekle");
    private final JToggleButton addRadar = new JToggleButton("Radar ekle");
    private final JButton delete = new JButton("Seçiliyi sil");
    private final JLabel cursorInfo = new JLabel("Sürükle: kaydır / objeyi taşı · Tekerlek: yakınlaştır");

    private final JTextField fId = new JTextField(6);
    private final JTextField fName = new JTextField(10);
    private final JComboBox<String> fIdentity = new JComboBox<>(Node.IDENTITIES.toArray(new String[0]));
    private final JTextField fAlt = new JTextField(8);
    private final JTextField fSpeed = new JTextField(8);
    private final JTextField fHeading = new JTextField(8);
    private final JTextField fLat = new JTextField(10);
    private final JTextField fLon = new JTextField(10);
    private final JButton apply = new JButton("Uygula");
    private final JPanel props = new JPanel(new BorderLayout(4, 4));
    private final javax.swing.border.TitledBorder propsBorder = BorderFactory.createTitledBorder("Seçili node");

    /** Jar'a gomulu harita verisiyle kurar (uydu goruntusu + ulke sinirlari). Ek dosya gerekmez. */
    public MapPanel() {
        this(BundledData.dirOrNull());
    }

    /**
     * Harita verisini verilen klasorden okur: bluemarble.jpg, dcwpo-browse.shp, dcwpo-browse.ssx.
     * Dosyalar yoksa ilgili katman atlanir. {@code null} verilirse verisiz (duz zemin) acilir.
     */
    public MapPanel(Path dataDir) {
        super(new BorderLayout());
        map.setBackgroundColor(new Color(0xB8DCF0));
        map.setCenter(39.9334, 32.8597);
        map.setScale(12_000_000f);
        map.add(objectLayer);          // OpenMap'te once eklenen katman ustte cizilir
        addBackgroundLayers(dataDir);

        add(buildToolbar(), BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);
        installMouse();
        refreshProps();
    }

    public void setSelectionListener(Consumer<Node> l) { selectionListener = l; }
    public void setNodeListener(NodeListener l) { nodeListener = l; }

    /** Haritadaki tum node'larin anlik kopyasi. */
    public List<Node> nodes() { return new ArrayList<>(objects); }

    /** Node durumu (ornegin gate) degisti: cizimi ve secili node basligini yeniler. */
    public void refresh() {
        refreshProps();
        objectLayer.repaint();
    }
    public Node getSelected() { return selected; }

    // ---------- kurulum ----------

    private void addBackgroundLayers(Path dataDir) {
        Path img = dataDir == null ? null : dataDir.resolve("bluemarble.jpg");
        boolean satellite = img != null && Files.exists(img);

        Properties g = new Properties();
        for (String k : new String[] {"10DegreeColor", "5DegreeColor", "1DegreeColor", "Below1DegreeColor"})
            g.put("grid." + k, satellite ? "50FFFFFF" : "60808080");
        g.put("grid.equatorColor", satellite ? "80FFFFFF" : "90808080");
        g.put("grid.textColor", satellite ? "FFFFFFFF" : "FF404040");
        GraticuleLayer grid = new GraticuleLayer();
        grid.setProperties("grid", g);
        map.add(grid);

        Path shp = dataDir == null ? null : dataDir.resolve("dcwpo-browse.shp");
        if (shp != null && Files.exists(shp)) {
            Properties p = new Properties();
            p.put("world.prettyName", "Dünya");
            p.put("world.shapeFile", shp.toAbsolutePath().toString());
            Path ssx = dataDir.resolve("dcwpo-browse.ssx");
            if (Files.exists(ssx)) p.put("world.spatialIndex", ssx.toAbsolutePath().toString());
            // uydu goruntusu varsa sinirlar sadece cizgi olarak ustte kalir
            p.put("world.lineColor", satellite ? "C0FFFFFF" : "FF888888");
            p.put("world.fillColor", satellite ? "00000000" : "FFF2EFE4");
            ShapeLayer world = new ShapeLayer();
            world.setProperties("world", p);
            map.add(world);
        }

        if (satellite) {
            try {
                map.add(new SatelliteLayer(ImageIO.read(img.toFile())));
            } catch (IOException e) {
                System.err.println("Uydu görüntüsü okunamadı: " + e.getMessage());
            }
        }
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(addPlatform);
        bar.add(addRadar);
        bar.add(delete);
        addPlatform.addActionListener(e -> { if (addPlatform.isSelected()) addRadar.setSelected(false); });
        addRadar.addActionListener(e -> { if (addRadar.isSelected()) addPlatform.setSelected(false); });
        delete.addActionListener(e -> deleteSelected());
        return bar;
    }

    private JComponent buildSouth() {
        JPanel grid = new JPanel(new GridLayout(0, 4, 6, 3));
        grid.add(new JLabel("nodeId")); grid.add(fId);
        grid.add(new JLabel("name"));    grid.add(fName);
        grid.add(new JLabel("identity")); grid.add(fIdentity);
        grid.add(new JLabel("altitude")); grid.add(fAlt);
        grid.add(new JLabel("speed"));   grid.add(fSpeed);
        grid.add(new JLabel("heading")); grid.add(fHeading);
        grid.add(new JLabel("lat"));     grid.add(fLat);
        grid.add(new JLabel("lon"));     grid.add(fLon);
        apply.addActionListener(e -> applyProps());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(apply);
        props.setBorder(propsBorder);
        props.add(grid, BorderLayout.CENTER);
        props.add(b, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout());
        south.add(props, BorderLayout.CENTER);
        cursorInfo.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        south.add(cursorInfo, BorderLayout.SOUTH);
        return south;
    }

    // ---------- fare ----------

    private void installMouse() {
        MouseAdapter ma = new MouseAdapter() {
            private Point last;
            private Point pressed;
            private boolean moved;
            private Node dragging;

            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                last = pressed = e.getPoint();
                moved = false;
                dragging = hit(e.getX(), e.getY());
                if (dragging != null) select(dragging);
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                if (e.getPoint().distance(pressed) > 3) moved = true;
                if (!moved) return;
                if (dragging != null) {
                    moveObject(dragging, e.getX(), e.getY());
                } else {
                    pan(e.getX() - last.x, e.getY() - last.y);
                }
                last = e.getPoint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (pressed == null) return;
                if (!moved && dragging == null) {
                    if (addType() != null) addObjectAt(e.getX(), e.getY());
                    else select(null);
                }
                last = pressed = null;
                dragging = null;
            }

            @Override public void mouseMoved(MouseEvent e) {
                Projection p = map.getProjection();
                if (p == null) return;
                Point2D g = p.inverse(e.getX(), e.getY());
                cursorInfo.setText(String.format("lat %.5f   lon %.5f   ölçek 1:%,d",
                        g.getY(), g.getX(), (long) p.getScale()));
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoom(e.getX(), e.getY(), Math.pow(1.25, e.getPreciseWheelRotation()));
            }
        };
        map.addMouseListener(ma);
        map.addMouseMotionListener(ma);
        map.addMouseWheelListener(ma);
    }

    private void pan(int dx, int dy) {
        Projection p = map.getProjection();
        Point2D c = p.inverse(map.getWidth() / 2.0 - dx, map.getHeight() / 2.0 - dy);
        map.setCenter(clampLat(c.getY()), c.getX());
    }

    /** factor > 1: uzaklas. Imlecin altindaki nokta sabit kalir. */
    private void zoom(int x, int y, double factor) {
        Projection p = map.getProjection();
        Point2D g = p.inverse(x, y);
        float ns = (float) Math.max(MIN_SCALE, Math.min(MAX_SCALE, p.getScale() * factor));
        map.setScale(ns);
        Point2D now = map.getProjection().forward(g);
        Point2D c = map.getProjection().inverse(map.getWidth() / 2.0 - (x - now.getX()),
                map.getHeight() / 2.0 - (y - now.getY()));
        map.setCenter(clampLat(c.getY()), c.getX());
    }

    // ---------- objeler ----------

    private Node hit(int x, int y) {
        Projection p = map.getProjection();
        if (p == null) return null;
        for (int i = objects.size() - 1; i >= 0; i--) {
            Node o = objects.get(i);
            if (p.forward(o.lat, o.lon).distance(x, y) <= HIT_RADIUS) return o;
        }
        return null;
    }

    /** Eklenecek tur; ikisi de kapaliysa null. */
    private Node.Type addType() {
        return addPlatform.isSelected() ? Node.Type.PLATFORM : addRadar.isSelected() ? Node.Type.RADAR : null;
    }

    private void addObjectAt(int x, int y) {
        Node.Type type = addType();
        Point2D g = map.getProjection().inverse(x, y);
        int id = nextId++;
        String prefix = type == Node.Type.RADAR ? "RDR" : "PLT";
        Node o = new Node(type, id, prefix + "-" + id, clampLat(g.getY()), clampLon(g.getX()));
        objects.add(o);
        select(o);
        nodeListener.onNode(o, Change.ADDED);
    }

    private void moveObject(Node o, int x, int y) {
        Point2D g = map.getProjection().inverse(x, y);
        o.lat = clampLat(g.getY());
        o.lon = clampLon(g.getX());
        refreshProps();
        objectLayer.repaint();
        selectionListener.accept(o);
        nodeListener.onNode(o, Change.MOVED);
    }

    private void deleteSelected() {
        if (selected == null) return;
        Node n = selected;
        objects.remove(n);
        select(null);
        nodeListener.onNode(n, Change.DELETED);
    }

    private void select(Node o) {
        selected = o;
        refreshProps();
        objectLayer.repaint();
        if (o != null) selectionListener.accept(o);
    }

    private void refreshProps() {
        boolean on = selected != null;
        for (JComponent c : new JComponent[] {fId, fName, fIdentity, fAlt, fSpeed, fHeading, fLat, fLon, apply})
            c.setEnabled(on);
        delete.setEnabled(on);
        propsBorder.setTitle(on ? "Seçili node: " + selected.type
                + (selected.type == Node.Type.PLATFORM ? " · gate: " + gateText(selected.gate) : "") : "Seçili node");
        props.repaint();
        if (!on) {
            for (JTextField f : new JTextField[] {fId, fName, fAlt, fSpeed, fHeading, fLat, fLon}) f.setText("");
            return;
        }
        fId.setText(String.valueOf(selected.nodeId));
        fName.setText(selected.name);
        fIdentity.setSelectedItem(selected.identity);
        fAlt.setText(fmt(selected.altitude));
        fSpeed.setText(fmt(selected.speed));
        fHeading.setText(fmt(selected.heading));
        fLat.setText(fmt(selected.lat));
        fLon.setText(fmt(selected.lon));
    }

    private void applyProps() {
        if (selected == null) return;
        try {
            int id = Integer.parseInt(fId.getText().trim());
            double alt = num(fAlt), speed = num(fSpeed), heading = num(fHeading);
            double lat = num(fLat), lon = num(fLon);
            selected.nodeId = id;
            selected.name = fName.getText();
            selected.identity = String.valueOf(fIdentity.getSelectedItem());
            selected.altitude = alt;
            selected.speed = speed;
            selected.heading = heading;
            selected.lat = clampLat(lat);
            selected.lon = clampLon(lon);
            refreshProps();
            objectLayer.repaint();
            selectionListener.accept(selected);
            nodeListener.onNode(selected, Change.EDITED);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Geçersiz sayı: " + e.getMessage(), "Hata",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String gateText(Node.GateState s) {
        switch (s) {
            case OFF: return "kapalı";
            case CONNECTING: return "bağlanıyor";
            case CONNECTED: return "bağlı";
            default: return "hata";
        }
    }

    /** Sayiyi gereksiz sifirlar olmadan yazar (en fazla 6 ondalik). */
    private static String fmt(double d) {
        return new java.math.BigDecimal(String.format(java.util.Locale.ROOT, "%.6f", d))
                .stripTrailingZeros().toPlainString();
    }

    private static double num(JTextField f) { return Double.parseDouble(f.getText().trim().replace(',', '.')); }
    private static double clampLat(double v) { return Math.max(-85, Math.min(85, v)); }
    private static double clampLon(double v) { return Math.max(-180, Math.min(180, v)); }

    // ---------- objeleri ciziyor ----------

    private final class ObjectLayer extends Layer {
        ObjectLayer() { setName("Objeler"); }

        @Override public void projectionChanged(ProjectionEvent e) {
            setProjection(e);
            repaint();
        }

        @Override public void paint(Graphics g0) {
            Projection p = getProjection();
            if (p == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (Node o : objects) {
                Point2D pt = p.forward(o.lat, o.lon);
                int x = (int) Math.round(pt.getX()), y = (int) Math.round(pt.getY());
                g.setStroke(new BasicStroke(1f));
                if (o.type == Node.Type.RADAR) {
                    Polygon d = new Polygon(new int[] {x, x + 9, x, x - 9}, new int[] {y - 9, y, y + 9, y}, 4);
                    g.setColor(color(o.identity));
                    g.fillPolygon(d);
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(o == selected ? 3f : 1f));
                    g.drawPolygon(d);
                } else {
                    double h = Math.toRadians(o.heading);
                    g.setColor(Color.DARK_GRAY);
                    g.drawLine(x, y, x + (int) Math.round(Math.sin(h) * 22), y - (int) Math.round(Math.cos(h) * 22));
                    g.setColor(color(o.identity));
                    g.fillOval(x - 7, y - 7, 14, 14);
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(o == selected ? 3f : 1f));
                    g.drawOval(x - 7, y - 7, 14, 14);
                    if (o.gate != Node.GateState.OFF) {   // gate durum halkasi
                        g.setColor(o.gate == Node.GateState.CONNECTED ? new Color(0x2ECC40)
                                : o.gate == Node.GateState.CONNECTING ? new Color(0xFF9500)
                                : new Color(0xFF3B30));
                        g.setStroke(new BasicStroke(2.5f));
                        g.drawOval(x - 12, y - 12, 24, 24);
                    }
                }
                g.setStroke(new BasicStroke(1f));
                String label = o.name + " [" + o.nodeId + "]";
                g.setColor(Color.BLACK);
                g.drawString(label, x + 12, y - 8);
                g.setColor(Color.WHITE);
                g.drawString(label, x + 11, y - 9);
            }
            g.dispose();
        }
    }

    /** Tum dunyayi kaplayan (enlem/boylam) uydu goruntusu; gecerli projeksiyona gore cizilir. */
    private static final class SatelliteLayer extends OMGraphicHandlerLayer {
        SatelliteLayer(BufferedImage image) {
            setName("Uydu");
            OMGraphicList list = new OMGraphicList();
            list.add(new OMWarpingImage(image, LatLonGCT.INSTANCE, new DataBounds(-180, -90, 180, 90)));
            setList(list);
        }

        @Override public synchronized OMGraphicList prepare() {
            OMGraphicList list = getList();
            Projection p = getProjection();
            if (list != null && p != null) list.generate(p);
            return list;
        }
    }

    private static Color color(String identity) {
        switch (identity) {
            case "FRIEND": return new Color(0x4A90E2);
            case "HOSTILE": return new Color(0xE24A4A);
            case "NEUTRAL": return new Color(0x5CB85C);
            case "PENDING": return new Color(0xAAAAAA);
            default: return new Color(0xF0C040);
        }
    }
}
