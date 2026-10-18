package comm.schema;

import comm.core.Hex;
import comm.schema.FieldDef.Kind;
import comm.schema.FieldDef.Size;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Alan degerlerinin bellek temsili ve metin donusumleri.
 * INT: Long (uint64 isaretsiz bit deseni), FLOAT: Double, BOOL: Boolean, ENUM/STRING/CSTRING: String,
 * BYTES: byte[], ARRAY: List&lt;Object&gt;, LIST: List&lt;Map&gt;, STRUCT: Map&lt;String,Object&gt;.
 */
public final class Values {
    private Values() { }

    // ---- metin -> deger ----

    /** Yaprak alan icin metni cevirir; gecersizse alan adini iceren IllegalArgumentException. */
    public static Object parse(FieldDef f, String text) {
        String t = text == null ? "" : text;
        String trimmed = t.trim();
        switch (f.kind) {
            case INT:
                return checkRange(f, parseInt(f, trimmed));
            case FLOAT:
                try {
                    return Double.parseDouble(trimmed.replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw bad(f, "geçersiz ondalık sayı: '" + t + "'");
                }
            case BOOL:
                switch (trimmed.toLowerCase(new Locale("tr"))) {
                    case "true": case "1": case "evet": case "e": case "doğru": return Boolean.TRUE;
                    case "false": case "0": case "hayır": case "h": case "yanlış": return Boolean.FALSE;
                    default: throw bad(f, "true/false bekleniyor: '" + t + "'");
                }
            case ENUM: {
                if (f.enumByName(trimmed) != null) return trimmed;
                long v = parseInt(f, trimmed);
                checkRange(f, v);
                FieldDef.EnumItem e = f.enumByValue(v);
                return e != null ? e.name : Long.toString(v);
            }
            case STRING:
            case CSTRING: {
                if (f.kind == Kind.CSTRING && t.indexOf('\0') >= 0) throw bad(f, "cstring içinde \\0 olamaz");
                int n = t.getBytes(StandardCharsets.UTF_8).length;
                if (f.length != null && f.length.mode == Size.Mode.FIXED && n > f.length.n) {
                    throw bad(f, "en fazla " + f.length.n + " byte olabilir, " + n + " verildi");
                }
                return t;
            }
            case BYTES: {
                byte[] b;
                try {
                    b = Hex.parse(t);
                } catch (IllegalArgumentException e) {
                    throw bad(f, e.getMessage());
                }
                if (f.length.mode == Size.Mode.FIXED && b.length > f.length.n) {
                    throw bad(f, "en fazla " + f.length.n + " byte olabilir, " + b.length + " verildi");
                }
                return b;
            }
            default:
                throw bad(f, "bu alan doğrudan düzenlenemez");
        }
    }

    private static long parseInt(FieldDef f, String t) {
        if (!f.signed && t.startsWith("-")) throw bad(f, "işaretsiz alan negatif olamaz: '" + t + "'");
        try {
            return Types.parseLong(t);
        } catch (NumberFormatException e) {
            throw bad(f, "geçersiz tam sayı: '" + t + "'");
        }
    }

    static long checkRange(FieldDef f, long v) {
        int bits = f.bitWidth > 0 ? f.bitWidth : 8 * f.size;
        if (bits >= 64) return v; // 64 bit: isaretsiz deger long bit deseninde tasinir
        long min = f.signed ? -(1L << (bits - 1)) : 0;
        long max = f.signed ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
        if (v < min || v > max) throw bad(f, "değer aralık dışında (" + min + " … " + max + "): " + v);
        return v;
    }

    private static IllegalArgumentException bad(FieldDef f, String msg) {
        return new IllegalArgumentException("'" + f.name + "': " + msg);
    }

    // ---- deger -> metin ----

    public static String format(FieldDef f, Object v) {
        if (v == null) return "";
        switch (f.kind) {
            case INT: {
                long l = ((Number) v).longValue();
                if (prefersHex(f)) {
                    long masked = f.size >= 8 ? l : l & ((1L << (8 * f.size)) - 1);
                    StringBuilder h = new StringBuilder(Long.toHexString(masked).toUpperCase(Locale.ROOT));
                    while (h.length() < 2 * f.size) h.insert(0, '0');
                    return "0x" + h;
                }
                return f.size >= 8 && !f.signed ? Long.toUnsignedString(l) : Long.toString(l);
            }
            case FLOAT: {
                double d = ((Number) v).doubleValue();
                return f.size == 4 ? trimZero(Float.toString((float) d)) : trimZero(Double.toString(d));
            }
            case BYTES:
                return Hex.toHex((byte[]) v);
            default:
                return String.valueOf(v);
        }
    }

    /** Yalnizca checksum'lar hex gosterilir; diger tam sayilar her zaman ondalik. */
    private static boolean prefersHex(FieldDef f) {
        return f.role.isChecksum();
    }

    private static String trimZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ---- varsayilanlar ----

    public static Object defaultValue(FieldDef f) {
        if (f.defaultText != null) return parse(f, f.defaultText);
        switch (f.kind) {
            case INT: return 0L;
            case FLOAT: return 0.0;
            case BOOL: return Boolean.FALSE;
            case ENUM: return f.enums.get(0).name;
            case STRING: case CSTRING: return "";
            case BYTES: return new byte[f.length.mode == Size.Mode.FIXED ? f.length.n : 0];
            case ARRAY: {
                List<Object> l = new ArrayList<>();
                if (f.length.mode == Size.Mode.FIXED) for (int i = 0; i < f.length.n; i++) l.add(defaultValue(f.element));
                return l;
            }
            case LIST: {
                List<Object> l = new ArrayList<>();
                if (f.length.mode == Size.Mode.FIXED) for (int i = 0; i < f.length.n; i++) l.add(defaults(f.struct));
                return l;
            }
            case STRUCT:
            case BITS:
                return defaults(f.struct);
            default:
                throw new IllegalStateException(f.kind.name());
        }
    }

    public static Map<String, Object> defaults(StructDef s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldDef f : s.fields) {
            if (!f.role.isAuto()) m.put(f.name, defaultValue(f));
        }
        return m;
    }

    /** Eksik alanlari varsayilanla doldurur (ic ice listeler dahil); ayni haritayi dondurur. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> complete(StructDef s, Map<String, Object> m) {
        for (FieldDef f : s.fields) {
            if (f.role.isAuto()) continue;
            Object v = m.get(f.name);
            if (v == null) {
                m.put(f.name, defaultValue(f));
            } else if (f.kind == Kind.STRUCT || f.kind == Kind.BITS) {
                complete(f.struct, (Map<String, Object>) v);
            } else if (f.kind == Kind.LIST) {
                for (Object o : (List<Object>) v) complete(f.struct, (Map<String, Object>) o);
            }
        }
        return m;
    }

    /** Derin kopya (harita, liste ve byte dizileri). */
    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) out.put(e.getKey(), copy(e.getValue()));
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) out.add(copy(o));
            return out;
        }
        if (v instanceof byte[]) return ((byte[]) v).clone();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyMap(Map<String, Object> m) { return (Map<String, Object>) copy(m); }

    // ---- duzlestirme (form ve parametre tablolari) ----

    /** Duzlestirilmis tablo satiri: yol, alan ve degere erisim. */
    public static final class Row {
        public enum Type {
            /** Duzenlenebilir yaprak deger. */
            VALUE,
            /** Liste/dizi eleman sayisi. */
            COUNT,
            /** Liste elemani ya da struct basligi. */
            GROUP,
            /** Kodlayicinin doldurdugu alan (id, uzunluk, checksum, adet). */
            AUTO
        }

        public final String path;
        public final int depth;
        public final FieldDef field;
        public final Type type;
        private final Supplier<Object> getter;
        private final Consumer<Object> setter;

        Row(String path, int depth, FieldDef field, Type type, Supplier<Object> getter, Consumer<Object> setter) {
            this.path = path;
            this.depth = depth;
            this.field = field;
            this.type = type;
            this.getter = getter;
            this.setter = setter;
        }

        public Object get() { return getter == null ? null : getter.get(); }

        public boolean editable() { return setter != null; }

        /** VALUE icin ayristirilmis deger, COUNT icin Integer bekler. */
        public void set(Object v) {
            if (setter == null) throw new IllegalStateException(path + " salt okunur");
            setter.accept(v);
        }

        /** Metni ayristirip yazar. */
        public void setText(String text) {
            if (type == Type.COUNT) {
                int n;
                try {
                    n = Integer.parseInt(text.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + field.name + "': adet tam sayı olmalı");
                }
                if (n < 0 || n > 100_000) throw new IllegalArgumentException("'" + field.name + "': adet 0 … 100000 olmalı");
                set(n);
            } else {
                set(parse(field, text));
            }
        }

        public String display() {
            Object v = get();
            switch (type) {
                case COUNT: return v == null ? "" : v + " öğe";
                case GROUP: return "";
                case AUTO: return v == null ? "(otomatik)" : format(field, v);
                default: return format(field, v);
            }
        }

        /** Tip sutunu metni; bit alanlarinda bit konumu da yazilir. */
        public String typeText() {
            String t = baseTypeText();
            if (field.bitWidth == 0) return t;
            int lo = field.bitShift;
            int hi = lo + field.bitWidth - 1;
            return (lo == hi ? "bit " + lo : "bit " + lo + "-" + hi) + " · " + t;
        }

        private String baseTypeText() {
            if (type == Type.GROUP) return field.kind == Kind.LIST ? field.struct.name : field.typeText;
            if (field.kind == Kind.ENUM) {
                // secenekler acilir listede ve ipucunda; burada yalniz taban tip
                String base = field.typeText.contains(" ") ? field.typeText.substring(0, field.typeText.indexOf(' ')) : field.typeText;
                return base + " · " + field.enums.size() + " seçenek";
            }
            return field.typeText;
        }
    }

    /**
     * Degerleri satirlara acar. editable=false ise (alinan mesajlar) hicbir satira yazilamaz.
     * editable=true ise harita once {@link #complete} ile tamamlanmis olmali.
     */
    public static List<Row> flatten(StructDef s, Map<String, Object> values, String prefix, boolean editable) {
        List<Row> out = new ArrayList<>();
        flatten(s.fields, values, prefix, 0, editable, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(List<FieldDef> fields, Map<String, Object> values, String prefix, int depth,
                                boolean editable, List<Row> out) {
        for (FieldDef f : fields) {
            String path = prefix + f.name;
            Supplier<Object> get = () -> values.get(f.name);
            if (f.role.isAuto()) {
                out.add(new Row(path, depth, f, Row.Type.AUTO, get, null));
                continue;
            }
            Object v = values.get(f.name);
            switch (f.kind) {
                case ARRAY: case LIST: {
                    List<Object> list = v instanceof List ? (List<Object>) v : null;
                    boolean resizable = editable && list != null && f.length.mode != Size.Mode.FIXED;
                    out.add(new Row(path, depth, f, Row.Type.COUNT, () -> list == null ? null : list.size(),
                            resizable ? n -> resize(f, list, (Integer) n) : null));
                    if (list == null) break;
                    for (int i = 0; i < list.size(); i++) {
                        int idx = i;
                        String ep = path + "[" + i + "]";
                        if (f.kind == Kind.ARRAY) {
                            out.add(new Row(ep, depth + 1, f.element, Row.Type.VALUE, () -> list.get(idx),
                                    editable ? x -> list.set(idx, x) : null));
                        } else {
                            out.add(new Row(ep, depth + 1, f, Row.Type.GROUP, null, null));
                            flatten(f.struct.fields, (Map<String, Object>) list.get(i), ep + ".", depth + 2, editable, out);
                        }
                    }
                    break;
                }
                case STRUCT:
                case BITS:
                    out.add(new Row(path, depth, f, Row.Type.GROUP, null, null));
                    if (v instanceof Map) flatten(f.struct.fields, (Map<String, Object>) v, path + ".", depth + 1, editable, out);
                    break;
                default:
                    out.add(new Row(path, depth, f, Row.Type.VALUE, get, editable ? x -> values.put(f.name, x) : null));
            }
        }
    }

    // ---- metin olarak kayit/geri yukleme (proje dosyasi, senaryolar) ----

    /** Duzenlenebilir degerleri satir sirasiyla "yol -> metin" olarak verir (adetler elemanlardan once). */
    public static Map<String, String> toTexts(StructDef s, Map<String, Object> values, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Row r : flatten(s, values, prefix, false)) {
            if (r.type == Row.Type.VALUE || r.type == Row.Type.COUNT) {
                Object v = r.get();
                if (v != null) out.put(r.path, r.type == Row.Type.COUNT ? v.toString() : format(r.field, v));
            }
        }
        return out;
    }

    /** Alinan bir mesajin tum degerleri (otomatik alanlar ve adetler dahil): yol -&gt; gosterim metni. */
    public static Map<String, String> receivedTexts(StructDef s, Map<String, Object> values, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Row r : flatten(s, values, prefix, false)) {
            if (r.type == Row.Type.GROUP || r.get() == null) continue;
            out.put(r.path, r.type == Row.Type.COUNT ? r.get().toString() : r.display());
        }
        return out;
    }

    /**
     * Metin degerlerini sirayla uygular; bulunamayan ya da gecersiz olanlar atlanir.
     * @return sorunlarin listesi (bos ise hepsi uygulandi)
     */
    public static List<String> applyTexts(StructDef s, Map<String, Object> values, String prefix, Map<String, String> texts) {
        List<String> problems = new ArrayList<>();
        complete(s, values);
        for (Map.Entry<String, String> e : texts.entrySet()) {
            Row row = null;
            for (Row r : flatten(s, values, prefix, true)) {
                if (r.path.equals(e.getKey()) && r.editable()) {
                    row = r;
                    break;
                }
            }
            if (row == null) {
                problems.add(e.getKey() + ": alan bulunamadı");
                continue;
            }
            try {
                row.setText(e.getValue());
            } catch (IllegalArgumentException ex) {
                problems.add(ex.getMessage());
            }
        }
        return problems;
    }

    private static void resize(FieldDef f, List<Object> list, int n) {
        while (list.size() > n) list.remove(list.size() - 1);
        while (list.size() < n) list.add(f.kind == Kind.ARRAY ? defaultValue(f.element) : defaults(f.struct));
    }
}
