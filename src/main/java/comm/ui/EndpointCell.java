package comm.ui;

import comm.core.Endpoint;
import comm.core.EndpointConfig.Kind;
import comm.core.Stats;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Baglanti listesindeki kart: durum noktasi, ad, tur rozeti, adres ozeti ve canli sayaclar. */
final class EndpointCell extends JComponent implements ListCellRenderer<Endpoint> {
    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 66;

    private Endpoint ep;
    private boolean selected;
    private boolean focused;

    @Override public Component getListCellRendererComponent(JList<? extends Endpoint> list, Endpoint value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        ep = value;
        selected = isSelected;
        focused = list.hasFocus();
        setFont(list.getFont());
        return this;
    }

    @Override public Dimension getPreferredSize() { return new Dimension(260, HEIGHT); }

    static String kindLabel(Kind k) {
        switch (k) {
            case TCP_SERVER: return "TCP SUNUCU";
            case TCP_CLIENT: return "TCP İSTEMCİ";
            case UDP: return "UDP";
            case MODBUS_SLAVE: return "MODBUS SLAVE";
            case MODBUS_MASTER: return "MODBUS MASTER";
            case SNMP_AGENT: return "SNMP AGENT";
            case WS_SERVER: return "WS SUNUCU";
            case WS_CLIENT: return "WS İSTEMCİ";
            default: return "SNMP MANAGER";
        }
    }

    static String family(Kind k) {
        if (k.isModbus()) return "MB";
        if (k.isSnmp()) return "SNMP";
        if (k.isWebSocket()) return "WS";
        return k == Kind.UDP ? "UDP" : "TCP";
    }

    @Override protected void paintComponent(Graphics g0) {
        if (ep == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        int w = getWidth();
        int h = getHeight();

        Color fg = UIManager.getColor("List.foreground");
        Color muted = Theme.muted();
        if (selected) {
            Color bg = UIManager.getColor(focused ? "List.selectionBackground" : "List.selectionInactiveBackground");
            g.setColor(bg);
            g.fillRoundRect(4, 2, w - 8, h - 4, 10, 10);
            fg = UIManager.getColor(focused ? "List.selectionForeground" : "List.selectionInactiveForeground");
            muted = Theme.tint(fg, 175);
        } else {
            g.setColor(Theme.separator());
            g.drawLine(14, h - 1, w - 14, h - 1);
        }

        // durum noktasi
        Color stateColor = Theme.state(ep.state());
        g.setColor(selected && focused ? Theme.tint(java.awt.Color.WHITE, 90) : Theme.tint(stateColor, 70));
        g.fillOval(13, 13, 12, 12);
        g.setColor(stateColor);
        g.fillOval(15, 15, 8, 8);

        // tur rozeti (sagda)
        Font base = getFont();
        Font chipFont = base.deriveFont(Font.BOLD, base.getSize2D() - 3f);
        g.setFont(chipFont);
        FontMetrics cfm = g.getFontMetrics();
        String chip = kindLabel(ep.config().kind);
        int cw = cfm.stringWidth(chip) + 12;
        int cx = w - cw - 12;
        Color pc = Theme.protocol(family(ep.config().kind));
        g.setColor(selected && focused ? Theme.tint(java.awt.Color.WHITE, 55) : Theme.tint(pc, Theme.dark() ? 60 : 34));
        g.fillRoundRect(cx, 10, cw, 17, 17, 17);
        g.setColor(selected && focused ? fg : pc);
        g.drawString(chip, cx + 6, 10 + (17 + cfm.getAscent() - cfm.getDescent()) / 2);

        // ad
        Font nameFont = base.deriveFont(Font.BOLD, base.getSize2D() + 0.5f);
        g.setFont(nameFont);
        g.setColor(fg);
        FontMetrics nfm = g.getFontMetrics();
        g.drawString(ellipsize(ep.name(), nfm, cx - 36), 32, 24);

        // ozet
        Font small = base.deriveFont(base.getSize2D() - 1.5f);
        g.setFont(small);
        g.setColor(muted);
        FontMetrics sfm = g.getFontMetrics();
        g.drawString(ellipsize(ep.config().describe(), sfm, w - 44), 32, 41);

        // sayaclar
        Stats s = ep.stats();
        String counters = "↓ " + s.rxFrames() + "   ↑ " + s.txFrames() + "   " + ep.peers().size() + " eş";
        g.setFont(Theme.mono(small.getSize2D() - 0.5f));
        g.drawString(counters, 32, 57);
        if (s.errors() > 0) {
            String e = "⚠ " + s.errors() + " hata";
            int ew = g.getFontMetrics().stringWidth(e);
            g.setColor(selected && focused ? fg : Theme.err());
            g.drawString(e, w - ew - 14, 57);
        }
        g.dispose();
    }

    private static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > max) end--;
        return s.substring(0, end) + dots;
    }
}
