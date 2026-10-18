package comm.schema;

import comm.core.Hex;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecTest {
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String hex(byte[] b) { return Hex.toHex(b); }

    // ---- eski messages.def ile uyum ----

    @Test void legacyMessagesDefKeepsWireFormat() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("messages.def"));
        assertEquals(6, s.messages().size());
        MessageDef text = s.byName("TEXT_MESSAGE");
        Map<String, Object> body = Values.defaults(text.body);
        assertEquals("SIM", body.get("sender"));
        body.put("nodeId", 1L);
        byte[] b = Codec.encode(s, text, null, body);
        // u16 id | int32 nodeId | enum 1B | string u16+utf8 | string u16+utf8
        assertEquals("00 03 00 00 00 01 00 00 03 53 49 4D 00 07 4D 65 72 68 61 62 61", hex(b));

        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok(), d.error);
        assertEquals("TEXT_MESSAGE", d.message.name);
        assertEquals("Merhaba", d.body.get("text"));
        assertTrue(d.warnings.isEmpty(), d.warnings.toString());
    }

    @Test void legacyListOfStructs() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("messages.def"));
        MessageDef m = s.byName("YAYIN_LISTESI");
        Map<String, Object> body = Values.defaults(m.body);
        List<Object> radars = new ArrayList<>();
        Map<String, Object> r = Values.defaults(s.structs.get("RADAR_KAYDI"));
        r.put("nodeId", 7L);
        r.put("name", "R1");
        r.put("identity", "HOSTILE");
        r.put("lat", 39.5);
        radars.add(r);
        body.put("radars", radars);
        byte[] b = Codec.encode(s, m, null, body);
        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok(), d.error);
        @SuppressWarnings("unchecked")
        Map<String, Object> got = (Map<String, Object>) ((List<?>) d.body.get("radars")).get(0);
        assertEquals(7L, got.get("nodeId"));
        assertEquals("HOSTILE", got.get("identity"));
        assertEquals(39.5, got.get("lat"));
        // 2 id + 4 nodeId + 1 enum + 2 adet + (4 + 2+2 + 1 + 8*3)
        assertEquals(2 + 4 + 1 + 2 + 33, b.length);
    }

    // ---- baslik / son ek / checksum ----

    @Test void modbusRtuCrcMatchesKnownFrame() throws Exception {
        Schema s = SchemaParser.parse(
                "header\n"
                        + "footer\n"
                        + "  uint16le crc @crc16\n"
                        + "message 3 READ_HOLDING\n"
                        + "  uint8  slave = 1\n"
                        + "  uint8  func  = 3\n"
                        + "  uint16 start\n"
                        + "  uint16 count = 10\n", "rtu");
        assertNull(s.idField());
        MessageDef m = s.byName("READ_HOLDING");
        byte[] b = Codec.encode(s, m, null, Values.defaults(m.body));
        assertEquals("01 03 00 00 00 0A C5 CD", hex(b));
        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok(), d.error);
        assertTrue(d.warnings.isEmpty(), d.warnings.toString());

        b[2] = 1; // veri bozuldu
        d = Codec.decode(s, b);
        assertTrue(d.ok());
        assertEquals(1, d.warnings.size());
        assertTrue(d.warnings.get(0).contains("crc"), d.warnings.get(0));
    }

    private static final String RICH =
            "endian little\n"
                    + "header\n"
                    + "  uint8  sync = 0xAA\n"
                    + "  uint16 msgId @id\n"
                    + "  uint16 len   @length(all)\n"
                    + "footer\n"
                    + "  uint8 chk @xor8\n"
                    + "struct POINT\n"
                    + "  float32 x\n"
                    + "  float32 y\n"
                    + "struct TAG\n"
                    + "  cstring label\n"
                    + "  int8    level\n"
                    + "message 0x10 STATUS\n"
                    + "  enum:uint16be mode IDLE=0|RUN=5|STOP\n"
                    + "  int64   big\n"
                    + "  uint64be huge\n"
                    + "  string[8] name = abc\n"
                    + "  POINT   pos\n"
                    + "  uint8   n\n"
                    + "  uint16[n] regs\n"
                    + "  TAG[uint8] tags\n"
                    + "  bool    on = true\n"
                    + "  bytes[*] tail\n"
                    + "message 0x11 PING\n";

    @Test void richRoundTrip() throws Exception {
        Schema s = SchemaParser.parse(RICH, "rich");
        MessageDef m = s.byName("STATUS");
        assertEquals(0x10, m.id);
        Map<String, Object> body = Values.defaults(m.body);
        assertEquals("IDLE", body.get("mode"));
        body.put("mode", "STOP");
        body.put("big", -2L);
        body.put("huge", -1L); // 0xFFFF...FF
        body.put("pos", map("x", 1.5, "y", -2.25));
        body.put("regs", new ArrayList<Object>(Arrays.asList(1L, 0xFFFFL, 3L)));
        List<Object> tags = new ArrayList<>();
        tags.add(map("label", "ön", "level", -3L));
        tags.add(map("label", "", "level", 4L));
        body.put("tags", tags);
        body.put("tail", Hex.parse("DE AD"));

        byte[] b = Codec.encode(s, m, null, body);
        assertEquals((byte) 0xAA, b[0]);
        assertEquals(0x10, b[1]);
        assertEquals(b.length, (b[3] & 0xFF) | (b[4] & 0xFF) << 8);
        assertEquals("00 06", hex(Arrays.copyOfRange(b, 5, 7))); // STOP = 6, big endian

        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok(), d.error);
        assertTrue(d.warnings.isEmpty(), d.warnings.toString());
        assertEquals("STOP", d.body.get("mode"));
        assertEquals(-2L, d.body.get("big"));
        assertEquals("18446744073709551615", Values.format(m.body.field("huge"), d.body.get("huge")));
        assertEquals("abc", d.body.get("name"));
        assertEquals(map("x", 1.5, "y", -2.25), d.body.get("pos"));
        assertEquals(3L, d.body.get("n"));
        assertEquals(Arrays.asList(1L, 0xFFFFL, 3L), d.body.get("regs"));
        assertEquals(tags, d.body.get("tags"));
        assertEquals(Boolean.TRUE, d.body.get("on"));
        assertArrayEquals(Hex.parse("DE AD"), (byte[]) d.body.get("tail"));
        assertNotNull(d.spans.stream().filter(x -> x.path.equals("tags[0].label")).findFirst().orElse(null));

        // bos govdeli mesaj
        byte[] ping = Codec.encode(s, s.byName("PING"), null, null);
        assertEquals("AA 11 00 06 00", hex(Arrays.copyOf(ping, 5)));
        assertTrue(Codec.decode(s, ping).ok());
    }

    @Test void decodeReportsProblems() throws Exception {
        Schema s = SchemaParser.parse(RICH, "rich");
        byte[] ping = Codec.encode(s, s.byName("PING"), null, null);

        byte[] badSync = ping.clone();
        badSync[0] = 0x55;
        Decoded d = Codec.decode(s, badSync);
        assertTrue(d.warnings.stream().anyMatch(w -> w.contains("sync")), d.warnings.toString());

        byte[] unknown = ping.clone();
        unknown[1] = 0x7F;
        d = Codec.decode(s, unknown);
        assertNull(d.message);
        assertTrue(d.error.contains("127"), d.error);

        MessageDef m = s.byName("STATUS");
        byte[] full = Codec.encode(s, m, null, Values.defaults(m.body));
        d = Codec.decode(s, Arrays.copyOf(full, 12));
        assertNotNull(d.error);
        assertEquals("STATUS", d.message.name);
        assertEquals("IDLE", d.body.get("mode")); // kismi degerler korunur
    }

    @Test void encodeRejectsOutOfRange() throws Exception {
        Schema s = SchemaParser.parse(RICH, "rich");
        MessageDef m = s.byName("STATUS");
        Map<String, Object> body = Values.defaults(m.body);
        body.put("regs", new ArrayList<Object>(Arrays.asList(70000L)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Codec.encode(s, m, null, body));
        assertTrue(e.getMessage().contains("eleman"), e.getMessage());

        body.put("regs", new ArrayList<>());
        body.put("name", "çok uzun bir ad");
        assertThrows(IllegalArgumentException.class, () -> Codec.encode(s, m, null, body));
    }

    // ---- ayristirici ----

    @Test void parserErrorsCarryLineNumbers() {
        SchemaException e = assertThrows(SchemaException.class,
                () -> SchemaParser.parse("message 1 A\n  int32 x\n  float17 y\n", "f.def"));
        assertTrue(e.getMessage().startsWith("f.def:3:"), e.getMessage());

        e = assertThrows(SchemaException.class,
                () -> SchemaParser.parse("message 1 A\n  bytes[*] x\n  int8 y\n", "f.def"));
        assertTrue(e.getMessage().contains("[*]"), e.getMessage());

        e = assertThrows(SchemaException.class,
                () -> SchemaParser.parse("message 1 A\n  uint8 x = 300\n", "f.def"));
        assertTrue(e.getMessage().contains("aralık"), e.getMessage());

        e = assertThrows(SchemaException.class,
                () -> SchemaParser.parse("message 1 A\nmessage 1 B\n", "f.def"));
        assertTrue(e.getMessage().contains("tekrar"), e.getMessage());

        assertThrows(SchemaException.class, () -> SchemaParser.parse("# bos\n", "f.def"));
    }

    // ---- duzlestirme ----

    @Test void flattenEditsAndResizes() throws Exception {
        Schema s = SchemaParser.parse(RICH, "rich");
        MessageDef m = s.byName("STATUS");
        Map<String, Object> body = Values.complete(m.body, new LinkedHashMap<>());
        List<Values.Row> rows = Values.flatten(m.body, body, "", true);
        Values.Row tags = rows.stream().filter(r -> r.path.equals("tags")).findFirst().get();
        assertEquals(Values.Row.Type.COUNT, tags.type);
        tags.setText("2");
        rows = Values.flatten(m.body, body, "", true);
        Values.Row level = rows.stream().filter(r -> r.path.equals("tags[1].level")).findFirst().get();
        level.setText("-5");
        assertEquals(-5L, ((Map<?, ?>) ((List<?>) body.get("tags")).get(1)).get("level"));
        Values.Row n = rows.stream().filter(r -> r.path.equals("n")).findFirst().get();
        assertEquals(Values.Row.Type.AUTO, n.type);
        assertTrue(!n.editable());
        Values.Row name = rows.stream().filter(r -> r.path.equals("name")).findFirst().get();
        assertThrows(IllegalArgumentException.class, () -> name.setText("123456789"));
    }

    // ---- ornek sema ----

    @Test void sampleSchemaRoundTripsEveryMessage() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        assertEquals(8, s.messages().size());
        for (MessageDef m : s.messages()) {
            byte[] b = Codec.encode(s, m, Values.defaults(s.header), Values.defaults(m.body));
            Decoded d = Codec.decode(s, b);
            assertTrue(d.ok(), m.name + ": " + d.error);
            assertTrue(d.warnings.isEmpty(), m.name + ": " + d.warnings);
            assertEquals(m, d.message);
        }
        MessageDef k = s.byName("KOMUT");
        Map<String, Object> body = Values.defaults(k.body);
        assertEquals("HOLD", body.get("command"));
        byte[] b = Codec.encode(s, k, null, body);
        assertEquals("A5 01 00 04", hex(Arrays.copyOf(b, 4)));
        assertEquals(b.length - 8 - 2, ((b[6] & 0xFF) << 8) | (b[7] & 0xFF)); // @length = govde
    }

    // ---- rol parametreleri ve baglanti bazinda gecersiz kilma ----

    private static final String FRAMED =
            "header\n"
                    + "  uint8  sync = 0xAA\n"
                    + "  uint8  msgId @id\n"
                    + "  uint16 len   @length(all,adjust=-1)\n"
                    + "footer\n"
                    + "  uint16 crc @crc16(from=1)\n"
                    + "message 1 VERI\n"
                    + "  uint16 a = 0x0102\n";

    @Test void roleParametersShapeLengthAndChecksum() throws Exception {
        Schema s = SchemaParser.parse(FRAMED, "f");
        MessageDef m = s.byName("VERI");
        byte[] b = Codec.encode(s, m, null, null);
        assertEquals(8, b.length);
        assertEquals("AA 01 00 07 01 02", hex(Arrays.copyOf(b, 6))); // 8 - 1
        // crc sync byte'ini kapsamaz: [1, 6)
        long crc = Checksums.compute(FieldDef.Role.CRC16, b, 1, 6);
        assertEquals(crc, ((b[6] & 0xFF) << 8) | (b[7] & 0xFF));
        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok() && d.warnings.isEmpty(), d.warnings + " " + d.error);

        SchemaException e = assertThrows(SchemaException.class,
                () -> SchemaParser.parse("header\n  uint8 x @crc16(start=1)\nmessage 1 A\n", "g"));
        assertTrue(e.getMessage().contains("parametre"), e.getMessage());
    }

    @Test void overridesChangeOrderChecksumAndLengthPerConnection() throws Exception {
        Schema base = SchemaParser.parse(FRAMED, "f");
        MessageDef m = base.byName("VERI");
        SchemaOverrides o = new SchemaOverrides();
        o.byteOrder = "LITTLE";
        o.checksum = "XOR8";
        o.checksumFrom = 0;
        o.length = "LENGTH_BODY";
        o.lengthAdjust = 0;
        Schema v = base.withOverrides(o);
        assertTrue(v.byName("VERI") == m, "mesaj tanımları paylaşılmalı");
        byte[] b = Codec.encode(v, m, null, null);
        assertEquals("AA 01 02 00 02 01", hex(Arrays.copyOf(b, 6))); // LE uzunluk=gövde(2), LE a
        long x = Checksums.compute(FieldDef.Role.XOR8, b, 0, 6);
        assertEquals(x, (b[6] & 0xFF) | ((b[7] & 0xFF) << 8));
        assertTrue(Codec.decode(v, b).warnings.isEmpty());
        // ayni cerceve asil semayla cozulunce uyusmazliklar bildirilir
        assertTrue(Codec.decode(base, b).warnings.size() >= 2, Codec.decode(base, b).warnings.toString());
        assertTrue(base.withOverrides(new SchemaOverrides()) == base);

        // alan soneki varsayilan olarak korunur, forceAllFields ile ezilir
        Schema le = SchemaParser.parse("message 1 A\n  uint16be x = 1\n", "h");
        SchemaOverrides lo = new SchemaOverrides();
        lo.byteOrder = "LITTLE";
        byte[] keep = Codec.encode(le.withOverrides(lo), le.byName("A"), null, null);
        assertEquals("00 01", hex(Arrays.copyOfRange(keep, 2, 4)));
        lo.forceAllFields = true;
        byte[] forced = Codec.encode(le.withOverrides(lo), le.byName("A"), null, null);
        assertEquals("01 00", hex(Arrays.copyOfRange(forced, 2, 4)));
        assertEquals("01 00", hex(Arrays.copyOf(forced, 2))); // id de LE
    }

    // ---- gorsel duzenleyici modeli ----

    @Test void draftRoundTripKeepsWireFormat() throws Exception {
        for (String text : new String[]{new String(java.nio.file.Files.readAllBytes(Paths.get("schemas/ornek.def")), "UTF-8"),
                new String(java.nio.file.Files.readAllBytes(Paths.get("messages.def")), "UTF-8"), RICH, FRAMED}) {
            Schema a = SchemaParser.parse(text, "a");
            String regenerated = SchemaDraft.from(a).toText();
            Schema b = SchemaParser.parse(regenerated, "b");
            assertEquals(a.messages().size(), b.messages().size(), regenerated);
            for (MessageDef m : a.messages()) {
                byte[] x = Codec.encode(a, m, null, Values.defaults(m.body));
                byte[] y = Codec.encode(b, b.byName(m.name), null, Values.defaults(b.byName(m.name).body));
                assertEquals(hex(x), hex(y), m.name + "\n" + regenerated);
            }
        }
    }

    @Test void draftEditsProduceValidSchema() throws Exception {
        SchemaDraft d = SchemaDraft.starter();
        SchemaDraft.Field crc = new SchemaDraft.Field("uint16le", "crc");
        crc.role = "crc16(from=1)";
        d.footer.fields.add(crc);
        SchemaDraft.Field mode = new SchemaDraft.Field("enum:uint8", "mod");
        mode.enums = "KAPALI | ACIK=5";
        mode.def = "ACIK";
        d.messages.get(0).fields.add(mode);
        Schema s = SchemaParser.parse(d.toText(), "d");
        MessageDef m = s.byName("MESAJ1");
        assertEquals("ACIK", Values.defaults(m.body).get("mod"));
        assertTrue(Codec.decode(s, Codec.encode(s, m, null, null)).warnings.isEmpty());

        d.messages.get(0).fields.get(0).type = "uint17";
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaParser.parse(d.toText(), "d"));
        assertTrue(e.getMessage().contains("uint17"), e.getMessage());
    }

    // ---- bit gruplari ----

    private static final String BITS =
            "message 8 DURUM\n"
                    + "  bits8 bayraklar\n"
                    + "    bit    alarm\n"
                    + "    bit    hazir = true\n"
                    + "    uint3  mod = 2\n"
                    + "    enum2  seviye DUSUK|ORTA|YUKSEK\n"
                    + "    pad1\n"
                    + "  bits16le(msb) kelime\n"
                    + "    uint4  a = 0xA\n"
                    + "    pad4\n"
                    + "    int8   b = -2\n"
                    + "  uint16 deger = 0x1234\n";

    @Test void bitGroupsPackLsbAndMsbFirst() throws Exception {
        Schema s = SchemaParser.parse(BITS, "b");
        MessageDef m = s.byName("DURUM");
        Map<String, Object> body = Values.defaults(m.body);
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) body.get("bayraklar");
        flags.put("alarm", true);
        flags.put("seviye", "YUKSEK");
        byte[] b = Codec.encode(s, m, null, body);
        // LSB: alarm=1 (bit0), hazir=1 (bit1), mod=2 (bit2-4 -> 0b01000), seviye=2 (bit5-6 -> 0b1000000)
        assertEquals(0x01 | 0x02 | (2 << 2) | (2 << 5), b[2] & 0xFF);
        // MSB, 16 bit: a=0xA bit15-12, pad bit11-8, b=-2 (0xFE) bit7-0 -> 0xA0FE, little endian -> FE A0
        assertEquals("FE A0 12 34", hex(Arrays.copyOfRange(b, 3, 7)));

        Decoded d = Codec.decode(s, b);
        assertTrue(d.ok() && d.warnings.isEmpty(), d.error + " " + d.warnings);
        @SuppressWarnings("unchecked")
        Map<String, Object> got = (Map<String, Object>) d.body.get("bayraklar");
        assertEquals(Boolean.TRUE, got.get("alarm"));
        assertEquals(2L, got.get("mod"));
        assertEquals("YUKSEK", got.get("seviye"));
        @SuppressWarnings("unchecked")
        Map<String, Object> word = (Map<String, Object>) d.body.get("kelime");
        assertEquals(-2L, word.get("b"));
        assertTrue(d.spans.stream().anyMatch(x -> x.path.equals("bayraklar.mod") && x.text.contains("[bit 2-4]")), d.spans.toString());
        assertTrue(d.spans.stream().anyMatch(x -> x.path.equals("kelime.a") && x.text.contains("[bit 12-15]")));
    }

    @Test void bitFieldsAreEditableByPath() throws Exception {
        Schema s = SchemaParser.parse(BITS, "b");
        MessageDef m = s.byName("DURUM");
        Map<String, Object> body = Values.complete(m.body, new LinkedHashMap<>());
        Map<String, String> set = new LinkedHashMap<>();
        set.put("bayraklar.alarm", "1");
        set.put("bayraklar.mod", "7");
        set.put("kelime.b", "-128");
        assertTrue(Values.applyTexts(m.body, body, "", set).isEmpty());
        assertEquals(1, Values.applyTexts(m.body, body, "", Collections.singletonMap("bayraklar.mod", "8")).size());
        List<Values.Row> rows = Values.flatten(m.body, body, "", true);
        Values.Row mod = rows.stream().filter(r -> r.path.equals("bayraklar.mod")).findFirst().get();
        assertEquals("bit 2-4 · uint3", mod.typeText());
        assertEquals("7", mod.display());
        byte[] b = Codec.encode(s, m, null, body);
        assertEquals(1 | 2 | (7 << 2), b[2] & 0xFF);
        // duzenleyici modeli bit gruplarini korur
        Schema again = SchemaParser.parse(SchemaDraft.from(s).toText(), "again");
        assertEquals(hex(b), hex(Codec.encode(again, again.byName("DURUM"), null, body)));
    }

    @Test void bitGroupErrors() {
        String[][] bad = {
                {"message 1 A\n  bits8 x\n    uint5 a\n    uint4 b\n", "taşıyor"},
                {"message 1 A\n  bits8 x\n  uint8 y\n", "boş"},
                {"message 1 A\n  bits8 x\n    float a\n", "geçersiz tip"},
                {"message 1 A\n  bits8 x\n    enum1 e A|B|C\n", "sığmıyor"},
                {"message 1 A\n  bits8 x\n    uint2 a = 4\n", "aralık"},
                {"message 1 A\n  bits8 x\n    bit a @id\n", "rol"},
                {"message 1 A\n  bits8 x\n    bit a\n    bit a\n", "tekrar"},
        };
        for (String[] c : bad) {
            SchemaException e = assertThrows(SchemaException.class, () -> SchemaParser.parse(c[0], "e"), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[1] + " <- " + e.getMessage());
        }
    }
}
