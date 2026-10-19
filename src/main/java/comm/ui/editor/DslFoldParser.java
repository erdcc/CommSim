package comm.ui.editor;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Senaryo dili bloklari (repeat / while / if / function ... end) icin katlama. */
public class DslFoldParser implements FoldParser {
    @Override public List<Fold> getFolds(RSyntaxTextArea area) {
        List<Fold> top = new ArrayList<>();
        Deque<Fold> open = new ArrayDeque<>();
        try {
            Element root = area.getDocument().getDefaultRootElement();
            for (int i = 0; i < root.getElementCount(); i++) {
                Element line = root.getElement(i);
                String text = area.getDocument().getText(line.getStartOffset(), line.getEndOffset() - line.getStartOffset());
                String first = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                int at = line.getStartOffset() + (text.length() - text.replaceAll("^\\s+", "").length());
                if (first.equals("repeat") || first.equals("while") || first.equals("if") || first.equals("function")) {
                    Fold f = open.isEmpty() ? new Fold(FoldType.CODE, area, at) : open.peek().createChild(FoldType.CODE, at);
                    if (open.isEmpty()) top.add(f);
                    open.push(f);
                } else if (first.equals("end") && !open.isEmpty()) {
                    Fold f = open.pop();
                    f.setEndOffset(at + 2);
                    if (f.isOnSingleLine()) {
                        if (f.getParent() == null) top.remove(f);
                        else f.removeFromParent();
                    }
                }
            }
        } catch (BadLocationException e) {
            // belge degisti; bir sonraki ayristirmada duzelir
        }
        // kapanmamis bloklari at
        for (Fold f : open) {
            if (f.getParent() == null) top.remove(f);
            else f.removeFromParent();
        }
        return top;
    }
}
