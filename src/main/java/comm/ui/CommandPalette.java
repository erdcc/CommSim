package comm.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Klavyeyle her eyleme ulasmak icin arama kutulu komut listesi (⌘K). */
final class CommandPalette {
    /** Tek komut. */
    static final class Command {
        final String group;
        final String title;
        final String shortcut;
        final Icon icon;
        final Runnable action;
        final String haystack;

        Command(String group, String title, String shortcut, Icon icon, Runnable action) {
            this.group = group;
            this.title = title;
            this.shortcut = shortcut == null ? "" : shortcut;
            this.icon = icon;
            this.action = action;
            this.haystack = fold(group + " " + title);
        }
    }

    private CommandPalette() { }

    /** Turkce karakterleri sadelestirip kucuk harfe cevirir; "gönder" ile "gonder" eslesir. */
    static String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'ı': case 'I': case 'İ': case 'i': sb.append('i'); break;
                case 'ğ': case 'Ğ': sb.append('g'); break;
                case 'ü': case 'Ü': sb.append('u'); break;
                case 'ş': case 'Ş': sb.append('s'); break;
                case 'ö': case 'Ö': sb.append('o'); break;
                case 'ç': case 'Ç': sb.append('c'); break;
                default: sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Sorguya uyan komutlar, en iyi eslesme basta. Her kelime metinde gecmelidir. */
    static List<Command> filter(List<Command> all, String query) {
        String q = fold(query.trim());
        if (q.isEmpty()) return new ArrayList<>(all);
        String[] words = q.split("\\s+");
        List<Command> out = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Command c : all) {
            int score = 0;
            boolean ok = true;
            String title = fold(c.title);
            for (String w : words) {
                int i = c.haystack.indexOf(w);
                if (i < 0) {
                    ok = false;
                    break;
                }
                if (title.startsWith(w)) score += 3;
                else if (title.contains(" " + w) || title.contains(":" + w)) score += 2;
                else if (title.contains(w)) score += 1;
            }
            if (!ok) continue;
            int pos = 0;
            while (pos < scores.size() && scores.get(pos) >= score) pos++;
            out.add(pos, c);
            scores.add(pos, score);
        }
        return out;
    }

    /** Pencerenin ust ortasinda paleti acar. */
    static void show(Window owner, List<Command> commands) {
        JDialog d = create(owner, commands);
        Point p = owner.getLocationOnScreen();
        d.setLocation(p.x + (owner.getWidth() - d.getWidth()) / 2, p.y + 70);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowDeactivated(WindowEvent e) { d.dispose(); }
        });
        d.setVisible(true);
        ((JComponent) d.getContentPane()).getComponent(0).requestFocusInWindow();
    }

    /** Paleti kurar (gosterilmez). */
    static JDialog create(Window owner, List<Command> commands) {
        JDialog d = new JDialog(owner);
        d.setUndecorated(true);
        d.setModal(false);
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", "Komut, bağlantı ya da mesaj ara…  (ör. \"gönder konum\", \"başlat\", \"koyu\")");
        field.putClientProperty("JTextField.leadingIcon", Icons.SEARCH);
        field.putClientProperty("JComponent.minimumHeight", 34);
        field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 1f));
        DefaultListModel<Command> model = new DefaultListModel<>();
        JList<Command> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setVisibleRowCount(12);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JLabel foot = new JLabel("↑↓ seç   ⏎ çalıştır   esc kapat");
        foot.setForeground(Theme.muted());
        foot.setFont(foot.getFont().deriveFont(foot.getFont().getSize2D() - 1f));
        foot.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.separator()),
                BorderFactory.createEmptyBorder(8, 8, 0, 8)));
        root.add(field, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        d.setContentPane(root);

        Runnable refresh = () -> {
            model.clear();
            for (Command c : filter(commands, field.getText())) model.addElement(c);
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        Runnable run = () -> {
            Command c = list.getSelectedValue();
            d.dispose();
            if (c != null) SwingUtilities.invokeLater(c.action);
        };
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh.run(); }
        });
        bind(field, KeyEvent.VK_DOWN, () -> move(list, 1));
        bind(field, KeyEvent.VK_UP, () -> move(list, -1));
        bind(field, KeyEvent.VK_PAGE_DOWN, () -> move(list, 8));
        bind(field, KeyEvent.VK_PAGE_UP, () -> move(list, -8));
        bind(field, KeyEvent.VK_ENTER, run);
        bind(root, KeyEvent.VK_ESCAPE, d::dispose);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) run.run();
            }
        });
        refresh.run();
        d.setSize(new Dimension(Math.min(640, Math.max(420, owner.getWidth() - 80)), 430));
        return d;
    }

    private static void move(JList<Command> list, int delta) {
        int n = list.getModel().getSize();
        if (n == 0) return;
        int i = Math.max(0, Math.min(n - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private static void bind(JComponent c, int key, Runnable r) {
        String name = "palette-" + key;
        c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), name);
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        c.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Satir: ikon, baslik; sagda grup ve kisayol. */
    private static final class Renderer extends JPanel implements ListCellRenderer<Command> {
        private static final long serialVersionUID = 1L;
        private final JLabel title = new JLabel();
        private final JLabel right = new JLabel();

        Renderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 10));
            title.setIconTextGap(8);
            add(title, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
        }

        @Override public Component getListCellRendererComponent(JList<? extends Command> l, Command c, int i,
                                                                boolean sel, boolean foc) {
            title.setText(c.title);
            title.setIcon(c.icon);
            right.setText(c.shortcut.isEmpty() ? c.group : c.group + "   " + c.shortcut);
            Font f = l.getFont();
            title.setFont(f);
            right.setFont(f.deriveFont(f.getSize2D() - 1f));
            Color bg = sel ? l.getSelectionBackground() : l.getBackground();
            setBackground(bg);
            title.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            right.setForeground(sel ? Theme.tint(l.getSelectionForeground(), 180) : Theme.muted());
            return this;
        }
    }
}
