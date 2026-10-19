package comm.ui.editor;

import comm.schema.FieldDef;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.StructDef;

import java.util.ArrayList;
import java.util.List;

/** Editorun bildigi bir baglanti: ad, tur ve (varsa) mesaj semasi. EDT'de anlik kopya olarak alinir. */
public final class Target {
    public final String name;
    /** Gorunen tur, orn. "TCP İSTEMCİ". */
    public final String kind;
    /** null ise sema yok. */
    public final Schema schema;
    public final boolean modbusMaster, modbusSlave, snmpManager, snmpAgent;

    public Target(String name, String kind, Schema schema, boolean modbusMaster, boolean modbusSlave,
                  boolean snmpManager, boolean snmpAgent) {
        this.name = name;
        this.kind = kind;
        this.schema = schema;
        this.modbusMaster = modbusMaster;
        this.modbusSlave = modbusSlave;
        this.snmpManager = snmpManager;
        this.snmpAgent = snmpAgent;
    }

    /** Bir mesaj alani: yol, tip ve (enum ise) secenekler. */
    static final class Field {
        final String path;
        final String type;
        final List<String> enums;
        final String def;

        Field(String path, String type, List<String> enums, String def) {
            this.path = path;
            this.type = type;
            this.enums = enums;
            this.def = def;
        }
    }

    /** Mesajin atanabilir alanlari (baslik dahil); listelerde ilk eleman [0] ornek alinir. */
    static List<Field> fields(Schema s, MessageDef m) {
        List<Field> out = new ArrayList<>();
        if (s != null) walk(s.header, "başlık.", out);
        walk(m.body, "", out);
        return out;
    }

    private static void walk(StructDef s, String prefix, List<Field> out) {
        for (FieldDef f : s.fields) {
            if (f.role.isAuto()) continue;
            String p = prefix + f.name;
            switch (f.kind) {
                case STRUCT:
                case BITS:
                    walk(f.struct, p + ".", out);
                    break;
                case LIST:
                    out.add(new Field(p, "adet (" + f.struct.name + " listesi)", null, null));
                    walk(f.struct, p + "[0].", out);
                    break;
                case ARRAY:
                    out.add(new Field(p, "adet (" + f.element.typeText + " dizisi)", null, null));
                    out.add(new Field(p + "[0]", f.element.typeText, null, null));
                    break;
                default: {
                    List<String> en = null;
                    if (f.kind == FieldDef.Kind.ENUM) {
                        en = new ArrayList<>();
                        for (FieldDef.EnumItem e : f.enums) en.add(e.name);
                    }
                    if (f.kind == FieldDef.Kind.BOOL) {
                        en = new ArrayList<>();
                        en.add("true");
                        en.add("false");
                    }
                    out.add(new Field(p, f.typeText, en, f.defaultText));
                }
            }
        }
    }

    static MessageDef message(Schema s, String name) {
        if (s == null || name == null) return null;
        for (MessageDef m : s.messages()) if (m.name.equalsIgnoreCase(name)) return m;
        return null;
    }
}
