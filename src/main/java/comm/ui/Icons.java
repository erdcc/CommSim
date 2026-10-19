package comm.ui;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/** Kod icinde cizilen, temaya uyan 16 px vektor ikonlar. */
final class Icons {
    private interface Painter {
        void paint(Graphics2D g);
    }

    private static final class VIcon implements Icon {
        private final Painter painter;
        private final Supplier<Color> color;
        private final int size;

        VIcon(int size, Supplier<Color> color, Painter painter) {
            this.size = size;
            this.color = color;
            this.painter = painter;
        }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size / 16.0, size / 16.0);
            Color col = color.get();
            if (c != null && !c.isEnabled()) col = Theme.tint(col, 90);
            g.setColor(col);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            painter.paint(g);
            g.dispose();
        }

        @Override public int getIconWidth() { return size; }

        @Override public int getIconHeight() { return size; }
    }

    private static Color fg() {
        Color c = UIManager.getColor("Label.foreground");
        return c == null ? Color.DARK_GRAY : c;
    }

    private static Icon of(Supplier<Color> color, Painter p) { return new VIcon(16, color, p); }

    private static Icon of(Painter p) { return of(Icons::fg, p); }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
    }

    private static Path2D path(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }

    static final Icon ADD = of(Theme::accent, g -> {
        line(g, 8, 3, 8, 13);
        line(g, 3, 8, 13, 8);
    });

    static final Icon PLAY = of(Theme::ok, g -> {
        Path2D p = path(5, 3, 13, 8, 5, 13);
        p.closePath();
        g.fill(p);
    });

    static final Icon STOP = of(Theme::err, g -> g.fill(new RoundRectangle2D.Double(4, 4, 8, 8, 2, 2)));

    static final Icon STOP_ALL = of(Theme::err, g -> {
        g.draw(new RoundRectangle2D.Double(2.5, 2.5, 11, 11, 3, 3));
        g.fill(new RoundRectangle2D.Double(5.5, 5.5, 5, 5, 1.5, 1.5));
    });

    static final Icon EDIT = of(g -> {
        g.draw(path(3, 13, 3.6, 10.4, 10.8, 3.2, 12.8, 5.2, 5.6, 12.4, 3, 13));
        line(g, 9.6, 4.4, 11.6, 6.4);
    });

    static final Icon COPY = of(g -> {
        g.draw(new RoundRectangle2D.Double(5.5, 5.5, 8, 8, 3, 3));
        g.draw(path(10.5, 3.5, 10.5, 3, 3, 3, 3, 10.5, 3.5, 10.5));
    });

    static final Icon DELETE = of(g -> {
        line(g, 3, 4.5, 13, 4.5);
        g.draw(path(6, 4.5, 6.5, 2.5, 9.5, 2.5, 10, 4.5));
        g.draw(path(4.5, 4.5, 5.3, 13.5, 10.7, 13.5, 11.5, 4.5));
    });

    static final Icon RELOAD = of(g -> {
        g.draw(new java.awt.geom.Arc2D.Double(3, 3, 10, 10, 60, 280, java.awt.geom.Arc2D.OPEN));
        g.draw(path(11.5, 1.8, 11.4, 4.4, 8.8, 4.2));
    });

    static final Icon SCHEMA = of(g -> {
        g.draw(new RoundRectangle2D.Double(2.5, 2.5, 11, 11, 3, 3));
        line(g, 5, 6, 11, 6);
        line(g, 5, 8.5, 9.5, 8.5);
        line(g, 5, 11, 8, 11);
    });

    static final Icon SAVE = of(g -> {
        g.draw(path(3, 3, 11, 3, 13, 5, 13, 13, 3, 13, 3, 3));
        g.draw(path(5.5, 3, 5.5, 6, 10, 6, 10, 3));
    });

    static final Icon OPEN = of(g -> g.draw(path(2.5, 12.5, 2.5, 3.5, 6.5, 3.5, 8, 5, 13.5, 5, 13.5, 12.5, 2.5, 12.5)));

    static final Icon SEND = of(Theme::accent, g -> {
        Path2D p = path(2.5, 7.5, 13.5, 2.5, 9, 13.5, 7.5, 9, 2.5, 7.5);
        g.draw(p);
        line(g, 7.5, 9, 13.5, 2.5);
    });

    static final Icon CLEAR = of(g -> {
        g.draw(new Ellipse2D.Double(2.5, 2.5, 11, 11));
        line(g, 5.5, 10.5, 10.5, 5.5);
    });

    static final Icon EXPORT = of(g -> {
        line(g, 8, 2.5, 8, 10);
        g.draw(path(5, 5.5, 8, 2.5, 11, 5.5));
        g.draw(path(3, 9, 3, 13.5, 13, 13.5, 13, 9));
    });

    static final Icon THEME = of(g -> {
        g.draw(new Ellipse2D.Double(2.5, 2.5, 11, 11));
        g.fill(new java.awt.geom.Arc2D.Double(2.5, 2.5, 11, 11, 90, 180, java.awt.geom.Arc2D.PIE));
    });

    static final Icon REPEAT = of(Theme::accent, g -> {
        g.draw(path(3, 7, 3, 5, 12, 5));
        g.draw(path(10, 3, 12, 5, 10, 7));
        g.draw(path(13, 9, 13, 11, 4, 11));
        g.draw(path(6, 13, 4, 11, 6, 9));
    });

    static final Icon SEARCH = of(Theme::muted, g -> {
        g.draw(new Ellipse2D.Double(2.5, 2.5, 8, 8));
        line(g, 9.5, 9.5, 13.5, 13.5);
    });

    static final Icon HELP = of(g -> {
        g.draw(new Ellipse2D.Double(2.5, 2.5, 11, 11));
        g.draw(new java.awt.geom.Arc2D.Double(6, 4.8, 4, 3.6, 160, -250, java.awt.geom.Arc2D.OPEN));
        line(g, 8, 8.6, 8, 9.6);
        g.fill(new Ellipse2D.Double(7.2, 11, 1.6, 1.6));
    });

    static final Icon STAR = of(Theme::warn, g -> {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? 6.2 : 2.7;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double x = 8 + r * Math.cos(a);
            double y = 8.6 + r * Math.sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        g.draw(p);
    });

    static final Icon COMMAND = of(g -> {
        g.draw(new java.awt.geom.RoundRectangle2D.Double(2.5, 3.5, 11, 9, 3, 3));
        g.draw(path(5, 6.5, 7, 8, 5, 9.5));
        line(g, 8.5, 10, 11, 10);
    });

    /** Vurgulu (mavi zeminli) butonlar icin ayni ikonun beyaz cizimi. */
    static Icon onAccent(Icon icon) {
        if (!(icon instanceof VIcon)) return icon;
        VIcon v = (VIcon) icon;
        return new VIcon(v.size, () -> Color.WHITE, v.painter);
    }

    private Icons() { }
}
