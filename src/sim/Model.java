package sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mesaj tanimi ve deger tipleri. */
public final class Model {
    private Model() {}

    /** LIST: kayit listesi (her kayit bir StructDef). Degerleri formdan degil, haritadan gelir. */
    public enum Type { INT, DOUBLE, BOOL, STRING, ENUM, INTLIST, LIST }

    /** Liste alanlarinin eleman sablonu (messages.def'teki 'struct'). */
    public record StructDef(String name, List<FieldDef> fields) {}

    /** bind: haritadaki node ozelligi adi (null ise alan adi kullanilir). struct: sadece LIST icin. */
    public record FieldDef(String name, Type type, String defaultValue, List<String> enumValues,
                           String bind, StructDef struct) {
        public String bindKey() { return bind != null ? bind : name; }
    }

    public record MessageDef(int id, String name, List<FieldDef> fields) {
        @Override public String toString() { return id + " - " + name; }
    }

    /**
     * Bir mesajin alan degerleri: INT->Integer, DOUBLE->Double, BOOL->Boolean, STRING/ENUM->String,
     * INTLIST->List<Integer>, LIST->List<Map<String,Object>>.
     */
    public record Message(MessageDef def, Map<String, Object> values) {
        public Message(MessageDef def) { this(def, new LinkedHashMap<>()); }

        public double getDouble(String field) { return ((Number) values.get(field)).doubleValue(); }
        public int getInt(String field) { return ((Number) values.get(field)).intValue(); }
        public String getString(String field) { return String.valueOf(values.get(field)); }
        public boolean has(String field) { return values.containsKey(field); }

        public String summary() {
            List<String> parts = new ArrayList<>();
            values.forEach((k, v) -> parts.add(k + "=" + (isRecordList(v) ? ((List<?>) v).size() + " kayıt" : v)));
            return String.join(", ", parts);
        }
    }

    private static boolean isRecordList(Object v) {
        return v instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map;
    }

    /** Detay gosterimi icin: kayit listelerini satir satir yazar. */
    public static String describe(Object v) {
        if (!isRecordList(v)) return String.valueOf(v);
        List<?> l = (List<?>) v;
        StringBuilder sb = new StringBuilder(l.size() + " kayıt");
        for (Object o : l) sb.append("\n    ").append(o);
        return sb.toString();
    }

    /** Sayiyi gereksiz sifirlar olmadan yazar (en fazla 6 ondalik). */
    public static String fmt(double d) {
        return new java.math.BigDecimal(String.format(java.util.Locale.ROOT, "%.6f", d))
                .stripTrailingZeros().toPlainString();
    }

    /** Gonderilen/alinan her mesaj icin bildirim (harita mantigi buraya baglanir). */
    public interface MessageListener {
        void onMessage(Message m, boolean incoming);
    }

    /** Alan metnini tipine gore cevirir. Gecersizse IllegalArgumentException firlatir. */
    public static Object parseValue(FieldDef f, String text) {
        String t = text == null ? "" : text.trim();
        try {
            return switch (f.type()) {
                case INT -> Integer.parseInt(t);
                case DOUBLE -> Double.parseDouble(t.replace(',', '.'));
                case BOOL -> Boolean.parseBoolean(t);
                case STRING -> text == null ? "" : text;
                case INTLIST -> {
                    List<Integer> ids = new ArrayList<>();
                    for (String part : t.split("[,;\\s]+")) if (!part.isEmpty()) ids.add(Integer.parseInt(part));
                    yield ids;
                }
                case LIST -> new ArrayList<Map<String, Object>>();
                case ENUM -> {
                    if (!f.enumValues().contains(t)) throw new IllegalArgumentException("gecersiz deger");
                    yield t;
                }
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + f.name() + "' alani gecersiz: '" + text + "'");
        }
    }
}
