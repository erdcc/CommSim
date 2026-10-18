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
    }

    /** Ayristirilmis adim: komut ya da tekrar blogu. */
    public static final class Step {
        public final int line;
        public final String command;
        public final List<String> args;
        /** repeat icin. */
        public final int count;
        public final List<Step> body;
        /** if icin: else govdesi (yoksa bos). */
        public final List<Step> elseBody;

        Step(int line, String command, List<String> args) {
            this(line, command, args, 0, Collections.<Step>emptyList(), Collections.<Step>emptyList());
        }

        Step(int line, String command, List<String> args, int count, List<Step> body, List<Step> elseBody) {
            this.line = line;
            this.command = command;
            this.args = args;
            this.count = count;
            this.body = body;
            this.elseBody = elseBody;
        }
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
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame("root", 0, 0, Collections.<String>emptyList()));
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> t;
            try {
                t = tokenize(line);
            } catch (IllegalArgumentException e) {
                throw new ScenarioException("satır " + lineNo + ": " + e.getMessage());
            }
            String cmd = t.get(0).toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>(t.subList(1, t.size()));
            Frame top = stack.get(stack.size() - 1);
            switch (cmd) {
                case "repeat": {
                    if (args.size() != 1) throw new ScenarioException("satır " + lineNo + ": 'repeat <adet>' bekleniyor");
                    int n;
                    try {
                        n = Integer.parseInt(args.get(0));
                    } catch (NumberFormatException e) {
                        throw new ScenarioException("satır " + lineNo + ": geçersiz adet: " + args.get(0));
                    }
                    if (n < 0) throw new ScenarioException("satır " + lineNo + ": adet negatif olamaz");
                    stack.add(new Frame("repeat", lineNo, n, args));
                    break;
                }
                case "if":
                    stack.add(new Frame("if", lineNo, 0, condition(args, lineNo, "if")));
                    break;
                case "else":
                    if (!top.kind.equals("if") || top.inElse) throw new ScenarioException("satır " + lineNo + ": eşleşen 'if' yok");
                    top.inElse = true;
                    break;
                case "end": {
                    if (stack.size() == 1) throw new ScenarioException("satır " + lineNo + ": eşleşen 'repeat' ya da 'if' yok");
                    Frame f = stack.remove(stack.size() - 1);
                    stack.get(stack.size() - 1).current().add(f.kind.equals("repeat")
                            ? new Step(f.line, "repeat", Collections.<String>emptyList(), f.count, f.body, Collections.<Step>emptyList())
                            : new Step(f.line, "if", f.args, 0, f.body, f.elseBody));
                    break;
                }
                default: {
                    Integer min = COMMANDS.get(cmd);
                    if (min == null) throw new ScenarioException("satır " + lineNo + ": bilinmeyen komut: " + t.get(0));
                    if (args.size() < min) throw new ScenarioException("satır " + lineNo + ": '" + cmd + "' için en az " + min + " argüman gerekli");
                    if (cmd.equals("assert")) args = condition(args, lineNo, "assert");
                    validate(cmd, args, lineNo);
                    top.current().add(new Step(lineNo, cmd, args));
                }
            }
        }
        if (stack.size() > 1) {
            Frame f = stack.get(stack.size() - 1);
            throw new ScenarioException("satır " + f.line + ": '" + f.kind + "' bloğu 'end' ile kapanmamış");
        }
        return stack.get(0).body;
    }

    /** "sol op sag" -&gt; [sol, op, sag]; ifadeler derlenerek dogrulanir. */
    private static List<String> condition(List<String> args, int line, String cmd) throws ScenarioException {
        int op = -1;
        for (int i = 0; i < args.size(); i++) {
            if (comm.runtime.Expr.isCompareOp(args.get(i))) {
                if (op >= 0) throw new ScenarioException("satır " + line + ": tek karşılaştırma operatörü olmalı");
                op = i;
            }
        }
        if (op <= 0 || op == args.size() - 1) {
            throw new ScenarioException("satır " + line + ": '" + cmd + " <ifade> == <ifade>' bekleniyor (== != < > <= >=, operatörün iki yanında boşluk)");
        }
        String left = String.join(" ", args.subList(0, op));
        String right = String.join(" ", args.subList(op + 1, args.size()));
        try {
            comm.runtime.Expr.parse(left);
            comm.runtime.Expr.parse(right);
        } catch (IllegalArgumentException e) {
            throw new ScenarioException("satır " + line + ": " + e.getMessage());
        }
        return new ArrayList<>(java.util.Arrays.asList(left, args.get(op), right));
    }

    private static void validate(String cmd, List<String> a, int line) throws ScenarioException {
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
            throw new ScenarioException("satır " + line + ": sayı bekleniyor (" + e.getMessage() + ")");
        } catch (IllegalArgumentException e) {
            throw new ScenarioException("satır " + line + ": " + e.getMessage());
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
    static List<String> tokenize(String line) {
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
