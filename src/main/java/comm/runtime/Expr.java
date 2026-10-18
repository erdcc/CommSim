package comm.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Deger ifadeleri: uretecler, kural yanitlari ve senaryo degiskenleri icin ortak kucuk dil.
 * <pre>
 *   5   GOTO   "metin"           duz deger (ifade olarak cozulemeyen her metin de duz degerdir)
 *   counter(0, 1)                0, 1, 2 ... (ucuncu arguman verilirse o degerden sonra basa doner)
 *   random(10, 20)               tam sayi, iki uc dahil;   randomf(0, 1) ondalik
 *   sine(0, 100, 5000)           5 s periyotlu sinus;  ramp(0, 100, 5000) testere;  square(0, 1, 1000)
 *   list(A|B|C)                  sirayla;  pick(A|B|C) rastgele;  toggle() true/false
 *   now()                        epoch milisaniye
 *   $rx.alan  ya da rx(alan)     kurallarda: gelen mesajdaki deger (baslik icin $rx.başlık.seq)
 *   ${degisken}                  senaryolarda: degisken
 *   last(baglanti, MESAJ, alan)  senaryolarda: son alinan deger
 *   + - * / %  ve parantez      sayilar uzerinde ($rx.seq + 1)
 * </pre>
 * Durumlu fonksiyonlar (counter, list, toggle) her Expr nesnesinin kendi durumunu tutar.
 */
public final class Expr {
    /** Ifadenin dis dunyadan okudugu degerler. */
    public interface Context {
        /** Gelen mesajdaki alan (kurallar); yoksa null. */
        default String rx(String path) { return null; }

        /** Senaryo degiskeni; yoksa null. */
        default String var(String name) { return null; }

        /** Bir baglantinin son alinan mesajindaki alan; yoksa null. */
        default String last(String endpoint, String message, String path) { return null; }

        default long now() { return System.currentTimeMillis(); }
    }

    public static final Context EMPTY = new Context() { };

    private interface Node {
        Object eval(Context c);
    }

    private final String text;
    private final Node root;
    private final Random random = new Random();

    private Expr(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    public String text() { return text; }

    /** Duz deger mi (hic fonksiyon/referans/aritmetik yok). */
    public boolean isLiteral() { return root instanceof Lit; }

    /**
     * Ifadeyi derler. Ifade olarak cozulemeyen metin duz deger sayilir;
     * ama bilinmeyen bir fonksiyon adi ya da hatali arguman yazim hatasi olarak bildirilir.
     */
    public static Expr parse(String text) {
        String t = text == null ? "" : text.trim();
        Expr e = new Expr(t, null);
        Node n;
        try {
            Parser p = e.new Parser(t);
            n = p.expr();
            p.skipWs();
            if (p.pos != t.length()) n = null;
        } catch (Syntax ex) {
            n = null;
        }
        if (n == null || n instanceof Lit) {
            if (t.matches("[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*\\)\\s*")) {
                String name = t.substring(0, t.indexOf('(')).trim();
                if (!FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("bilinmeyen fonksiyon: " + name);
                throw new IllegalArgumentException("hatalı ifade: " + t);
            }
            n = new Lit(unquote(t));
        }
        return new Expr(t, n);
    }

    /** Degeri metin olarak hesaplar. @throws IllegalArgumentException referans bulunamazsa ya da sayi gerekirken metin gelirse */
    public String eval(Context c) { return format(root.eval(c == null ? EMPTY : c)); }

    static String format(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            String s = String.format(Locale.ROOT, "%.6f", d);
            s = s.replaceAll("0+$", "");
            return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        }
        return String.valueOf(v);
    }

    private static String unquote(String t) {
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"");
        }
        return t;
    }

    // ---- dugumler ----

    private static final class Lit implements Node {
        final Object v;

        Lit(Object v) { this.v = v; }

        @Override public Object eval(Context c) { return v; }
    }

    private static final class Syntax extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Syntax() { super(null, null, false, false); }
    }

    private static final java.util.Set<String> FUNCTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "counter", "random", "randomf", "sine", "ramp", "square", "list", "pick", "toggle", "now", "rx", "last"));

    /** Karsilastirma: iki taraf sayiysa sayisal, degilse metin. Operatorler: = == != &lt; &gt; &lt;= &gt;= */
    public static boolean compare(String a, String op, String b) {
        Double x = numberOrNull(a);
        Double y = numberOrNull(b);
        boolean numeric = x != null && y != null;
        int c = numeric ? Double.compare(x, y) : a.compareTo(b);
        boolean eq = numeric ? x.doubleValue() == y.doubleValue() : a.equals(b);
        switch (op) {
            case "=": case "==": return eq;
            case "!=": return !eq;
            case ">": return c > 0;
            case "<": return c < 0;
            case ">=": return c >= 0;
            case "<=": return c <= 0;
            default: throw new IllegalArgumentException("bilinmeyen karşılaştırma: " + op);
        }
    }

    public static boolean isCompareOp(String s) {
        return s.equals("==") || s.equals("=") || s.equals("!=") || s.equals("<") || s.equals(">") || s.equals("<=") || s.equals(">=");
    }

    private static Double numberOrNull(String s) {
        try {
            return num(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        String s = String.valueOf(v).trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            if (s.equalsIgnoreCase("true")) return 1;
            if (s.equalsIgnoreCase("false")) return 0;
            throw new IllegalArgumentException("sayı bekleniyor: '" + s + "'");
        }
    }

    /** Tam sayiysa Long, degilse Double. */
    static Object number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 9e18 ? (Object) (long) d : (Object) d;
    }

    private final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean eat(char c) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        Node expr() {
            Node left = term();
            while (true) {
                if (eat('+')) {
                    Node a = left, b = term();
                    left = c -> number(num(a.eval(c)) + num(b.eval(c)));
                } else if (peekMinus()) {
                    pos++;
                    Node a = left, b = term();
                    left = c -> number(num(a.eval(c)) - num(b.eval(c)));
                } else {
                    return left;
                }
            }
        }

        /** Ikili eksi: '-' sonrasi bosluk olsun ya da olmasin; ama tek basina negatif sayi degil. */
        private boolean peekMinus() {
            skipWs();
            return pos < s.length() && s.charAt(pos) == '-';
        }

        Node term() {
            Node left = factor();
            while (true) {
                if (eat('*')) {
                    Node a = left, b = factor();
                    left = c -> number(num(a.eval(c)) * num(b.eval(c)));
                } else if (eat('/')) {
                    Node a = left, b = factor();
                    left = c -> {
                        double d = num(b.eval(c));
                        if (d == 0) throw new IllegalArgumentException("sıfıra bölme");
                        return number(num(a.eval(c)) / d);
                    };
                } else if (eat('%')) {
                    Node a = left, b = factor();
                    left = c -> number(num(a.eval(c)) % num(b.eval(c)));
                } else {
                    return left;
                }
            }
        }

        Node factor() {
            skipWs();
            if (pos >= s.length()) throw new Syntax();
            char ch = s.charAt(pos);
            if (ch == '(') {
                pos++;
                Node n = expr();
                if (!eat(')')) throw new Syntax();
                return n;
            }
            if (ch == '-') {
                pos++;
                Node n = factor();
                return c -> number(-num(n.eval(c)));
            }
            if (ch == '"') {
                int end = s.indexOf('"', pos + 1);
                if (end < 0) throw new Syntax();
                String lit = s.substring(pos + 1, end);
                pos = end + 1;
                return new Lit(lit);
            }
            if (ch == '$') return ref();
            if (Character.isDigit(ch) || ch == '.') return numberLit();
            if (Character.isLetter(ch) || ch == '_') return call();
            throw new Syntax();
        }

        Node numberLit() {
            int start = pos;
            if (s.startsWith("0x", pos) || s.startsWith("0X", pos)) {
                pos += 2;
                while (pos < s.length() && Character.digit(s.charAt(pos), 16) >= 0) pos++;
                long v = Long.parseLong(s.substring(start + 2, pos), 16);
                return new Lit(v);
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            String t = s.substring(start, pos);
            try {
                return new Lit(t.contains(".") ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t));
            } catch (NumberFormatException e) {
                throw new Syntax();
            }
        }

        /** $rx.yol  ya da  ${degisken} */
        Node ref() {
            pos++;
            if (pos < s.length() && s.charAt(pos) == '{') {
                int end = s.indexOf('}', pos);
                if (end < 0) throw new Syntax();
                String name = s.substring(pos + 1, end).trim();
                pos = end + 1;
                return c -> {
                    String v = c.var(name);
                    if (v == null) throw new IllegalArgumentException("tanımsız değişken: " + name);
                    return v;
                };
            }
            if (!s.startsWith("rx.", pos)) throw new Syntax();
            pos += 3;
            int start = pos;
            while (pos < s.length() && isPathChar(s.charAt(pos))) pos++;
            String path = s.substring(start, pos);
            if (path.isEmpty()) throw new Syntax();
            return c -> rxValue(c, path);
        }

        boolean isPathChar(char ch) {
            return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '[' || ch == ']';
        }

        Node call() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            String name = s.substring(start, pos).toLowerCase(Locale.ROOT);
            if (!eat('(')) throw new Syntax(); // duz kelime: ifade degil, duz deger
            // argumanlari ham metin olarak ayir (list(A|B) gibi duz degerler icin)
            List<String> args = new ArrayList<>();
            int depth = 0;
            int argStart = pos;
            boolean quoted = false;
            for (; pos < s.length(); pos++) {
                char ch = s.charAt(pos);
                if (ch == '"') quoted = !quoted;
                if (quoted) continue;
                if (ch == '(') depth++;
                else if (ch == ')') {
                    if (depth == 0) break;
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    args.add(s.substring(argStart, pos).trim());
                    argStart = pos + 1;
                }
            }
            if (pos >= s.length()) throw new Syntax();
            String lastArg = s.substring(argStart, pos).trim();
            if (!lastArg.isEmpty() || !args.isEmpty()) args.add(lastArg);
            pos++; // ')'
            return function(name, args);
        }
    }

    private static Object rxValue(Context c, String path) {
        String v = c.rx(path);
        if (v == null) throw new IllegalArgumentException("gelen mesajda alan yok: " + path);
        return v;
    }

    private Node arg(List<String> args, int i, String def) {
        String a = i < args.size() ? args.get(i) : def;
        if (a == null) throw new IllegalArgumentException("eksik argüman");
        return Expr.parse(a).root;
    }

    private Node function(String name, List<String> args) {
        switch (name) {
            case "counter": {
                Node start = arg(args, 0, "0");
                Node step = arg(args, 1, "1");
                Node max = args.size() > 2 ? arg(args, 2, null) : null;
                double[] cur = {Double.NaN};
                return c -> {
                    double st = num(start.eval(c));
                    if (Double.isNaN(cur[0])) cur[0] = st;
                    else cur[0] += num(step.eval(c));
                    if (max != null && cur[0] > num(max.eval(c))) cur[0] = st;
                    return number(cur[0]);
                };
            }
            case "random": {
                Node lo = arg(args, 0, "0");
                Node hi = arg(args, 1, "100");
                return c -> {
                    long a = (long) num(lo.eval(c));
                    long b = (long) num(hi.eval(c));
                    if (b < a) throw new IllegalArgumentException("random: üst sınır alttan küçük");
                    return a + (long) Math.floor(random.nextDouble() * (b - a + 1));
                };
            }
            case "randomf": {
                Node lo = arg(args, 0, "0");
                Node hi = arg(args, 1, "1");
                return c -> {
                    double a = num(lo.eval(c));
                    return Math.round((a + random.nextDouble() * (num(hi.eval(c)) - a)) * 1000.0) / 1000.0;
                };
            }
            case "sine":
            case "ramp":
            case "square": {
                Node lo = arg(args, 0, "0");
                Node hi = arg(args, 1, "100");
                Node period = arg(args, 2, "10000");
                String kind = name;
                return c -> {
                    double a = num(lo.eval(c));
                    double b = num(hi.eval(c));
                    double p = Math.max(1, num(period.eval(c)));
                    double phase = (c.now() % (long) p) / p;
                    double f;
                    if (kind.equals("sine")) f = (Math.sin(2 * Math.PI * phase) + 1) / 2;
                    else if (kind.equals("ramp")) f = phase;
                    else f = phase < 0.5 ? 0 : 1;
                    double v = a + (b - a) * f;
                    return kind.equals("sine") ? (Object) (Math.round(v * 1000.0) / 1000.0) : number(Math.round(v * 1000.0) / 1000.0);
                };
            }
            case "list":
            case "pick": {
                if (args.isEmpty()) throw new IllegalArgumentException(name + ": değer listesi gerekli (A|B|C)");
                String[] items = String.join(",", args).split("\\|");
                for (int i = 0; i < items.length; i++) items[i] = unquote(items[i].trim());
                int[] idx = {-1};
                boolean seq = name.equals("list");
                return c -> {
                    if (seq) {
                        idx[0] = (idx[0] + 1) % items.length;
                        return items[idx[0]];
                    }
                    return items[random.nextInt(items.length)];
                };
            }
            case "toggle": {
                boolean[] state = {true};
                return c -> {
                    state[0] = !state[0];
                    return state[0];
                };
            }
            case "now":
                return Context::now;
            case "rx": {
                if (args.size() != 1) throw new IllegalArgumentException("rx(alan)");
                String path = args.get(0);
                return c -> rxValue(c, path);
            }
            case "last": {
                if (args.size() != 3) throw new IllegalArgumentException("last(bağlantı, MESAJ, alan)");
                String ep = unquote(args.get(0));
                String msg = args.get(1);
                String path = args.get(2);
                return c -> {
                    String v = c.last(ep, msg, path);
                    if (v == null) throw new IllegalArgumentException(ep + " için " + msg + "." + path + " henüz alınmadı");
                    return v;
                };
            }
            default:
                throw new IllegalArgumentException("bilinmeyen fonksiyon: " + name);
        }
    }
}
