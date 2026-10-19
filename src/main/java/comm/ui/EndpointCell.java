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
    /** Trafik vurgusunun suresi (ms). */
    static final long ACTIVE_MS = 700;

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

    @Override public Dimension getPreferredSize() { return new Dimension(200, HEIGHT); }

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

        // ad (tam genislik; hata sayaci varsa sagda)
        Font base = getFont();
        Font nameFont = base.deriveFont(Font.BOLD, base.getSize2D() + 0.5f);
        g.setFont(nameFont);
        g.setColor(fg);
        FontMetrics nfm = g.getFontMetrics();
        g.drawString(ellipsize(ep.name(), nfm, w - 32 - 14), 32, 24);

        // tur rozeti + ozet (ikinci satir)
        Font chipFont = base.deriveFont(Font.BOLD, base.getSize2D() - 3.5f);
        g.setFont(chipFont);
        FontMetrics cfm = g.getFontMetrics();
        String chip = kindLabel(ep.config().kind);
        int cw = cfm.stringWidth(chip) + 10;
        int cy = 31;
        Color pc = Theme.protocol(family(ep.config().kind));
        g.setColor(selected && focused ? Theme.tint(java.awt.Color.WHITE, 55) : Theme.tint(pc, Theme.dark() ? 60 : 34));
        g.fillRoundRect(32, cy, cw, 15, 15, 15);
        g.setColor(selected && focused ? fg : pc);
        g.drawString(chip, 37, cy + (15 + cfm.getAscent() - cfm.getDescent()) / 2);

        Font small = base.deriveFont(base.getSize2D() - 1.5f);
        g.setFont(small);
        g.setColor(muted);
        FontMetrics sfm = g.getFontMetrics();
        int sx = 32 + cw + 6;
        g.drawString(ellipsize(summaryText(), sfm, w - sx - 14), sx, 43);

        // sayaclar; son ACTIVE_MS icinde trafik varsa ok renklenir
        Stats s = ep.stats();
        long now = System.currentTimeMillis();
        g.setFont(Theme.mono(small.getSize2D() - 0.5f));
        FontMetrics mfm = g.getFontMetrics();
        int x = 32;
        x = counter(g, mfm, x, "↓ " + s.rxFrames(), now - s.lastRx() < ACTIVE_MS ? Theme.rx() : null, muted);
        x = counter(g, mfm, x + 12, "↑ " + s.txFrames(), now - s.lastTx() < ACTIVE_MS ? Theme.tx() : null, muted);
        g.setColor(muted);
        g.drawString(ep.peers().size() + " eş", x + 12, 57);
        if (s.errors() > 0) {
            String e = "⚠ " + s.errors() + " hata";
            int ew = g.getFontMetrics().stringWidth(e);
            g.setColor(selected && focused ? fg : Theme.err());
            g.drawString(e, w - ew - 14, 57);
        }
        g.dispose();
    }

    /** Bir sayac yazar; etkinse arkasina yumusak bir vurgu cizer. Bitis x'ini dondurur. */
    private int counter(Graphics2D g, FontMetrics fm, int x, String text, Color active, Color muted) {
        int w = fm.stringWidth(text);
        if (active != null && !selected) {
            g.setColor(Theme.tint(active, Theme.dark() ? 70 : 40));
            g.fillRoundRect(x - 4, 57 - fm.getAscent() - 1, w + 8, fm.getAscent() + fm.getDescent() + 2, 8, 8);
        }
        g.setColor(active != null && !selected ? active : muted);
        g.drawString(text, x, 57);
        return x + w;
    }

    /** Rozette tur yazdigi icin ozetin "TCP istemci → " gibi tur onekini atar. */
    private String summaryText() {
        String d = ep.config().describe();
        int arrow = d.indexOf(" → ");
        String[] prefixes = {"TCP sunucu ", "TCP istemci ", "UDP ", "Modbus slave ", "Modbus master ", "SNMP agent ",
                "SNMP manager ", "WebSocket sunucu ", "WebSocket istemci "};
        for (String p : prefixes) {
            if (d.startsWith(p)) return d.substring(p.length());
        }
        return arrow > 0 && arrow < 20 ? d.substring(arrow + 1) : d;
    }

    private static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > max) end--;
        return s.substring(0, end) + dots;
    }
}
