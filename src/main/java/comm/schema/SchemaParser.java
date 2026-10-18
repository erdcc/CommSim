package comm.schema;

import comm.schema.FieldDef.Kind;
import comm.schema.FieldDef.Role;
import comm.schema.FieldDef.Size;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sema dosyasini (.def) okur. Bicim icin schemas/ornek.def dosyasina bakin.
 * Eski messages.def dosyalari degismeden okunur ve ayni byte'lari uretir.
 */
public final class SchemaParser {
    /** Varsayilan deger ayraci: iki yaninda bosluk olan '='. Enum ogelerindeki A=1 ile karismaz. */
    private static final Pattern DEFAULT_SEP = Pattern.compile("\\s=(\\s|$)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_\\p{L}][A-Za-z0-9_\\p{L}]*");

    private final String source;
    private final Schema schema;
    private StructDef current;
    private boolean headerDeclared;
    private int lineNo;
    private static final Pattern BITS = Pattern.compile("bits(8|16|32|64)(le|be)?(\\((msb|lsb)\\))?");
    private static final Pattern BIT_TYPE = Pattern.compile("(bit|bool|u?int(\\d+)|enum(\\d+)|pad(\\d+))");
    /** Acik bit grubu; girintisi daha buyuk satirlar onun alt alanlaridir. */
    private FieldDef openBits;
    private int bitsIndent;
    private int bitsUsed;

    private SchemaParser(String source) {
        this.source = source;
        this.schema = new Schema(source);
    }

    public static Schema parse(Path file) throws IOException, SchemaException {
        byte[] bytes = Files.readAllBytes(file);
        return parse(new String(bytes, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    public static Schema parse(String text, String source) throws SchemaException {
        SchemaParser p = new SchemaParser(source);
        p.run(text);
        return p.schema;
    }

    private SchemaException error(String msg) {
        return new SchemaException(source + ":" + lineNo + ": " + msg);
    }

    private void run(String text) throws SchemaException {
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lineNo = i + 1;
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            if (openBits != null) {
                if (indent(lines[i]) > bitsIndent && !isKeyword(t[0])) {
                    bitField(line);
                    continue;
                }
                closeBits();
            }
            switch (t[0]) {
                case "endian":
                    expect(t, 2, "endian big|little");
                    if (t[1].equals("big")) schema.order = ByteOrder.BIG_ENDIAN;
                    else if (t[1].equals("little")) schema.order = ByteOrder.LITTLE_ENDIAN;
                    else throw error("endian 'big' ya da 'little' olmalı");
                    current = null;
                    break;
                case "header":
                    expect(t, 1, "header");
                    if (headerDeclared) throw error("header iki kez tanımlandı");
                    headerDeclared = true;
                    open(schema.header);
                    break;
                case "footer":
                    expect(t, 1, "footer");
                    if (!schema.footer.fields.isEmpty()) throw error("footer iki kez tanımlandı");
                    open(schema.footer);
                    break;
                case "struct": {
                    expect(t, 2, "struct <AD>");
                    String name = ident(t[1]);
                    if (schema.structs.containsKey(name)) throw error("struct iki kez tanımlandı: " + name);
                    if (Types.isPrimitive(name)) throw error("struct adı bir tip adı olamaz: " + name);
                    StructDef s = new StructDef(name);
                    schema.structs.put(name, s);
                    open(s);
                    break;
                }
                case "message": {
                    expect(t, 3, "message <id> <AD>");
                    long id;
                    try {
                        id = Types.parseLong(t[1]);
                    } catch (NumberFormatException e) {
                        throw error("geçersiz mesaj id: " + t[1]);
                    }
                    String name = ident(t[2]);
                    if (schema.byId(id) != null) throw error("mesaj id tekrarlandı: " + id);
                    if (schema.byName(name) != null) throw error("mesaj adı tekrarlandı: " + name);
                    MessageDef m = new MessageDef(id, name);
                    schema.messages.add(m);
                    open(m.body);
                    break;
                }
                default:
                    if (current == null) throw error("alan, 'message', 'struct', 'header' ya da 'footer' satırından önce geldi");
                    FieldDef f = field(lines[i].trim());
                    if (current.field(f.name) != null) throw error("alan adı tekrarlandı: " + f.name);
                    current.fields.add(f);
                    if (f.kind == Kind.BITS) {
                        openBits = f;
                        bitsIndent = indent(lines[i]);
                        bitsUsed = 0;
                    }
            }
        }
        if (openBits != null) closeBits();
        lineNo = 0;
        finish();
    }

    private static int indent(String raw) {
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ') n++;
            else if (c == '\t') n += 4;
            else break;
        }
        return n;
    }

    private static boolean isKeyword(String s) {
        return s.equals("endian") || s.equals("header") || s.equals("footer") || s.equals("struct") || s.equals("message");
    }

    private void closeBits() throws SchemaException {
        FieldDef b = openBits;
        openBits = null;
        if (b.struct.fields.isEmpty()) {
            lineNo = b.line;
            throw error("bit grubu boş: " + b.name + " (alt alanları daha içeriden yazın: bit, uintN, intN, enumN, padN)");
        }
    }

    /** Bit grubu alt alani: bit ad | uintN ad | intN ad | enumN ad A|B | padN  [= varsayilan] */
    private void bitField(String line) throws SchemaException {
        String def = null;
        Matcher dm = DEFAULT_SEP.matcher(line);
        if (dm.find()) {
            def = line.substring(dm.end()).trim();
            line = line.substring(0, dm.start()).trim();
        }
        List<String> tokens = new ArrayList<>();
        for (String tok : line.split("\\s+")) {
            if (tok.startsWith("#")) break;
            if (tok.startsWith("@")) throw error("bit alanlarında rol kullanılamaz: " + tok);
            tokens.add(tok);
        }
        String type = tokens.get(0).toLowerCase(Locale.ROOT);
        Matcher m = BIT_TYPE.matcher(type);
        if (!m.matches()) throw error("bit grubunda geçersiz tip: " + tokens.get(0) + " (bit, uintN, intN, enumN, padN)");
        int total = openBits.size * 8;
        if (m.group(4) != null) { // padN
            if (tokens.size() != 1 || def != null) throw error("padN ad ya da değer almaz");
            int w = Integer.parseInt(m.group(4));
            if (w < 1) throw error("pad genişliği pozitif olmalı");
            bitsUsed += w;
            if (bitsUsed > total) throw error("bit grubu taşıyor: " + bitsUsed + " bit > " + total);
            return;
        }
        FieldDef f = new FieldDef();
        f.line = lineNo;
        f.typeText = tokens.get(0);
        int width;
        if (type.equals("bit") || type.equals("bool")) {
            f.kind = Kind.BOOL;
            width = 1;
        } else if (m.group(2) != null) {
            f.kind = Kind.INT;
            f.signed = type.startsWith("int");
            width = Integer.parseInt(m.group(2));
        } else {
            f.kind = Kind.ENUM;
            width = Integer.parseInt(m.group(3));
        }
        if (width < 1 || width > 64) throw error("bit genişliği 1 … 64 olmalı: " + tokens.get(0));
        if (f.signed && width < 2) throw error("işaretli bit alanı en az 2 bit olmalı");
        if (tokens.size() < 2) throw error("bit alanı adı eksik");
        f.name = ident(tokens.get(1));
        if (f.kind == Kind.ENUM) {
            if (tokens.size() < 3) throw error("enum değerleri eksik: 'enum" + width + " <ad> A|B|C'");
            enumItems(f, tokens.get(2));
            if (tokens.size() > 3) throw error("fazla sözcük: " + tokens.get(3));
            for (FieldDef.EnumItem e : f.enums) {
                if (e.value < 0 || (width < 64 && e.value >= (1L << width))) {
                    throw error("enum değeri " + width + " bite sığmıyor: " + e.name + "=" + e.value);
                }
            }
        } else if (tokens.size() > 2) {
            throw error("fazla sözcük: " + tokens.get(2));
        }
        f.bitWidth = width;
        f.size = Math.max(1, (width + 7) / 8);
        f.bitOffset = bitsUsed;
        bitsUsed += width;
        if (bitsUsed > total) throw error("bit grubu taşıyor: " + bitsUsed + " bit > " + total + " (" + openBits.name + ")");
        f.bitShift = openBits.msbFirst ? total - f.bitOffset - width : f.bitOffset;
        if (openBits.struct.field(f.name) != null) throw error("bit alanı adı tekrarlandı: " + f.name);
        if (def != null) {
            f.defaultText = def;
            try {
                Values.parse(f, def);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }
        openBits.struct.fields.add(f);
    }

    private void open(StructDef s) {
        current = s;
    }

    private void expect(String[] t, int n, String usage) throws SchemaException {
        if (t.length != n) throw error("beklenen biçim: " + usage);
    }

    private String ident(String s) throws SchemaException {
        if (!IDENT.matcher(s).matches()) throw error("geçersiz ad: " + s);
        return s;
    }

    private FieldDef field(String line) throws SchemaException {
        String def = null;
        Matcher m = DEFAULT_SEP.matcher(line);
        if (m.find()) {
            def = line.substring(m.end()).trim();
            line = line.substring(0, m.start()).trim();
        }
        List<String> tokens = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        for (String tok : line.split("\\s+")) {
            if (tok.startsWith("#")) break;
            if (tok.startsWith("@")) roles.add(tok.substring(1));
            else tokens.add(tok);
        }
        if (tokens.size() < 2) throw error("alan satırı: '<tip> <ad>' bekleniyor");

        FieldDef f = new FieldDef();
        f.line = lineNo;
        String typeTok = tokens.get(0);
        String lower = typeTok.toLowerCase(Locale.ROOT);
        int nameIdx = 1;

        Matcher bm = BITS.matcher(lower);
        if (bm.matches()) {
            // bits8 / bits16le / bits32(msb): alt alanlar sonraki, daha icerideki satirlarda
            f.kind = Kind.BITS;
            f.size = Integer.parseInt(bm.group(1)) / 8;
            if (bm.group(2) != null) f.order = bm.group(2).equals("le") ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            f.msbFirst = "msb".equals(bm.group(4));
            f.typeText = typeTok;
        } else if (lower.equals("list") || lower.startsWith("list[")) {
            // eski bicim: list <STRUCT> <ad>; yeni: list[adet] <STRUCT> <ad>
            if (tokens.size() < 3) throw error("liste: 'list[adet] <STRUCT> <ad>' bekleniyor");
            f.kind = Kind.LIST;
            f.struct = struct(tokens.get(1));
            f.length = lower.equals("list") ? Size.prefix(2) : size(bracket(typeTok), f);
            f.typeText = typeTok + " " + tokens.get(1);
            nameIdx = 2;
        } else if (lower.equals("enum") || lower.startsWith("enum:")) {
            // enum <ad> A|B|C  ya da  enum:uint16 <ad> A=1|B=5
            if (tokens.size() < 3) throw error("enum değerleri eksik: 'enum <ad> A|B|C'");
            f.kind = Kind.ENUM;
            if (lower.equals("enum")) {
                f.size = 1;
            } else {
                Types.Num base = Types.num(typeTok.substring(5));
                if (base == null || base.isFloat || base.isBool) throw error("enum taban tipi tam sayı olmalı: " + typeTok);
                f.size = base.size;
                f.signed = base.signed;
                f.order = base.order;
            }
            enumItems(f, tokens.get(2));
            f.typeText = typeTok;
            if (tokens.size() > 3) throw error("fazla sözcük: " + tokens.get(3));
        } else if (lower.equals("intlist")) {
            f.kind = Kind.ARRAY;
            f.element = primitive("int32");
            f.length = Size.prefix(2);
            f.typeText = "intlist";
        } else {
            String base = typeTok;
            String spec = null;
            int br = typeTok.indexOf('[');
            if (br >= 0) {
                base = typeTok.substring(0, br);
                spec = bracket(typeTok);
            }
            String b = base.toLowerCase(Locale.ROOT);
            if (b.equals("string") || b.equals("bytes")) {
                f.kind = b.equals("string") ? Kind.STRING : Kind.BYTES;
                f.length = spec == null ? Size.prefix(2) : size(spec, f);
            } else if (b.equals("cstring")) {
                if (spec != null) throw error("cstring uzunluk almaz (sıfır ile biter)");
                f.kind = Kind.CSTRING;
            } else if (Types.num(base) != null) {
                if (spec == null) {
                    copyPrimitive(f, base);
                } else {
                    f.kind = Kind.ARRAY;
                    f.element = primitive(base);
                    f.length = size(spec, f);
                }
            } else if (schema.structs.containsKey(base)) {
                f.struct = schema.structs.get(base);
                if (spec == null) {
                    f.kind = Kind.STRUCT;
                } else {
                    f.kind = Kind.LIST;
                    f.length = size(spec, f);
                }
            } else {
                throw error("bilinmeyen tip: " + typeTok + " (struct ise kullanılmadan önce tanımlayın)");
            }
            f.typeText = typeTok;
        }

        if (tokens.size() <= nameIdx) throw error("alan adı eksik");
        f.name = ident(tokens.get(nameIdx));
        if (f.kind == Kind.BITS) f.struct = new StructDef(f.name);
        if (tokens.size() > nameIdx + 1 && f.kind != Kind.ENUM) {
            throw error("fazla sözcük: " + tokens.get(nameIdx + 1));
        }

        for (String r : roles) role(f, r);
        if (def != null) {
            if (!f.isLeaf()) throw error("liste/dizi/struct alanına varsayılan değer verilemez");
            if (f.role.isAuto()) throw error("otomatik doldurulan alana varsayılan değer verilemez");
            f.defaultText = def;
            try {
                Values.parse(f, def);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }
        return f;
    }

    private String bracket(String tok) throws SchemaException {
        int a = tok.indexOf('[');
        if (a < 0) return null;
        if (!tok.endsWith("]") || tok.length() - 1 <= a + 1) throw error("köşeli parantez hatalı: " + tok);
        return tok.substring(a + 1, tok.length() - 1).trim();
    }

    private StructDef struct(String name) throws SchemaException {
        StructDef s = schema.structs.get(name);
        if (s == null) throw error("tanımsız struct: " + name + " (kullanılmadan önce tanımlayın)");
        return s;
    }

    private FieldDef primitive(String type) throws SchemaException {
        FieldDef e = new FieldDef();
        copyPrimitive(e, type);
        e.name = "eleman";
        e.line = lineNo;
        return e;
    }

    private void copyPrimitive(FieldDef f, String type) throws SchemaException {
        Types.Num n = Types.num(type);
        if (n == null) throw error("bilinmeyen tip: " + type);
        f.kind = n.isFloat ? Kind.FLOAT : n.isBool ? Kind.BOOL : Kind.INT;
        f.size = n.size;
        f.signed = n.signed;
        f.order = n.order;
        f.typeText = type;
    }

    /** [..] icerigi: sayi, '*', onek tipi (uint8/16/32) ya da onceki bir alanin adi. */
    private Size size(String spec, FieldDef owner) throws SchemaException {
        if (spec.equals("*")) return Size.rest();
        if (spec.chars().allMatch(Character::isDigit)) {
            int n = Integer.parseInt(spec);
            if (n <= 0) throw error("uzunluk pozitif olmalı: " + spec);
            return Size.fixed(n);
        }
        Types.Num n = Types.num(spec);
        if (n != null) {
            if (n.isFloat || n.isBool || n.signed || n.size > 4) {
                throw error("önek tipi uint8, uint16 ya da uint32 olmalı: " + spec);
            }
            return Size.prefix(n.size);
        }
        FieldDef ref = current.field(spec);
        if (ref == null) throw error("adet alanı bulunamadı: " + spec + " (aynı blokta, önce tanımlanmalı)");
        if (ref.kind != Kind.INT) throw error("adet alanı tam sayı olmalı: " + spec);
        if (ref.role != Role.NONE && ref.role != Role.COUNT) throw error("adet alanının başka bir rolü var: " + spec);
        if (ref.role == Role.COUNT) throw error("aynı adet alanı iki kez kullanıldı: " + spec);
        if (ref.defaultText != null) throw error("adet alanına varsayılan değer verilemez: " + spec);
        ref.role = Role.COUNT;
        return Size.field(spec);
    }

    private void enumItems(FieldDef f, String spec) throws SchemaException {
        long next = 0;
        Set<String> names = new HashSet<>();
        for (String part : spec.split("\\|")) {
            String name = part;
            long value = next;
            int eq = part.indexOf('=');
            if (eq >= 0) {
                name = part.substring(0, eq);
                try {
                    value = Types.parseLong(part.substring(eq + 1));
                } catch (NumberFormatException e) {
                    throw error("geçersiz enum değeri: " + part);
                }
            }
            ident(name);
            if (!names.add(name)) throw error("enum adı tekrarlandı: " + name);
            if (f.enumByValue(value) != null) throw error("enum değeri tekrarlandı: " + value);
            f.enums.add(new FieldDef.EnumItem(name, value));
            next = value + 1;
        }
    }

    /**
     * Rol: {@code @ad} ya da {@code @ad(arg,anahtar=deger)}. Uzunluk icin konum (body|all|after) ve adjust=N,
     * checksum icin from=N verilebilir. Bosluk birakilmaz: @length(all,adjust=-2)  @crc16(from=1)
     */
    private void role(FieldDef f, String r) throws SchemaException {
        String lower = r.toLowerCase(Locale.ROOT);
        String name = lower;
        List<String> args = new ArrayList<>();
        int par = lower.indexOf('(');
        if (par >= 0) {
            if (!lower.endsWith(")")) throw error("rol parantezi kapanmamış: @" + r);
            name = lower.substring(0, par);
            for (String a : lower.substring(par + 1, lower.length() - 1).split(",")) {
                if (!a.trim().isEmpty()) args.add(a.trim());
            }
        }
        Role role;
        switch (name) {
            case "id": role = Role.ID; break;
            case "length": role = Role.LENGTH_BODY; break;
            case "sum8": role = Role.SUM8; break;
            case "xor8": role = Role.XOR8; break;
            case "crc16": role = Role.CRC16; break;
            case "crc16ccitt": case "crc16_ccitt": role = Role.CRC16_CCITT; break;
            case "crc32": role = Role.CRC32; break;
            default:
                return; // eski dosyalardaki harita baglamalari (@name) gibi bilinmeyen etiketler yok sayilir
        }
        for (String a : args) {
            int eq = a.indexOf('=');
            String key = eq < 0 ? a : a.substring(0, eq).trim();
            String val = eq < 0 ? "" : a.substring(eq + 1).trim();
            if (role.isLength() && eq < 0) {
                switch (key) {
                    case "body": role = Role.LENGTH_BODY; break;
                    case "all": role = Role.LENGTH_ALL; break;
                    case "after": role = Role.LENGTH_AFTER; break;
                    default: throw error("uzunluk konumu body, all ya da after olmalı: " + key);
                }
            } else if (role.isLength() && key.equals("adjust")) {
                f.lengthAdjust = intParam(val, key);
            } else if (role.isChecksum() && key.equals("from")) {
                f.checksumFrom = intParam(val, key);
                if (f.checksumFrom < 0) throw error("from negatif olamaz");
            } else {
                throw error("@" + name + " için geçersiz parametre: " + a);
            }
        }
        if (f.kind != Kind.INT) throw error("@" + r + " yalnızca tam sayı alanında kullanılabilir");
        if (f.role != Role.NONE) throw error("bir alanın tek rolü olabilir");
        if (role == Role.ID) {
            if (current != schema.header) throw error("@id yalnızca header içinde kullanılabilir");
            if (schema.idField != null) throw error("header'da birden çok @id var");
            schema.idField = f;
        }
        if ((role == Role.CRC16 || role == Role.CRC16_CCITT) && f.size < 2) throw error("CRC16 için en az 2 byte gerekir");
        if (role == Role.CRC32 && f.size < 4) throw error("CRC32 için en az 4 byte gerekir");
        f.role = role;
    }

    private int intParam(String v, String key) throws SchemaException {
        try {
            return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
        } catch (NumberFormatException e) {
            throw error(key + " bir tam sayı olmalı: '" + v + "'");
        }
    }

    private void finish() throws SchemaException {
        if (!headerDeclared) {
            // eski messages.def: u16 mesaj id
            FieldDef id = new FieldDef();
            id.name = "id";
            id.kind = Kind.INT;
            id.size = 2;
            id.typeText = "uint16";
            id.role = Role.ID;
            schema.header.fields.add(id);
            schema.idField = id;
        }
        if (schema.footer.fixedSize() < 0) throw error("footer alanları sabit boylu olmalı");
        checkRest(schema.header, false);
        checkRest(schema.footer, false);
        for (StructDef s : schema.structs.values()) checkRest(s, false);
        for (MessageDef m : schema.messages) checkRest(m.body, true);
        if (schema.messages.isEmpty()) throw new SchemaException(source + ": hiç mesaj tanımlanmamış");
    }

    private void checkRest(StructDef s, boolean body) throws SchemaException {
        for (int i = 0; i < s.fields.size(); i++) {
            FieldDef f = s.fields.get(i);
            if (f.length != null && f.length.mode == Size.Mode.REST && (!body || i != s.fields.size() - 1)) {
                lineNo = f.line;
                throw error("[*] yalnızca bir mesajın son alanında kullanılabilir: " + f.name);
            }
        }
    }
}
