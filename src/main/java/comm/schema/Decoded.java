package comm.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bir cercevenin semaya gore cozumu. Cozum hatasinda eldeki kismi degerler korunur. */
public final class Decoded {
    /** Cercevedeki bir alanin yeri ve gosterimi. */
    public static final class Span {
        public final String path;
        public final int offset;
        public final int length;
        public final String text;

        public Span(String path, int offset, int length, String text) {
            this.path = path;
            this.offset = offset;
            this.length = length;
            this.text = text;
        }
    }

    /** Taninamadiysa null. */
    public MessageDef message;
    public final Map<String, Object> header = new LinkedHashMap<>();
    public final Map<String, Object> body = new LinkedHashMap<>();
    public final Map<String, Object> footer = new LinkedHashMap<>();
    public final List<Span> spans = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    /** Cozum yarim kaldiysa nedeni. */
    public String error;

    public boolean ok() { return message != null && error == null; }

    /** Trafik tablosundaki kisa ad. */
    public String label() {
        String n = message == null ? "?" : message.name;
        return error != null || !warnings.isEmpty() ? n + " ⚠" : n;
    }
}
