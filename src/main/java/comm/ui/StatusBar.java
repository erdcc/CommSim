package comm.ui;

import comm.core.Endpoint;
import comm.core.Stats;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/** Alt cubuk: solda gecici mesajlar, sagda secili baglantinin ozeti ve genel durum. */
final class StatusBar extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int MESSAGE_MS = 8000;

    private final JLabel message = new JLabel(" ");
    private final Chip state = new Chip(" ");
    private final JLabel name = new JLabel(" ");
    private final JLabel rx = new JLabel(" ");
    private final JLabel tx = new JLabel(" ");
    private final JLabel errors = new JLabel(" ");
    private final JLabel overall = new JLabel(" ");
    private final Timer clear = new Timer(MESSAGE_MS, e -> message.setText(" "));

    StatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.separator()),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        for (JLabel l : new JLabel[]{rx, tx, errors, overall}) UiKit.style(l, "small");
        right.add(state);
        right.add(name);
        right.add(rx);
        right.add(tx);
        right.add(errors);
        right.add(overall);
        message.setForeground(Theme.muted());
        add(message, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        clear.setRepeats(false);
    }

    @Override public void updateUI() {
        super.updateUI();
        if (message != null) {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.separator()),
                    BorderFactory.createEmptyBorder(4, 12, 4, 12)));
            message.setForeground(Theme.muted());
        }
    }

    /** Birkac saniye gorunen bilgi mesaji. */
    void setText(String text) {
        message.setText(text == null || text.isEmpty() ? " " : text);
        clear.restart();
    }

    void update(Endpoint ep, List<Endpoint> all) {
        int running = 0;
        for (Endpoint e : all) if (e.state() == Endpoint.State.RUNNING) running++;
        overall.setText(running + "/" + all.size() + " çalışıyor");
        overall.setForeground(Theme.muted());
        boolean on = ep != null;
        state.setVisible(on);
        name.setVisible(on);
        rx.setVisible(on);
        tx.setVisible(on);
        errors.setVisible(on);
        if (!on) return;
        Stats s = ep.stats();
        state.set(ep.state().label, () -> Theme.state(ep.state()));
        name.setText(ep.name());
        rx.setText("↓ " + s.rxFrames() + " · " + StatsPanel.bytes(s.rxBytes()));
        rx.setForeground(Theme.rx());
        tx.setText("↑ " + s.txFrames() + " · " + StatsPanel.bytes(s.txBytes()));
        tx.setForeground(Theme.tx());
        errors.setText(s.errors() == 0 ? "hata yok" : "⚠ " + s.errors() + " hata");
        errors.setForeground(s.errors() == 0 ? Theme.muted() : Theme.err());
        errors.setToolTipText(s.lastError().isEmpty() ? null : "Son hata: " + s.lastError());
    }
}
