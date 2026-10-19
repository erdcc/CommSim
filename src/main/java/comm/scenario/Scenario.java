package comm.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Senaryo dilinin ayristiricisi. Her satir bir komut; "repeat N" ... "end" bloklari ic ice olabilir.
 * Bosluk iceren argumanlar cift tirnakla yazilir: send "Örnek TCP istemci" KOMUT command=GOTO
 */
public final class Scenario {
    /** Komut adi -&gt; en az arguman sayisi. */
    static final Map<String, Integer> COMMANDS = new HashMap<>();

    static {
        COMMANDS.put("start", 1);
        COMMANDS.put("stop", 1);
        COMMANDS.put("wait", 1);
        COMMANDS.put("log", 0);
        COMMANDS.put("send", 2);
        COMMANDS.put("set", 3);
        COMMANDS.put("hex", 2);
        COMMANDS.put("text", 2);
        COMMANDS.put("waitfor", 2);
        COMMANDS.put("expect", 3);
        COMMANDS.put("modbus", 3);
        COMMANDS.put("mbset", 4);
        COMMANDS.put("snmp", 3);
        COMMANDS.put("trap", 2);
        COMMANDS.put("var", 3);
        COMMANDS.put("assert", 1);
        COMMANDS.put("fail", 0);
        COMMANDS.put("exit", 0);
        COMMANDS.put("continue_on_error", 1);
        COMMANDS.put("break", 0);
        COMMANDS.put("return", 0);
        COMMANDS.put("call", 1);
    }

    /** Kullanici tanimli komut grubu: function ad [param ...] ... end */
    public static final class Function {
        public final String name;
        public final List<String> params;
        public final List<Step> body;
        public final int line;

        Function(String name, List<String> params, List<Step> body, int line) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.line = line;
        }
    }

    /** Ayristirilmis senaryo: ana adimlar ve fonksiyonlar. */
    public static final class Program {
        public final List<Step> steps;
        public final Map<String, Function> functions;

        Program(List<Step> steps, Map<String, Function> functions) {
            this.steps = steps;
            this.functions = functions;
        }
    }

    /** Dongu ust siniri: yanlislikla sonsuz kalan while'lar durur. */
    public static final int WHILE_MAX = 1_000_000;
    private static final int INCLUDE_DEPTH = 8;

    /** Ayristirilmis adim: komut ya da tekrar blogu. */
    public static final class Step {
        public final int line;
        public final String command;
        public final List<String> args;
        /** repeat icin; -1 ise adet args[0] ifadesinden calisirken hesaplanir. */
        public final int count;
        public final List<Step> body;
        /** if icin: else govdesi (yoksa bos). */
        public final List<Step> elseBody;
        /** include edilen dosyanin adi; ana metinde bos. */
        public final String source;

        Step(int line, String command, List<String> args) {
            this(line, command, args, 0, Collections.<Step>emptyList(), Collections.<Step>emptyList(), "");
        }

        Step(int line, String command, List<String> args, int count, List<Step> body, List<Step> elseBody, String source) {
            this.line = line;
            this.command = command;
            this.args = args;
            this.count = count;
            this.body = body;
            this.elseBody = elseBody;
            this.source = source;
        }

        /** Hata mesajlari icin konum: "satır 5" ya da "ortak.scn satır 5". */
        public String where() { return (source.isEmpty() ? "" : source + " ") + "satır " + line; }
    }

    private Scenario() { }

    /** Acik blok: repeat ya da if. */
    private static final class Frame {
        final String kind;
        final int line;
        final int count;
        final List<String> args;
        final List<Step> body = new ArrayList<>();
        List<Step> elseBody = new ArrayList<>();
        boolean inElse;

        Frame(String kind, int line, int count, List<String> args) {
            this.kind = kind;
            this.line = line;
            this.count = count;
            this.args = args;
        }

        List<Step> current() { return inElse ? elseBody : body; }
    }

    public static List<Step> parse(String text) throws ScenarioException {
        return parseProgram(text, null).steps;
    }

    /**
     * Senaryoyu fonksiyonlariyla ayristirir.
     * @param baseDir include yollarinin goreli oldugu klasor; null ise calisma klasoru
     */
    public static Program parseProgram(String text, java.nio.file.Path baseDir) throws ScenarioException {
        Map<String, Function> functions = new LinkedHashMap<>();
        List<Step> steps = parseInto(text, baseDir, "", functions, 0);
        checkCalls(steps, functions, new java.util.HashSet<List<Step>>());
        for (Function f : functions.values()) checkCalls(f.body, functions, new java.util.HashSet<List<Step>>());
        return new Program(steps, functions);
    }

    /** call adimlarinin var olan fonksiyonlari dogru arguman sayisiyla cagirdigini denetler. */
    private static void checkCalls(List<Step> steps, Map<String, Function> functions, java.util.Set<List<Step>> seen)
            throws ScenarioException {
        if (!seen.add(steps)) return;
        for (Step s : steps) {
            if (s.command.equals("call")) {
                Function f = functions.get(s.args.get(0));
                String at = s.where();
                if (f == null) throw new ScenarioException(at + ": tanımsız fonksiyon: " + s.args.get(0));
                if (s.args.size() - 1 != f.params.size()) {
                    throw new ScenarioException(at + ": '" + f.name + "' " + f.params.size() + " argüman bekliyor, "
                            + (s.args.size() - 1) + " verildi");
                }
            }
            checkCalls(s.body, functions, seen);
            checkCalls(s.elseBody, functions, seen);
        }
    }

    private static List<Step> parseInto(String text, java.nio.file.Path baseDir, String source,
                                        Map<String, Function> functions, int depth) throws ScenarioException {
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame("root", 0, 0, Collections.<String>emptyList()));
        String[] lines = text.split("\r?\n", -1);
        String at = source.isEmpty() ? "satır " : source + " satır ";
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> t;
            try {
                t = tokenize(line);
            } catch (IllegalArgumentException e) {
                throw new ScenarioException(at + lineNo + ": " + e.getMessage());
            }
            String cmd = t.get(0).toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>(t.subList(1, t.size()));
            Frame top = stack.get(stack.size() - 1);
            switch (cmd) {
                case "repeat": {
                    if (args.size() != 1) throw new ScenarioException(at + lineNo + ": 'repeat <adet>' bekleniyor");
                    int n;
                    try {
                        n = Integer.parseInt(args.get(0));
                        if (n < 0) throw new ScenarioException(at + lineNo + ": adet negatif olamaz");
                    } catch (NumberFormatException e) {
                        // ${degisken} ya da ifade: calisirken hesaplanir
                        try {
                            comm.runtime.Expr.parse(args.get(0));
                        } catch (IllegalArgumentException x) {
                            throw new ScenarioException(at + lineNo + ": geçersiz adet: " + args.get(0));
                        }
                        n = -1;
                    }
                    stack.add(new Frame("repeat", lineNo, n, args));
                    break;
                }
                case "if":
                    stack.add(new Frame("if", lineNo, 0, condition(args, lineNo, "if", at)));
                    break;
                case "while":
                    stack.add(new Frame("while", lineNo, 0, condition(args, lineNo, "while", at)));
                    break;
                case "function": {
                    if (stack.size() != 1) throw new ScenarioException(at + lineNo + ": 'function' yalnızca en dış seviyede tanımlanabilir");
                    if (args.isEmpty()) throw new ScenarioException(at + lineNo + ": 'function <ad> [parametre ...]' bekleniyor");
                    for (String a : args) {
                        if (!a.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ScenarioException(at + lineNo + ": geçersiz ad: " + a);
                    }
                    if (functions.containsKey(args.get(0))) throw new ScenarioException(at + lineNo + ": '" + args.get(0) + "' iki kez tanımlanmış");
                    stack.add(new Frame("function", lineNo, 0, args));
                    break;
                }
                case "include": {
                    if (args.size() != 1) throw new ScenarioException(at + lineNo + ": 'include <dosya.scn>' bekleniyor");
                    if (depth >= INCLUDE_DEPTH) throw new ScenarioException(at + lineNo + ": include çok derin (döngüsel olabilir)");
                    java.nio.file.Path f = baseDir == null ? java.nio.file.Paths.get(args.get(0)) : baseDir.resolve(args.get(0));
                    String inc;
                    try {
                        inc = new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        throw new ScenarioException(at + lineNo + ": include okunamadı: " + f);
                    }
                    java.nio.file.Path parent = f.toAbsolutePath().getParent();
                    top.current().addAll(parseInto(inc, parent, f.getFileName().toString(), functions, depth + 1));
                    break;
                }
                case "else":
                    if (!top.kind.equals("if") || top.inElse) throw new ScenarioException(at + lineNo + ": eşleşen 'if' yok");
                    top.inElse = true;
                    break;
                case "end": {
                    if (stack.size() == 1) throw new ScenarioException(at + lineNo + ": eşleşen 'repeat', 'while', 'if' ya da 'function' yok");
                    Frame f = stack.remove(stack.size() - 1);
                    List<Step> into = stack.get(stack.size() - 1).current();
                    switch (f.kind) {
                        case "repeat":
                            into.add(new Step(f.line, "repeat", f.count < 0 ? f.args : Collections.<String>emptyList(), f.count,
                                    f.body, Collections.<Step>emptyList(), source));
                            break;
                        case "while":
                            into.add(new Step(f.line, "while", f.args, 0, f.body, Collections.<Step>emptyList(), source));
                            break;
                        case "function":
                            functions.put(f.args.get(0), new Function(f.args.get(0),
                                    new ArrayList<>(f.args.subList(1, f.args.size())), f.body, f.line));
                            break;
                        default:
                            into.add(new Step(f.line, "if", f.args, 0, f.body, f.elseBody, source));
                    }
                    break;
                }
                default: {
                    Integer min = COMMANDS.get(cmd);
                    if (min == null) throw new ScenarioException(at + lineNo + ": bilinmeyen komut: " + t.get(0));
                    if (args.size() < min) throw new ScenarioException(at + lineNo + ": '" + cmd + "' için en az " + min + " argüman gerekli");
                    if (cmd.equals("assert")) args = condition(args, lineNo, "assert", at);
                    if (cmd.equals("break") && !inside(stack, "repeat", "while")) {
                        throw new ScenarioException(at + lineNo + ": 'break' yalnızca repeat ya da while içinde kullanılır");
                    }
                    if (cmd.equals("return") && !inside(stack, "function")) {
                        throw new ScenarioException(at + lineNo + ": 'return' yalnızca function içinde kullanılır");
                    }
                    validate(cmd, args, lineNo, at);
                    top.current().add(new Step(lineNo, cmd, args, 0, Collections.<Step>emptyList(), Collections.<Step>emptyList(), source));
                }
            }
        }
        if (stack.size() > 1) {
            Frame f = stack.get(stack.size() - 1);
            throw new ScenarioException(at + f.line + ": '" + f.kind + "' bloğu 'end' ile kapanmamış");
        }
        return stack.get(0).body;
    }

    private static boolean inside(List<Frame> stack, String... kinds) {
        for (Frame f : stack) for (String k : kinds) if (f.kind.equals(k)) return true;
        return false;
    }

    /** "sol op sag" -&gt; [sol, op, sag]; ifadeler derlenerek dogrulanir. */
    private static List<String> condition(List<String> args, int line, String cmd, String at) throws ScenarioException {
        int op = -1;
        for (int i = 0; i < args.size(); i++) {
            if (comm.runtime.Expr.isCompareOp(args.get(i))) {
                if (op >= 0) throw new ScenarioException(at + line + ": tek karşılaştırma operatörü olmalı");
                op = i;
            }
        }
        if (op <= 0 || op == args.size() - 1) {
            throw new ScenarioException(at + line + ": '" + cmd + " <ifade> == <ifade>' bekleniyor (== != < > <= >=, operatörün iki yanında boşluk)");
        }
        String left = String.join(" ", args.subList(0, op));
        String right = String.join(" ", args.subList(op + 1, args.size()));
        try {
            comm.runtime.Expr.parse(left);
            comm.runtime.Expr.parse(right);
        } catch (IllegalArgumentException e) {
            throw new ScenarioException(at + line + ": " + e.getMessage());
        }
        return new ArrayList<>(java.util.Arrays.asList(left, args.get(op), right));
    }

    private static void validate(String cmd, List<String> a, int line, String at) throws ScenarioException {
        try {
            switch (cmd) {
                case "wait":
                    if (!a.get(0).contains("${") && Long.parseLong(a.get(0)) < 0) throw new IllegalArgumentException("süre negatif olamaz");
                    break;
                case "var":
                    if (!a.get(0).matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("geçersiz değişken adı: " + a.get(0));
                    if (!a.get(1).equals("=")) throw new IllegalArgumentException("'var <ad> = <ifade>' bekleniyor");
                    comm.runtime.Expr.parse(String.join(" ", a.subList(2, a.size())));
                    break;
                case "continue_on_error":
                    if (!a.get(0).equalsIgnoreCase("on") && !a.get(0).equalsIgnoreCase("off")) throw new IllegalArgumentException("on ya da off");
                    break;
                case "set":
                case "expect":
                    assignments(a.subList(2, a.size()));
                    break;
                case "send":
                    assignments(a.subList(2, a.size()));
                    break;
                case "waitfor":
                    if (a.size() > 2 && !a.get(2).contains("${")) Long.parseLong(a.get(2));
                    break;
                case "modbus": {
                    String op = a.get(1).toLowerCase(Locale.ROOT);
                    if (!Arrays.asList("read", "write", "coil").contains(op)) throw new IllegalArgumentException("modbus işlemi read|write|coil olmalı");
                    if (a.size() < (op.equals("read") ? 5 : 4)) throw new IllegalArgumentException("eksik argüman");
                    break;
                }
                case "mbset":
                    if (!Arrays.asList("coil", "di", "hr", "ir").contains(a.get(1).toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("tablo coil|di|hr|ir olmalı");
                    }
                    break;
                case "snmp": {
                    String op = a.get(1).toLowerCase(Locale.ROOT);
                    if (!Arrays.asList("get", "getnext", "walk", "set").contains(op)) throw new IllegalArgumentException("snmp işlemi get|getnext|walk|set olmalı");
                    if (op.equals("set") && a.size() < 5) throw new IllegalArgumentException("'snmp <bağlantı> set <oid> <TİP> <değer>'");
                    break;
                }
                default:
                    break;
            }
        } catch (NumberFormatException e) {
            throw new ScenarioException(at + line + ": sayı bekleniyor (" + e.getMessage() + ")");
        } catch (IllegalArgumentException e) {
            throw new ScenarioException(at + line + ": " + e.getMessage());
        }
    }

    /** "alan=değer" listesini sirali haritaya cevirir. */
    static Map<String, String> assignments(List<String> args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : args) {
            int eq = s.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("'alan=değer' bekleniyor: " + s);
            m.put(s.substring(0, eq), s.substring(eq + 1));
        }
        return m;
    }

    /** Boslukla ayirir; cift tirnak icindeki bosluklar korunur, \" ve \\ kacislari cozulur. */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    cur.append(line.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else if (c == '#' && !any) {
                break; // satir sonu yorumu
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("kapanmamış tırnak");
        if (any) out.add(cur.toString());
        return out;
    }
}
