package comm.ui.editor;

import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RecordableTextAction;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.awt.event.ActionEvent;

/** Enter: girintiyi korur, blok acan satirdan (repeat/while/if/else/function) sonra iki bosluk ekler. */
final class DslInsertBreak extends RecordableTextAction {
    private static final long serialVersionUID = 1L;

    DslInsertBreak() { super("insert-break"); }

    @Override public void actionPerformedImpl(ActionEvent e, RTextArea area) {
        if (!area.isEditable() || !area.isEnabled()) {
            javax.swing.UIManager.getLookAndFeel().provideErrorFeedback(area);
            return;
        }
        try {
            Document doc = area.getDocument();
            int caret = area.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(caret));
            String before = doc.getText(line.getStartOffset(), caret - line.getStartOffset());
            StringBuilder indent = new StringBuilder();
            for (char c : before.toCharArray()) {
                if (c == ' ' || c == '\t') indent.append(c);
                else break;
            }
            String first = before.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (DslInfo.OPENERS.contains(first)) indent.append("  ");
            area.replaceSelection("\n" + indent);
        } catch (BadLocationException ex) {
            area.replaceSelection("\n");
        }
    }

    @Override public String getMacroID() { return "insert-break"; }
}
