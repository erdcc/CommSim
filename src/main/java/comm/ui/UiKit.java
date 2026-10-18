package comm.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;

/** Ortak bilesen stilleri: butonlar, basliklar, arama alanlari. */
final class UiKit {
    private static final String PRIMARY = "background: #2F6FEB; foreground: #FFFFFF; hoverBackground: #2A63D4;"
            + " pressedBackground: #2458BE; borderColor: #2F6FEB; focusedBorderColor: #9DBBF6;"
            + " disabledBackground: #8FB0F0; disabledText: #EEF3FD; font: bold";

    private UiKit() { }

    static JButton button(String text, Icon icon) {
        JButton b = new JButton(text, icon);
        b.setIconTextGap(6);
        return b;
    }

    /** Ekrandaki ana eylem (Gonder, Calistir...): vurgulu mavi buton. */
    static JButton primary(String text, Icon icon) {
        JButton b = new JButton(text, icon == null ? null : Icons.onAccent(icon));
        b.putClientProperty("FlatLaf.style", PRIMARY);
        b.setIconTextGap(6);
        return b;
    }

    /** Zeminsiz arac cubugu butonu. */
    static JButton tool(String text, Icon icon, String tip) {
        JButton b = new JButton(text, icon);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.setIconTextGap(6);
        return b;
    }

    static void placeholder(JTextField f, String text) {
        f.putClientProperty("JTextField.placeholderText", text);
    }

    /** Buyutec ikonlu, temizleme dugmeli arama alani. */
    static void search(JTextField f, String placeholder) {
        placeholder(f, placeholder);
        f.putClientProperty("JTextField.leadingIcon", Icons.SEARCH);
        f.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
    }

    /** FlatLaf yazi sinifi: h1..h4, large, medium, small, mini, semibold. */
    static <T extends JLabel> T style(T l, String styleClass) {
        l.putClientProperty("FlatLaf.styleClass", styleClass);
        return l;
    }

    static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.muted());
        return l;
    }
}
