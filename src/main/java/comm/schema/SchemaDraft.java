package comm.schema;

import comm.schema.FieldDef.Kind;
import comm.schema.FieldDef.Role;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Gorsel sema duzenleyicinin degistirilebilir modeli. Bir Schema'dan uretilir, .def metnine yazilir;
 * dogrulama metni SchemaParser'dan gecirerek yapilir. Kaynak dosyadaki yorumlar korunmaz.
 */
public final class SchemaDraft {
    /** Tablodaki bir alan satiri; tum hucreler .def'teki yazimiyla metindir. */
    public static final class Field {
        /** Tip ifadesi: uint16, uint16le, string[16], bytes[*], enum, enum:uint16, list STRUCT, STRUCT[count] ... */
        public String type = "uint8";
        public String name = "alan";
        /** '@' olmadan rol: id, length(all,adjust=-1), crc16(from=1) ... bos olabilir. */
        public String role = "";
        /** Varsayilan deger; bos ise yok. */
        public String def = "";
        /** Yalnizca enum: A|B|C ya da A=1|B=5 */
        public String enums = "";
        /** Yalnizca bit grubu (bits8/16/32/64): alt alanlar; padN satirlari adsizdir. */
        public final List<Field> bits = new ArrayList<>();

        public Field() { }

        public Field(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public Field copy() {
            Field f = new Field(type, name);
            f.role = role;
            f.def = def;
            f.enums = enums;
            for (Field b : bits) f.bits.add(b.copy());
            return f;
        }

        public boolean isBits() { return type.trim().toLowerCase(java.util.Locale.ROOT).startsWith("bits"); }

        public boolean isPad() { return type.trim().toLowerCase(java.util.Locale.ROOT).matches("pad\\d+"); }

        public boolean isEnum() { return type.trim().toLowerCase(java.util.Locale.ROOT).startsWith("enum"); }
    }

    public enum BlockKind { HEADER, FOOTER, STRUCT, MESSAGE }

    public static final class Block {
        public final BlockKind kind;
        public String name;
        /** Yalnizca mesaj: ondalik ya da 0x.. */
        public String id = "";
        public final List<Field> fields = new ArrayList<>();

        public Block(BlockKind kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        @Override public String toString() {
            switch (kind) {
                case HEADER: return "Başlık (header)";
                case FOOTER: return "Son ek (footer)";
                case STRUCT: return "struct " + name;
                default: return "mesaj " + id + " - " + name;
            }
        }
    }

    /** "big" ya da "little". */
    public String endian = "big";
    public final Block header = new Block(BlockKind.HEADER, "header");
    public final Block footer = new Block(BlockKind.FOOTER, "footer");
    public final List<Block> structs = new ArrayList<>();
    public final List<Block> messages = new ArrayList<>();

    /** Yeni sema icin baslangic: 2 byte id'li baslik ve bir ornek mesaj. */
    public static SchemaDraft starter() {
        SchemaDraft d = new SchemaDraft();
        Field id = new Field("uint16", "id");
        id.role = "id";
        d.header.fields.add(id);
        Block m = new Block(BlockKind.MESSAGE, "MESAJ1");
        m.id = "1";
        m.fields.add(new Field("uint16", "deger"));
        d.messages.add(m);
        return d;
    }

    public static SchemaDraft from(Schema s) {
        SchemaDraft d = new SchemaDraft();
        d.endian = s.order == ByteOrder.LITTLE_ENDIAN ? "little" : "big";
        copy(s.header, d.header);
        copy(s.footer, d.footer);
        for (StructDef st : s.structs.values()) {
            Block b = new Block(BlockKind.STRUCT, st.name);
            copy(st, b);
            d.structs.add(b);
        }
        for (MessageDef m : s.messages) {
            Block b = new Block(BlockKind.MESSAGE, m.name);
            b.id = Long.toString(m.id);
            copy(m.body, b);
            d.messages.add(b);
        }
        return d;
    }

    private static void copy(StructDef s, Block b) {
        for (FieldDef f : s.fields) {
            Field x = new Field(f.typeText, f.name);
            x.role = roleText(f);
            x.def = f.defaultText == null ? "" : f.defaultText;
            if (f.kind == Kind.ENUM) {
                StringBuilder sb = new StringBuilder();
                for (FieldDef.EnumItem e : f.enums) {
                    if (sb.length() > 0) sb.append('|');
                    sb.append(e.name).append('=').append(e.value);
                }
                x.enums = sb.toString();
            }
            if (f.kind == Kind.BITS) {
                int cursor = 0;
                for (FieldDef sub : f.struct.fields) {
                    if (sub.bitOffset > cursor) x.bits.add(new Field("pad" + (sub.bitOffset - cursor), ""));
                    Field c = new Field(sub.typeText, sub.name);
                    c.def = sub.defaultText == null ? "" : sub.defaultText;
                    if (sub.kind == Kind.ENUM) c.enums = enumText(sub);
                    x.bits.add(c);
                    cursor = sub.bitOffset + sub.bitWidth;
                }
            }
            b.fields.add(x);
        }
    }

    private static String enumText(FieldDef f) {
        StringBuilder sb = new StringBuilder();
        for (FieldDef.EnumItem e : f.enums) {
            if (sb.length() > 0) sb.append('|');
            sb.append(e.name).append('=').append(e.value);
        }
        return sb.toString();
    }

    /** FieldDef rolunun .def yazimi ('@' olmadan); adet rolu ortuk oldugu icin yazilmaz. */
    static String roleText(FieldDef f) {
        switch (f.role) {
            case ID: return "id";
            case LENGTH_BODY:
            case LENGTH_ALL:
            case LENGTH_AFTER: {
                String where = f.role == Role.LENGTH_ALL ? "all" : f.role == Role.LENGTH_AFTER ? "after" : "body";
                if (f.lengthAdjust == 0) return f.role == Role.LENGTH_BODY ? "length" : "length(" + where + ")";
                return "length(" + where + ",adjust=" + f.lengthAdjust + ")";
            }
            case SUM8: case XOR8: case CRC16: case CRC16_CCITT: case CRC32: {
                String n = f.role == Role.CRC16_CCITT ? "crc16ccitt" : f.role.name().toLowerCase(java.util.Locale.ROOT);
                return f.checksumFrom == 0 ? n : n + "(from=" + f.checksumFrom + ")";
            }
            default:
                return "";
        }
    }

    public List<Block> allBlocks() {
        List<Block> out = new ArrayList<>();
        out.add(header);
        out.add(footer);
        out.addAll(structs);
        out.addAll(messages);
        return out;
    }

    /** .def metni. Her blok ve satir, ayristiricinin bekledigi bicimdedir. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Şema düzenleyici ile oluşturuldu. Söz dizimi için schemas/ornek.def dosyasına bakın.\n\n");
        sb.append("endian ").append(endian).append("\n\n");
        block(sb, "header", header);
        if (!footer.fields.isEmpty()) block(sb, "footer", footer);
        for (Block b : structs) block(sb, "struct " + b.name.trim(), b);
        for (Block b : messages) block(sb, "message " + b.id.trim() + " " + b.name.trim(), b);
        return sb.toString();
    }

    private static void block(StringBuilder sb, String title, Block b) {
        sb.append(title).append('\n');
        for (Field f : b.fields) {
            sb.append("  ").append(line(f)).append('\n');
            if (f.isBits()) for (Field c : f.bits) sb.append("    ").append(line(c)).append('\n');
        }
        sb.append('\n');
    }

    /** Tek alan satiri: tip ad [enum degerleri] [@rol] [= varsayilan] */
    public static String line(Field f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.type.trim());
        if (f.isPad()) return sb.toString();
        sb.append(' ').append(f.name.trim());
        if (f.isEnum()) sb.append(' ').append(f.enums.trim().replace(" ", ""));
        if (!f.role.trim().isEmpty()) sb.append(" @").append(f.role.trim().replace(" ", "").replaceFirst("^@", ""));
        if (!f.def.isEmpty()) sb.append(" = ").append(f.def);
        return sb.toString();
    }
}
