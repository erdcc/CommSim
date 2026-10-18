package comm.schema;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Ilkel tip adlari, takma adlar ve sayi ayristirma. */
final class Types {
    static final class Num {
        final int size;
        final boolean signed;
        final boolean isFloat;
        final boolean isBool;
        /** Tip adinda le/be soneki varsa; yoksa null. */
        final ByteOrder order;

        Num(int size, boolean signed, boolean isFloat, boolean isBool, ByteOrder order) {
            this.size = size;
            this.signed = signed;
            this.isFloat = isFloat;
            this.isBool = isBool;
            this.order = order;
        }
    }

    private static final Map<String, Num> BASE = new HashMap<>();

    static {
        for (int bits : new int[]{8, 16, 32, 64}) {
            BASE.put("int" + bits, new Num(bits / 8, true, false, false, null));
            BASE.put("uint" + bits, new Num(bits / 8, false, false, false, null));
        }
        BASE.put("float32", new Num(4, true, true, false, null));
        BASE.put("float64", new Num(8, true, true, false, null));
        BASE.put("bool", new Num(1, false, false, true, null));
        // takma adlar (eski messages.def: int, double)
        BASE.put("int", BASE.get("int32"));
        BASE.put("uint", BASE.get("uint32"));
        BASE.put("byte", BASE.get("uint8"));
        BASE.put("short", BASE.get("int16"));
        BASE.put("ushort", BASE.get("uint16"));
        BASE.put("long", BASE.get("int64"));
        BASE.put("float", BASE.get("float32"));
        BASE.put("double", BASE.get("float64"));
    }

    private Types() { }

    /** Sayisal tip; "uint16le" gibi sonekli yazimi da tanir. Bilinmiyorsa null. */
    static Num num(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        Num n = BASE.get(t);
        if (n != null) return n;
        if (t.length() > 2 && (t.endsWith("le") || t.endsWith("be"))) {
            Num b = BASE.get(t.substring(0, t.length() - 2));
            if (b != null && b.size > 1) {
                return new Num(b.size, b.signed, b.isFloat, b.isBool,
                        t.endsWith("le") ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            }
        }
        return null;
    }

    static boolean isPrimitive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return num(n) != null || n.equals("string") || n.equals("bytes") || n.equals("cstring")
                || n.equals("enum") || n.equals("list") || n.equals("intlist") || n.startsWith("bits");
    }

    /** Ondalik, 0x onekli hex ya da 0b onekli ikili; eksi isaretli olabilir. uint64 icin isaretsiz tasar. */
    static long parseLong(String s) {
        String t = s.trim().replace("_", "");
        boolean neg = t.startsWith("-");
        if (neg || t.startsWith("+")) t = t.substring(1);
        long v;
        if (t.startsWith("0x") || t.startsWith("0X")) v = Long.parseUnsignedLong(t.substring(2), 16);
        else if (t.startsWith("0b") || t.startsWith("0B")) v = Long.parseUnsignedLong(t.substring(2), 2);
        else v = Long.parseUnsignedLong(t);
        return neg ? -v : v;
    }
}
