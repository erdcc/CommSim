package comm.ui.editor;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;

import javax.swing.text.Segment;

/** Senaryo dili renklendiricisi: komutlar, blok sozcukleri, metinler, ${degisken}, sayilar, alan=deger. */
public class DslTokenMaker extends AbstractTokenMaker {
    @Override public TokenMap getWordsToHighlight() { return new TokenMap(); }

    @Override public String[] getLineCommentStartAndEnd(int languageIndex) { return new String[]{"# ", null}; }

    @Override public boolean getMarkOccurrencesOfTokenType(int type) {
        return type == Token.VARIABLE || type == Token.IDENTIFIER || type == Token.LITERAL_STRING_DOUBLE_QUOTE;
    }

    @Override public javax.swing.Action getInsertBreakAction() { return new DslInsertBreak(); }

    @Override public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        char[] a = text.array;
        int off = text.offset;
        int end = off + text.count;
        int shift = startOffset - off;
        int i = off;
        boolean first = true;
        while (i < end) {
            char c = a[i];
            if (RSyntaxUtilities.isWhitespace(c)) {
                int j = i;
                while (j < end && RSyntaxUtilities.isWhitespace(a[j])) j++;
                addToken(text, i, j - 1, Token.WHITESPACE, shift + i);
                i = j;
                continue;
            }
            if (c == '#') {
                addToken(text, i, end - 1, Token.COMMENT_EOL, shift + i);
                i = end;
                break;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < end && a[j] != '"') {
                    if (a[j] == '\\' && j + 1 < end) j++;
                    j++;
                }
                if (j < end) j++;
                addToken(text, i, j - 1, Token.LITERAL_STRING_DOUBLE_QUOTE, shift + i);
                i = j;
                first = false;
                continue;
            }
            int j = i;
            while (j < end && !RSyntaxUtilities.isWhitespace(a[j]) && a[j] != '"') j++;
            String w = new String(a, i, j - i);
            if (first) {
                String k = w.toLowerCase(java.util.Locale.ROOT);
                int type = DslInfo.BLOCK_WORDS.contains(k) ? Token.RESERVED_WORD
                        : DslInfo.COMMANDS.containsKey(k) ? Token.FUNCTION : Token.IDENTIFIER;
                addToken(text, i, j - 1, type, shift + i);
                first = false;
            } else {
                word(text, a, i, j, w, shift);
            }
            i = j;
        }
        addNullToken();
        return firstToken;
    }

    /** Arguman: alan=deger ayrilir; ${..}, sayi ve operatorler ayri renk alir. */
    private void word(Segment text, char[] a, int i, int j, String w, int shift) {
        int eq = w.indexOf('=');
        boolean op = DslInfo.isOperator(w);
        if (!op && eq > 0 && eq < w.length() && !w.startsWith("${")) {
            addToken(text, i, i + eq - 1, Token.DATA_TYPE, shift + i);
            addToken(text, i + eq, i + eq, Token.OPERATOR, shift + i + eq);
            if (i + eq + 1 < j) addToken(text, i + eq + 1, j - 1, valueType(w.substring(eq + 1)), shift + i + eq + 1);
            return;
        }
        addToken(text, i, j - 1, op ? Token.OPERATOR : valueType(w), shift + i);
    }

    private static int valueType(String v) {
        if (v.contains("${")) return Token.VARIABLE;
        if (v.matches("-?(0[xX][0-9a-fA-F]+|\\d+(\\.\\d+)?)(,-?(0[xX][0-9a-fA-F]+|\\d+(\\.\\d+)?))*")) return Token.LITERAL_NUMBER_DECIMAL_INT;
        if (v.matches("(?i)true|false|on|off")) return Token.LITERAL_BOOLEAN;
        return Token.IDENTIFIER;
    }
}
