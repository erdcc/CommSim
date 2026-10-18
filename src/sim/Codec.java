package sim;

import sim.Model.FieldDef;
import sim.Model.Message;
import sim.Model.MessageDef;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ikili kodlama (big-endian): u16 mesaj-id, sonra alanlar tanim sirasiyla.
 * int=4B, double=8B, bool=1B, enum=1B (sira no), string=u16 uzunluk + UTF-8,
 * intlist=u16 adet + adet x int32, list=u16 adet + adet x (struct alanlari sirasiyla).
 */
public final class Codec {
    private final List<MessageDef> defs;

    public Codec(List<MessageDef> defs) { this.defs = defs; }

    public byte[] encode(Message m) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeShort(m.def().id());
            writeFields(out, m.def().fields(), m.values());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // bellek ici akis, olmamali
        }
    }

    private static void writeFields(DataOutputStream out, List<FieldDef> fields, Map<String, Object> values)
            throws IOException {
        for (FieldDef f : fields) {
            Object v = values.get(f.name());
            switch (f.type()) {
                case INT -> out.writeInt((Integer) v);
                case DOUBLE -> out.writeDouble((Double) v);
                case BOOL -> out.writeBoolean((Boolean) v);
                case ENUM -> out.writeByte(f.enumValues().indexOf((String) v));
                case INTLIST -> {
                    List<?> ids = (List<?>) v;
                    checkCount(f, ids.size());
                    out.writeShort(ids.size());
                    for (Object id : ids) out.writeInt((Integer) id);
                }
                case LIST -> {
                    List<?> records = (List<?>) v;
                    checkCount(f, records.size());
                    out.writeShort(records.size());
                    for (Object r : records) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rec = (Map<String, Object>) r;
                        writeFields(out, f.struct().fields(), rec);
                    }
                }
                case STRING -> {
                    byte[] b = ((String) v).getBytes(StandardCharsets.UTF_8);
                    if (b.length > 0xFFFF) throw new IllegalArgumentException("'" + f.name() + "' cok uzun");
                    out.writeShort(b.length);
                    out.write(b);
                }
            }
        }
    }

    private static void checkCount(FieldDef f, int n) {
        if (n > 0xFFFF) throw new IllegalArgumentException("'" + f.name() + "' cok uzun");
    }

    public Message decode(byte[] data, int length) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, length));
            int id = in.readUnsignedShort();
            MessageDef def = defs.stream().filter(d -> d.id() == id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("taninmayan mesaj id: " + id));
            return new Message(def, readFields(in, def.fields()));
        } catch (IOException e) {
            throw new IllegalArgumentException("mesaj eksik/bozuk (" + length + " bayt)");
        }
    }

    private static Map<String, Object> readFields(DataInputStream in, List<FieldDef> fields) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldDef f : fields) {
            Object v = switch (f.type()) {
                case INT -> in.readInt();
                case DOUBLE -> in.readDouble();
                case BOOL -> in.readBoolean();
                case ENUM -> {
                    int idx = in.readUnsignedByte();
                    if (idx >= f.enumValues().size()) throw new IllegalArgumentException("gecersiz enum: " + idx);
                    yield f.enumValues().get(idx);
                }
                case INTLIST -> {
                    int n = in.readUnsignedShort();
                    List<Integer> ids = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) ids.add(in.readInt());
                    yield ids;
                }
                case LIST -> {
                    int n = in.readUnsignedShort();
                    List<Map<String, Object>> records = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) records.add(readFields(in, f.struct().fields()));
                    yield records;
                }
                case STRING -> {
                    byte[] b = new byte[in.readUnsignedShort()];
                    in.readFully(b);
                    yield new String(b, StandardCharsets.UTF_8);
                }
            };
            values.put(f.name(), v);
        }
        return values;
    }

    public static String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(i % 16 == 0 ? '\n' : ' ');
            sb.append(String.format("%02X", data[i]));
        }
        return sb.toString();
    }
}
