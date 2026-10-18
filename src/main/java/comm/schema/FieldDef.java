package comm.schema;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Sema dosyasindaki tek alan. Ayristirici tarafindan doldurulur, sonra degismez. */
public final class FieldDef {
    public enum Kind { INT, FLOAT, BOOL, ENUM, STRING, CSTRING, BYTES, ARRAY, LIST, STRUCT, BITS }

    /** Kodlayicinin kendisinin doldurdugu ozel alanlar. */
    public enum Role {
        NONE, ID, LENGTH_ALL, LENGTH_BODY, LENGTH_AFTER, SUM8, XOR8, CRC16, CRC16_CCITT, CRC32,
        /** Baska bir alanin eleman/byte sayisini tasir; gonderimde otomatik doldurulur. */
        COUNT;

        public boolean isChecksum() {
            return this == SUM8 || this == XOR8 || this == CRC16 || this == CRC16_CCITT || this == CRC32;
        }

        public boolean isLength() {
            return this == LENGTH_ALL || this == LENGTH_BODY || this == LENGTH_AFTER;
        }

        /** Degeri kullanici yerine kodlayici belirler. */
        public boolean isAuto() { return this != NONE; }
    }

    /** STRING/BYTES/ARRAY/LIST icin eleman ya da byte sayisinin nereden geldigi. */
    public static final class Size {
        public enum Mode { FIXED, PREFIX, FIELD, REST }

        public final Mode mode;
        /** FIXED: adet; PREFIX: onek byte genisligi. */
        public final int n;
        /** FIELD: adedi tasiyan (ayni kapsamdaki, onceki) alanin adi. */
        public final String field;

        private Size(Mode mode, int n, String field) {
            this.mode = mode;
            this.n = n;
            this.field = field;
        }

        public static Size fixed(int n) { return new Size(Mode.FIXED, n, null); }

        public static Size prefix(int bytes) { return new Size(Mode.PREFIX, bytes, null); }

        public static Size field(String name) { return new Size(Mode.FIELD, 0, name); }

        public static Size rest() { return new Size(Mode.REST, 0, null); }

        @Override public String toString() {
            switch (mode) {
                case FIXED: return String.valueOf(n);
                case PREFIX: return "uint" + (8 * n);
                case FIELD: return field;
                default: return "*";
            }
        }
    }

    public static final class EnumItem {
        public final String name;
        public final long value;

        public EnumItem(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }

    public String name;
    public Kind kind;
    /** INT/FLOAT/BOOL/ENUM: byte genisligi. */
    public int size;
    public boolean signed;
    /** null ise semanin varsayilan sirasi kullanilir. */
    public ByteOrder order;
    public Size length;
    public final List<EnumItem> enums = new ArrayList<>();
    /** LIST ve STRUCT icin. */
    public StructDef struct;
    /** ARRAY eleman tipi. */
    public FieldDef element;
    public Role role = Role.NONE;
    /** Checksum alanlari: hesaba katilmayan bastaki byte sayisi (orn. senkron byte'lari). */
    public int checksumFrom;
    /** Uzunluk alanlari: hesaplanan uzunluga eklenen deger (negatif olabilir). */
    public int lengthAdjust;

    // ---- bit gruplari ----
    /** BITS: alt alanlar MSB'den baslayarak mi yerlesir (varsayilan LSB, yani bit 0). */
    public boolean msbFirst;
    /** Bit alt alani: genislik (1..64); 0 ise bit alani degil. */
    public int bitWidth;
    /** Bit alt alani: tanim sirasina gore baslangic biti (bosluklar dahil). */
    public int bitOffset;
    /** Bit alt alani: ham degerdeki kaydirma (LSB'den), bit sirasi uygulanmis. */
    public int bitShift;
    /** Kaynak dosyadaki varsayilan deger metni; yoksa null. */
    public String defaultText;
    /** Dosyada yazildigi hali (tip ifadesi), gosterim icin. */
    public String typeText;
    public int line;

    public boolean isLeaf() {
        return kind != Kind.ARRAY && kind != Kind.LIST && kind != Kind.STRUCT && kind != Kind.BITS;
    }

    public EnumItem enumByName(String n) {
        for (EnumItem e : enums) if (e.name.equals(n)) return e;
        return null;
    }

    public EnumItem enumByValue(long v) {
        for (EnumItem e : enums) if (e.value == v) return e;
        return null;
    }

    /** Sabit byte boyu; degiskense -1. */
    public int fixedSize() {
        switch (kind) {
            case INT: case FLOAT: case BOOL: case ENUM: case BITS:
                return size;
            case STRING: case BYTES:
                return length.mode == Size.Mode.FIXED ? length.n : -1;
            case ARRAY: {
                int e = element.fixedSize();
                return length.mode == Size.Mode.FIXED && e >= 0 ? length.n * e : -1;
            }
            case LIST: {
                int e = struct.fixedSize();
                return length.mode == Size.Mode.FIXED && e >= 0 ? length.n * e : -1;
            }
            case STRUCT:
                return struct.fixedSize();
            default:
                return -1;
        }
    }

    @Override public String toString() { return typeText + " " + name; }
}
