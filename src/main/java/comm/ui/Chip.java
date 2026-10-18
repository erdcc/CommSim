package comm.ui;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

/** Yuvarlak, renkli zeminli kucuk etiket (durum ya da tur rozeti). */
final class Chip extends JLabel {
    private static final long serialVersionUID = 1L;
    private Supplier<Color> color = Theme::muted;

    Chip(String text) {
        super(text, SwingConstants.CENTER);
        setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 2f));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8));
    }

    void set(String text, Supplier<Color> c) {
        color = c;
        setText(text);
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(d.height, 18));
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = color.get();
        g.setColor(Theme.tint(c, Theme.dark() ? 60 : 36));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g.dispose();
        setForeground(c);
        super.paintComponent(g0);
    }
}
