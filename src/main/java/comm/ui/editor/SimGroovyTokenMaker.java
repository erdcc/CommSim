package comm.ui.editor;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.modes.GroovyTokenMaker;

/** Groovy renklendirmesi; simulator API yontemleri (send, waitFor...) islev rengiyle gosterilir. */
public class SimGroovyTokenMaker extends GroovyTokenMaker {
    @Override public void addToken(char[] array, int start, int end, int tokenType, int startOffset) {
        if (tokenType == Token.IDENTIFIER && end >= start
                && GroovyInfo.METHODS.containsKey(new String(array, start, end - start + 1))) {
            tokenType = Token.FUNCTION;
        }
        super.addToken(array, start, end, tokenType, startOffset);
    }
}
