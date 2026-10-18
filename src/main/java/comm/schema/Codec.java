package comm.schema;

import comm.schema.FieldDef.Kind;
import comm.schema.FieldDef.Role;
import comm.schema.FieldDef.Size;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Semaya gore ikili kodlama ve cozme. Cerceve: baslik + mesaj govdesi + son ek.
 * Id, uzunluk, adet ve checksum alanlari kodlarken otomatik doldurulur, cozerken dogrulanir.
 */
public final class Codec {
    private static final int MAX_COUNT = 1_000_000;

    private Codec() { }

    // =============================== kodlama ===============================

    private static final class Writer {
        byte[] a = new byte[256];
        int n;

        void ensure(int k) {
            if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k));
        }

        void writeInt(long v, int size, ByteOrder o) {
            ensure(size);
            put(n, v, size, o);
            n += size;
        }

        void put(int pos, long v, int size, ByteOrder o) {
            for (int i = 0; i < size; i++) {
                int shift = 8 * (o == ByteOrder.BIG_ENDIAN ? size - 1 - i : i);
                a[pos + i] = (byte) (v >>> shift);
            }
        }

        void write(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, a, n, b.length);
            n += b.length;
        }

        void zeros(int k) {
            ensure(k);
            n += k; // dizi zaten sifirli
        }
    }

    private static final class Patch {
        final int pos;
        final FieldDef field;

        Patch(int pos, FieldDef field) {
            this.pos = pos;
            this.field = field;
        }
    }

    private static final class EncCtx {
        final Schema schema;
        final long id;
        final List<Patch> patches = new ArrayList<>();

        EncCtx(Schema schema, long id) {
            this.schema = schema;
            this.id = id;
        }
    }

    /**
     * Mesaji kodlar. header null olabilir (varsayilanlar kullanilir).
     * @throws IllegalArgumentException deger aralik disi ya da tanima uymuyorsa (alan adiyla)
     */
    public static byte[] encode(Schema s, MessageDef m, Map<String, Object> header, Map<String, Object> body) {
        Writer w = new Writer();
        EncCtx c = new EncCtx(s, m.id);
        Map<String, Object> empty = Collections.emptyMap();
        writeStruct(s.header.fields, header == null ? empty : header, w, c);
        int bodyStart = w.n;
        writeStruct(m.body.fields, body == null ? empty : body, w, c);
        int bodyEnd = w.n;
        writeStruct(s.footer.fields, empty, w, c);

        for (Patch p : c.patches) {
            if (!p.field.role.isLength()) continue;
            long v = lengthValue(s.lengthRole(p.field), w.n, bodyStart, bodyEnd, p.pos, p.field.size) + s.lengthAdjust(p.field);
            Values.checkRange(p.field, v);
            w.put(p.pos, v, p.field.size, s.orderOf(p.field));
        }
        // checksum'lar konum sirasiyla; her biri kendinden onceki tum byte'lari kapsar
        for (Patch p : c.patches) {
            if (!p.field.role.isChecksum()) continue;
            long v = Checksums.compute(s.checksumRole(p.field), w.a, Math.min(s.checksumFrom(p.field), p.pos), p.pos);
            w.put(p.pos, v, p.field.size, s.orderOf(p.field));
        }
        return Arrays.copyOf(w.a, w.n);
    }

    private static long lengthValue(Role role, int total, int bodyStart, int bodyEnd, int pos, int size) {
        switch (role) {
            case LENGTH_ALL: return total;
            case LENGTH_BODY: return bodyEnd - bodyStart;
            default: return total - (pos + size);
        }
    }

    private static void writeStruct(List<FieldDef> fields, Map<String, Object> values, Writer w, EncCtx c) {
        Map<String, Long> counts = new HashMap<>();
        for (FieldDef f : fields) {
            if (f.length != null && f.length.mode == Size.Mode.FIELD) {
                counts.put(f.length.field, (long) countOf(f, valueOrDefault(f, values)));
            }
        }
        for (FieldDef f : fields) {
            ByteOrder o = c.schema.orderOf(f);
            switch (f.role) {
                case ID:
                    w.writeInt(Values.checkRange(f, c.id), f.size, o);
                    break;
                case COUNT:
                    w.writeInt(Values.checkRange(f, counts.getOrDefault(f.name, 0L)), f.size, o);
                    break;
                case NONE:
                    writeValue(f, valueOrDefault(f, values), w, c);
                    break;
                default: // uzunluk ve checksum: sona birakilir
                    c.patches.add(new Patch(w.n, f));
                    w.zeros(f.size);
            }
        }
    }

    private static Object valueOrDefault(FieldDef f, Map<String, Object> values) {
        Object v = values.get(f.name);
        return v != null ? v : Values.defaultValue(f);
    }

    /** FIELD modunda adet alanina yazilacak sayi: string/bytes icin byte, dizi/liste icin eleman sayisi. */
    private static int countOf(FieldDef f, Object v) {
        switch (f.kind) {
            case STRING: return ((String) v).getBytes(StandardCharsets.UTF_8).length;
            case BYTES: return ((byte[]) v).length;
            default: return ((List<?>) v).size();
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(FieldDef f, Object v, Writer w, EncCtx c) {
        ByteOrder o = c.schema.orderOf(f);
        switch (f.kind) {
            case INT:
                w.writeInt(Values.checkRange(f, toLong(f, v)), f.size, o);
                break;
            case FLOAT: {
                double d = v instanceof Number ? ((Number) v).doubleValue() : (Double) Values.parse(f, v.toString());
                w.writeInt(f.size == 4 ? Float.floatToIntBits((float) d) : Double.doubleToLongBits(d), f.size, o);
                break;
            }
            case BOOL:
                w.writeInt(Boolean.TRUE.equals(v instanceof String ? Values.parse(f, (String) v) : v) ? 1 : 0, f.size, o);
                break;
            case ENUM: {
                String name = v.toString();
                FieldDef.EnumItem e = f.enumByName(name);
                long n;
                if (e != null) {
                    n = e.value;
                } else {
                    try {
                        n = Types.parseLong(name);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("'" + f.name + "': tanımsız enum değeri: " + name);
                    }
                }
                w.writeInt(Values.checkRange(f, n), f.size, o);
                break;
            }
            case STRING:
                writeSized(f, ((String) v).getBytes(StandardCharsets.UTF_8), w, c);
                break;
            case CSTRING:
                w.write(((String) v).getBytes(StandardCharsets.UTF_8));
                w.writeInt(0, 1, o);
                break;
            case BYTES:
                writeSized(f, (byte[]) v, w, c);
                break;
            case ARRAY:
            case LIST: {
                List<Object> list = (List<Object>) v;
                int n = list.size();
                if (f.length.mode == Size.Mode.FIXED && n > f.length.n) {
                    throw new IllegalArgumentException("'" + f.name + "': en fazla " + f.length.n + " öğe olabilir, " + n + " verildi");
                }
                if (f.length.mode == Size.Mode.PREFIX) writePrefix(f, n, w, c);
                int total = f.length.mode == Size.Mode.FIXED ? f.length.n : n;
                for (int i = 0; i < total; i++) {
                    if (f.kind == Kind.ARRAY) {
                        writeValue(f.element, i < n ? list.get(i) : Values.defaultValue(f.element), w, c);
                    } else {
                        writeStruct(f.struct.fields, i < n ? (Map<String, Object>) list.get(i) : Values.defaults(f.struct), w, c);
                    }
                }
                break;
            }
            case STRUCT:
                writeStruct(f.struct.fields, (Map<String, Object>) v, w, c);
                break;
            case BITS:
                w.writeInt(packBits(f, (Map<String, Object>) v), f.size, o);
                break;
            default:
                throw new IllegalStateException(f.kind.name());
        }
    }

    private static long bitMask(int width) { return width >= 64 ? -1L : (1L << width) - 1; }

    /** Bit grubunun alt alanlarini tek bir ham degere paketler. */
    private static long packBits(FieldDef f, Map<String, Object> values) {
        long raw = 0;
        for (FieldDef b : f.struct.fields) {
            Object x = values == null ? null : values.get(b.name);
            if (x == null) x = Values.defaultValue(b);
            long n;
            switch (b.kind) {
                case BOOL:
                    n = Boolean.TRUE.equals(x instanceof String ? Values.parse(b, (String) x) : x) ? 1 : 0;
                    break;
                case ENUM:
                    n = enumNumber(b, x.toString());
                    break;
                default:
                    n = Values.checkRange(b, toLong(b, x));
            }
            raw |= (n & bitMask(b.bitWidth)) << b.bitShift;
        }
        return raw;
    }

    private static long enumNumber(FieldDef f, String name) {
        FieldDef.EnumItem e = f.enumByName(name);
        if (e != null) return e.value;
        try {
            return Values.checkRange(f, Types.parseLong(name));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("'" + f.name + "': tanımsız enum değeri: " + name);
        }
    }

    private static long toLong(FieldDef f, Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return (Long) Values.parse(f, v.toString());
    }

    private static void writeSized(FieldDef f, byte[] b, Writer w, EncCtx c) {
        switch (f.length.mode) {
            case FIXED:
                if (b.length > f.length.n) {
                    throw new IllegalArgumentException("'" + f.name + "': en fazla " + f.length.n + " byte olabilir, " + b.length + " verildi");
                }
                w.write(b);
                w.zeros(f.length.n - b.length);
                break;
            case PREFIX:
                writePrefix(f, b.length, w, c);
                w.write(b);
                break;
            default:
                w.write(b);
        }
    }

    private static void writePrefix(FieldDef f, int n, Writer w, EncCtx c) {
        int size = f.length.n;
        if (size < 8 && n >= (1L << (8 * size))) {
            throw new IllegalArgumentException("'" + f.name + "': " + n + " öğe " + size + " byte'lık öneke sığmıyor");
        }
        w.writeInt(n, size, c.schema.order);
    }

    // =============================== cozme ===============================

    private static final class DecodeError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DecodeError(String message) { super(message, null, false, false); }
    }

    private static final class Reader {
        final byte[] a;
        int pos;
        final int limit;

        Reader(byte[] a, int pos, int limit) {
            this.a = a;
            this.pos = pos;
            this.limit = limit;
        }

        int remaining() { return limit - pos; }

        void need(int k, String path) {
            if (k > remaining()) {
                throw new DecodeError("mesaj eksik: '" + path + "' için " + k + " byte gerekiyor, " + remaining() + " kaldı");
            }
        }

        long readInt(int size, boolean signed, ByteOrder o, String path) {
            need(size, path);
            long v = 0;
            for (int i = 0; i < size; i++) {
                int shift = 8 * (o == ByteOrder.BIG_ENDIAN ? size - 1 - i : i);
                v |= (long) (a[pos + i] & 0xFF) << shift;
            }
            pos += size;
            if (signed && size < 8) {
                int sh = 64 - 8 * size;
                v = (v << sh) >> sh;
            }
            return v;
        }

        byte[] read(int k, String path) {
            need(k, path);
            byte[] b = Arrays.copyOfRange(a, pos, pos + k);
            pos += k;
            return b;
        }
    }

    /** Dogrulanacak otomatik alan. */
    private static final class Check {
        final FieldDef field;
        final int pos;
        final long value;
        final String path;

        Check(FieldDef field, int pos, long value, String path) {
            this.field = field;
            this.pos = pos;
            this.value = value;
            this.path = path;
        }
    }

    private static final class DecCtx {
        final Schema schema;
        final List<Decoded.Span> spans = new ArrayList<>();
        final List<Check> checks = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        DecCtx(Schema schema) { this.schema = schema; }
    }

    /** Hic istisna firlatmaz; sorunlar Decoded.error / warnings icindedir. */
    public static Decoded decode(Schema s, byte[] data) {
        Decoded d = new Decoded();
        DecCtx c = new DecCtx(s);
        try {
            decodeInto(s, data, d, c);
        } catch (DecodeError e) {
            d.error = e.getMessage();
        } catch (RuntimeException e) {
            d.error = "çözme hatası: " + e;
        }
        d.spans.addAll(c.spans);
        d.warnings.addAll(c.warnings);
        return d;
    }

    private static void decodeInto(Schema s, byte[] data, Decoded d, DecCtx c) {
        Reader r = new Reader(data, 0, data.length);
        readStruct(s.header.fields, d.header, r, c, "başlık.");
        int bodyStart = r.pos;
        int bodyEnd = data.length - s.footer.fixedSize();
        if (bodyEnd < bodyStart) throw new DecodeError("mesaj başlık ve son ek için çok kısa (" + data.length + " byte)");

        if (s.idField != null) {
            long id = ((Number) d.header.get(s.idField.name)).longValue();
            d.message = s.byId(id);
            if (d.message == null) {
                c.warnings.add("bu id ile tanımlı mesaj yok");
                throw new DecodeError("tanımsız mesaj id: " + id);
            }
            Reader b = new Reader(data, bodyStart, bodyEnd);
            readStruct(d.message.body.fields, d.body, b, c, "");
            if (b.pos < bodyEnd) c.warnings.add((bodyEnd - b.pos) + " byte artık veri (tanımda yok)");
        } else {
            // id alani yok: govdesi tam oturan ilk mesaj secilir
            for (MessageDef m : s.messages) {
                DecCtx trial = new DecCtx(s);
                Map<String, Object> values = new LinkedHashMap<>();
                Reader b = new Reader(data, bodyStart, bodyEnd);
                try {
                    readStruct(m.body.fields, values, b, trial, "");
                } catch (DecodeError e) {
                    continue;
                }
                if (b.pos != bodyEnd) continue;
                d.message = m;
                d.body.putAll(values);
                c.spans.addAll(trial.spans);
                c.checks.addAll(trial.checks);
                c.warnings.addAll(trial.warnings);
                break;
            }
            if (d.message == null) throw new DecodeError("hiçbir mesaj tanımına uymadı (" + data.length + " byte)");
        }

        Reader f = new Reader(data, bodyEnd, data.length);
        readStruct(s.footer.fields, d.footer, f, c, "son ek.");
        verify(s, data, bodyStart, bodyEnd, c);
    }

    private static void verify(Schema s, byte[] data, int bodyStart, int bodyEnd, DecCtx c) {
        for (Check k : c.checks) {
            FieldDef f = k.field;
            long expected;
            if (f.role.isLength()) {
                expected = lengthValue(s.lengthRole(f), data.length, bodyStart, bodyEnd, k.pos, f.size) + s.lengthAdjust(f);
                if (mask(expected, f.size) != mask(k.value, f.size)) {
                    c.warnings.add("'" + k.path + "' " + Long.toUnsignedString(mask(k.value, f.size))
                            + " diyor, gerçek uzunluk " + expected);
                }
            } else if (f.role.isChecksum()) {
                expected = Checksums.compute(s.checksumRole(f), data, Math.min(s.checksumFrom(f), k.pos), k.pos);
                if (mask(expected, f.size) != mask(k.value, f.size)) {
                    c.warnings.add("'" + k.path + "' uyuşmuyor: alınan 0x" + Long.toHexString(mask(k.value, f.size)).toUpperCase(java.util.Locale.ROOT)
                            + ", hesaplanan 0x" + Long.toHexString(mask(expected, f.size)).toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
    }

    private static long mask(long v, int size) {
        return size >= 8 ? v : v & ((1L << (8 * size)) - 1);
    }

    @SuppressWarnings("unchecked")
    private static void readStruct(List<FieldDef> fields, Map<String, Object> out, Reader r, DecCtx c, String prefix) {
        for (FieldDef f : fields) {
            String path = prefix + f.name;
            int start = r.pos;
            Object v = readValue(f, out, r, c, path);
            out.put(f.name, v);
            if (f.role != Role.NONE && f.role != Role.ID && f.role != Role.COUNT) {
                c.checks.add(new Check(f, start, ((Number) v).longValue(), path));
            }
            if (f.isLeaf() && f.defaultText != null && f.role == Role.NONE && !prefix.isEmpty()
                    && (prefix.startsWith("başlık.") || prefix.startsWith("son ek."))) {
                // baslik/son ekteki varsayilanli alanlar sabit kabul edilir (orn. senkron byte'lari)
                Object expected = Values.parse(f, f.defaultText);
                if (!Values.format(f, expected).equals(Values.format(f, v))) {
                    c.warnings.add("'" + path + "' beklenen " + Values.format(f, expected) + ", alınan " + Values.format(f, v));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readValue(FieldDef f, Map<String, Object> scope, Reader r, DecCtx c, String path) {
        ByteOrder o = c.schema.orderOf(f);
        int start = r.pos;
        Object v;
        switch (f.kind) {
            case INT:
                v = r.readInt(f.size, f.signed, o, path);
                break;
            case FLOAT: {
                long bits = r.readInt(f.size, false, o, path);
                v = f.size == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                break;
            }
            case BOOL:
                v = r.readInt(f.size, false, o, path) != 0;
                break;
            case ENUM: {
                long n = r.readInt(f.size, f.signed, o, path);
                FieldDef.EnumItem e = f.enumByValue(n);
                if (e == null) c.warnings.add("'" + path + "' tanımsız enum değeri: " + n);
                v = e != null ? e.name : Long.toString(n);
                c.spans.add(new Decoded.Span(path, start, r.pos - start, v + " (" + n + ")"));
                return v;
            }
            case STRING: {
                byte[] b = r.read(count(f, scope, r, c, path), path);
                int end = b.length;
                if (f.length.mode == Size.Mode.FIXED) {
                    for (int i = 0; i < b.length; i++) {
                        if (b[i] == 0) {
                            end = i;
                            break;
                        }
                    }
                }
                v = new String(b, 0, end, StandardCharsets.UTF_8);
                break;
            }
            case CSTRING: {
                int z = r.pos;
                while (z < r.limit && r.a[z] != 0) z++;
                if (z >= r.limit) throw new DecodeError("'" + path + "' sonlandırıcı sıfır byte bulunamadı");
                v = new String(r.a, r.pos, z - r.pos, StandardCharsets.UTF_8);
                r.pos = z + 1;
                break;
            }
            case BYTES:
                v = r.read(count(f, scope, r, c, path), path);
                break;
            case ARRAY:
            case LIST: {
                List<Object> list = new ArrayList<>();
                if (f.length.mode == Size.Mode.REST) {
                    for (int i = 0; r.pos < r.limit; i++) list.add(readElement(f, r, c, path + "[" + i + "]"));
                } else {
                    int n = count(f, scope, r, c, path);
                    for (int i = 0; i < n; i++) list.add(readElement(f, r, c, path + "[" + i + "]"));
                }
                return list; // elemanlar kendi araliklarini kaydetti
            }
            case STRUCT: {
                Map<String, Object> m = new LinkedHashMap<>();
                readStruct(f.struct.fields, m, r, c, path + ".");
                return m;
            }
            case BITS: {
                long raw = r.readInt(f.size, false, o, path);
                StringBuilder bin = new StringBuilder(Long.toBinaryString(raw));
                while (bin.length() < 8 * f.size) bin.insert(0, '0');
                for (int i = bin.length() - 4; i > 0; i -= 4) bin.insert(i, ' ');
                c.spans.add(new Decoded.Span(path, start, f.size, String.format("0x%0" + (2 * f.size) + "X", raw) + "  (" + bin + ")"));
                Map<String, Object> m = new LinkedHashMap<>();
                for (FieldDef b : f.struct.fields) {
                    long n = (raw >>> b.bitShift) & bitMask(b.bitWidth);
                    Object val;
                    String text;
                    if (b.kind == Kind.BOOL) {
                        val = n != 0;
                        text = String.valueOf(val);
                    } else if (b.kind == Kind.ENUM) {
                        FieldDef.EnumItem e = b.enumByValue(n);
                        if (e == null) c.warnings.add("'" + path + "." + b.name + "' tanımsız enum değeri: " + n);
                        val = e != null ? e.name : Long.toString(n);
                        text = val + " (" + n + ")";
                    } else {
                        if (b.signed && b.bitWidth < 64) n = (n << (64 - b.bitWidth)) >> (64 - b.bitWidth);
                        val = n;
                        text = Values.format(b, n);
                    }
                    m.put(b.name, val);
                    int lo = b.bitShift;
                    int hi = lo + b.bitWidth - 1;
                    c.spans.add(new Decoded.Span(path + "." + b.name, start, f.size,
                            text + "   [" + (lo == hi ? "bit " + lo : "bit " + lo + "-" + hi) + "]"));
                }
                return m;
            }
            default:
                throw new IllegalStateException(f.kind.name());
        }
        c.spans.add(new Decoded.Span(path, start, r.pos - start, Values.format(f, v)));
        return v;
    }

    private static Object readElement(FieldDef f, Reader r, DecCtx c, String path) {
        if (f.kind == Kind.ARRAY) return readValue(f.element, Collections.<String, Object>emptyMap(), r, c, path);
        Map<String, Object> m = new LinkedHashMap<>();
        readStruct(f.struct.fields, m, r, c, path + ".");
        return m;
    }

    /** Eleman (ya da byte) sayisini okur ve kalan veriyle tutarliligini denetler. */
    private static int count(FieldDef f, Map<String, Object> scope, Reader r, DecCtx c, String path) {
        long n;
        switch (f.length.mode) {
            case FIXED:
                n = f.length.n;
                break;
            case PREFIX:
                n = r.readInt(f.length.n, false, c.schema.order, path + " (adet)");
                break;
            case FIELD: {
                Object ref = scope.get(f.length.field);
                if (!(ref instanceof Number)) throw new DecodeError("'" + path + "' adet alanı okunamadı: " + f.length.field);
                n = ((Number) ref).longValue();
                break;
            }
            default: // REST: string/bytes kalanin tamami
                n = r.remaining();
        }
        int unit = f.kind == Kind.ARRAY ? f.element.fixedSize() : f.kind == Kind.LIST ? f.struct.fixedSize() : 1;
        long minBytes = unit > 0 ? n * unit : 0;
        if (n < 0 || n > MAX_COUNT || (unit != 0 && (unit > 0 ? minBytes : n) > r.remaining())) {
            throw new DecodeError("'" + path + "' adedi (" + n + ") kalan veriyle (" + r.remaining() + " byte) uyuşmuyor");
        }
        return (int) n;
    }
}
