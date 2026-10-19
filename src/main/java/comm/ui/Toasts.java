package comm.ui;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Pencerenin sag altinda kisa sureli bildirimler. Tiklayinca kapanir; ayni anahtar icin seyreltilir. */
final class Toasts {
    enum Kind {
        INFO(Theme::accent), OK(Theme::ok), WARN(Theme::warn), ERROR(Theme::err);

        final Supplier<Color> color;

        Kind(Supplier<Color> c) { color = c; }
    }

    private static final int MAX = 4;
    private static final int TOAST_W = 360;
    private static final int GAP = 8;
    private static final int MARGIN = 16;
    /** Durum cubugu yuksekligi kadar yukarida durur. */
    private static final int BOTTOM = 40;

    private final JLayeredPane layer;
    private final List<Toast> shown = new ArrayList<>();
    private final Map<String, Long> lastByKey = new HashMap<>();

    Toasts(JRootPane root) {
        layer = root.getLayeredPane();
        layer.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { layout(); }
        });
    }

    void show(Kind kind, String title, String text) {
        show(kind, title, text, null, 0);
    }

    /**
     * @param key      ayni anahtarli bildirimler minGapMs icinde tekrar gosterilmez (null: her zaman)
     * @param minGapMs seyreltme suresi
     */
    void show(Kind kind, String title, String text, String key, long minGapMs) {
        long now = System.currentTimeMillis();
        if (key != null) {
            Long last = lastByKey.get(key);
            if (last != null && now - last < minGapMs) return;
            lastByKey.put(key, now);
        }
        while (shown.size() >= MAX) remove(shown.get(0));
        Toast t = new Toast(kind, title, text);
        shown.add(t);
        layer.add(t, JLayeredPane.POPUP_LAYER);
        layout();
        Timer timer = new Timer(kind == Kind.ERROR ? 7000 : 4000, e -> remove(t));
        timer.setRepeats(false);
        timer.start();
    }

    private void remove(Toast t) {
        if (!shown.remove(t)) return;
        layer.remove(t);
        layout();
        layer.repaint();
    }

    private void layout() {
        int y = layer.getHeight() - BOTTOM;
        for (int i = shown.size() - 1; i >= 0; i--) {
            Toast t = shown.get(i);
            Dimension d = t.getPreferredSize();
            y -= d.height;
            t.setBounds(layer.getWidth() - d.width - MARGIN, y, d.width, d.height);
            y -= GAP;
        }
        layer.revalidate();
        layer.repaint();
    }

    /** Tek bildirim karti: solda renkli serit, baslik ve en fazla iki satir metin. */
    private final class Toast extends JComponent {
        private static final long serialVersionUID = 1L;
        private final Kind kind;
        private final String title;
        private final List<String> lines;

        Toast(Kind kind, String title, String text) {
            this.kind = kind;
            this.title = title;
            setFont(javax.swing.UIManager.getFont("Label.font"));
            lines = wrap(text == null ? "" : text, getFontMetrics(getFont()), TOAST_W - 40, 3);
            setToolTipText(text != null && text.length() > 60 ? text : null);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { remove(Toast.this); }
            });
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(TOAST_W, 22 + fm.getHeight() * (1 + lines.size()));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth();
            int h = getHeight();
            // golge
            for (int i = 0; i < 4; i++) {
                g.setColor(new Color(0, 0, 0, Theme.dark() ? 40 - i * 9 : 18 - i * 4));
                g.fillRoundRect(i, i + 2, w - 2 * i, h - 2 * i - 1, 14, 14);
            }
            g.setColor(Theme.surface());
            g.fillRoundRect(2, 1, w - 4, h - 4, 12, 12);
            g.setColor(Theme.separator());
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(2, 1, w - 5, h - 5, 12, 12);
            Color c = kind.color.get();
            g.setColor(c);
            g.fillRoundRect(8, 10, 4, h - 22, 4, 4);

            FontMetrics fm = g.getFontMetrics(getFont());
            int x = 22;
            int y = 10 + fm.getAscent();
            g.setFont(getFont().deriveFont(Font.BOLD));
            g.setColor(javax.swing.UIManager.getColor("Label.foreground"));
            g.drawString(title, x, y);
            g.setFont(getFont());
            g.setColor(Theme.muted());
            for (String l : lines) {
                y += fm.getHeight();
                g.drawString(l, x, y);
            }
            g.dispose();
        }
    }

    static List<String> wrap(String text, FontMetrics fm, int max, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) continue;
            String cand = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(cand) <= max || line.length() == 0) {
                line.setLength(0);
                line.append(cand);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, cut.get(maxLines - 1) + " …");
            out = cut;
        }
        // tek kelime cok uzunsa kirp
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (fm.stringWidth(s) > max) {
                int end = s.length();
                while (end > 1 && fm.stringWidth(s.substring(0, end) + "…") > max) end--;
                out.set(i, s.substring(0, end) + "…");
            }
        }
        return out;
    }
}
