package comm.schema;

import java.util.List;

/** Bir mesaj tipi: kimlik numarasi, adi ve govde alanlari. */
public final class MessageDef {
    public final long id;
    public final String name;
    public final StructDef body;

    public MessageDef(long id, String name) {
        this.id = id;
        this.name = name;
        this.body = new StructDef(name);
    }

    public List<FieldDef> fields() { return body.fields; }

    @Override public String toString() { return id + " - " + name; }
}
