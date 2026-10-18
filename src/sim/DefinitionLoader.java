package sim;

import sim.Model.FieldDef;
import sim.Model.MessageDef;
import sim.Model.StructDef;
import sim.Model.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** messages.def dosyasini okur ('struct' ve 'message' bloklari). */
public final class DefinitionLoader {
    private DefinitionLoader() {}

    private static final class Block {
        final boolean isStruct;
        final int id;
        final String name;
        final List<FieldDef> fields = new ArrayList<>();

        Block(boolean isStruct, int id, String name) {
            this.isStruct = isStruct;
            this.id = id;
            this.name = name;
        }
    }

    public static List<MessageDef> load(Path file) throws IOException {
        List<MessageDef> result = new ArrayList<>();
        Map<String, StructDef> structs = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file);
        Block cur = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                if (line.startsWith("struct ") || line.startsWith("message ")) {
                    flush(cur, result, structs);
                    String[] p = line.split("\\s+");
                    cur = p[0].equals("struct")
                            ? new Block(true, -1, p[1])
                            : new Block(false, Integer.parseInt(p[1]), p[2]);
                } else {
                    if (cur == null) throw new IllegalArgumentException("alan, 'message'/'struct' satirindan once geldi");
                    cur.fields.add(parseField(line, structs, cur.isStruct));
                }
            } catch (RuntimeException e) {
                throw new IOException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        flush(cur, result, structs);
        return result;
    }

    private static void flush(Block b, List<MessageDef> result, Map<String, StructDef> structs) {
        if (b == null) return;
        if (b.isStruct) structs.put(b.name, new StructDef(b.name, b.fields));
        else result.add(new MessageDef(b.id, b.name, b.fields));
    }

    private static FieldDef parseField(String line, Map<String, StructDef> structs, boolean inStruct) {
        String def = "";
        int eq = line.indexOf('=');
        if (eq >= 0) {
            def = line.substring(eq + 1).strip();
            line = line.substring(0, eq).strip();
        }
        String bind = null;
        List<String> tokens = new ArrayList<>();
        for (String t : line.split("\\s+")) {
            if (t.startsWith("@")) bind = t.substring(1);
            else tokens.add(t);
        }
        String[] p = tokens.toArray(new String[0]);
        if (p.length < 2) throw new IllegalArgumentException("alan satiri: '<tip> <ad>' bekleniyor");

        if (p[0].equalsIgnoreCase("list")) {
            if (inStruct) throw new IllegalArgumentException("struct icinde 'list' kullanilamaz");
            if (p.length < 3) throw new IllegalArgumentException("list satiri: 'list <STRUCT> <ad>' bekleniyor");
            StructDef s = structs.get(p[1]);
            if (s == null) throw new IllegalArgumentException("tanimsiz struct: " + p[1] + " (message'tan once tanimlayin)");
            return new FieldDef(p[2], Type.LIST, "", List.of(), bind, s);
        }

        Type type = Type.valueOf(p[0].toUpperCase(Locale.ROOT));
        if (type == Type.LIST) throw new IllegalArgumentException("list satiri: 'list <STRUCT> <ad>' bekleniyor");
        List<String> enums = List.of();
        if (type == Type.ENUM) {
            if (p.length < 3) throw new IllegalArgumentException("enum degerleri eksik (A|B|C)");
            enums = Arrays.asList(p[2].split("\\|"));
            if (def.isEmpty()) def = enums.get(0);
        } else if (def.isEmpty()) {
            def = switch (type) { case INT, DOUBLE -> "0"; case BOOL -> "false"; default -> ""; };
        }
        return new FieldDef(p[1], type, def, enums, bind, null);
    }
}
