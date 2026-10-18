package comm.schema;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir sema dosyasinin tamami: varsayilan byte sirasi, baslik, son ek, struct'lar ve mesajlar.
 * Kodlama/cozme {@link Codec} ile yapilir.
 */
public final class Schema {
    public final String source;
    public ByteOrder order = ByteOrder.BIG_ENDIAN;
    public final StructDef header;
    public final StructDef footer;
    final Map<String, StructDef> structs;
    final List<MessageDef> messages;
    /** Baslikta @id rolundeki alan; yoksa null. */
    FieldDef idField;
    /** Baglantiya ozel gecersiz kilmalar; yoksa null. */
    private final SchemaOverrides overrides;

    Schema(String source) {
        this.source = source;
        header = new StructDef("başlık");
        footer = new StructDef("son ek");
        structs = new LinkedHashMap<>();
        messages = new ArrayList<>();
        overrides = null;
    }

    /** Ayni tanimlari (MessageDef nesneleri dahil) paylasan, ayarlari gecersiz kilinmis kopya. */
    private Schema(Schema base, SchemaOverrides o) {
        source = base.source;
        header = base.header;
        footer = base.footer;
        structs = base.structs;
        messages = base.messages;
        idField = base.idField;
        overrides = o.copy();
        order = o.byteOrder.equals("LITTLE") ? ByteOrder.LITTLE_ENDIAN
                : o.byteOrder.equals("BIG") ? ByteOrder.BIG_ENDIAN : base.order;
    }

    /** Gecersiz kilma bossa ayni semayi, degilse paylasimli bir kopya dondurur. */
    public Schema withOverrides(SchemaOverrides o) {
        if (o == null || o.isEmpty()) return this;
        return new Schema(this, o);
    }

    public SchemaOverrides overrides() { return overrides == null ? null : overrides.copy(); }

    public List<MessageDef> messages() { return Collections.unmodifiableList(messages); }

    public MessageDef byId(long id) {
        for (MessageDef m : messages) if (m.id == id) return m;
        return null;
    }

    public MessageDef byName(String name) {
        for (MessageDef m : messages) if (m.name.equals(name)) return m;
        return null;
    }

    public FieldDef idField() { return idField; }

    ByteOrder orderOf(FieldDef f) {
        if (f.order != null && !(overrides != null && overrides.forceAllFields && !overrides.byteOrder.isEmpty())) return f.order;
        return order;
    }

    /** Uzunluk alaninin etkin rolu (gecersiz kilma varsa o). */
    FieldDef.Role lengthRole(FieldDef f) {
        return overrides != null && !overrides.length.isEmpty() ? FieldDef.Role.valueOf(overrides.length) : f.role;
    }

    int lengthAdjust(FieldDef f) {
        return overrides != null && overrides.lengthAdjust != null ? overrides.lengthAdjust : f.lengthAdjust;
    }

    FieldDef.Role checksumRole(FieldDef f) {
        return overrides != null && !overrides.checksum.isEmpty() ? FieldDef.Role.valueOf(overrides.checksum) : f.role;
    }

    int checksumFrom(FieldDef f) {
        return overrides != null && overrides.checksumFrom != null ? overrides.checksumFrom : f.checksumFrom;
    }
}
