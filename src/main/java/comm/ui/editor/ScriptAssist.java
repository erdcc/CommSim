package comm.ui.editor;

import comm.schema.MessageDef;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ParameterizedCompletion;
import org.fife.ui.autocomplete.TemplateCompletion;

import javax.swing.Icon;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baglama duyarli tamamlama: imlecin bulundugu yere gore baglanti adlari, o baglantinin mesajlari,
 * mesaj alanlari, enum degerleri, API yontemleri, degiskenler ve hazir kaliplar onerilir.
 */
final class ScriptAssist extends DefaultCompletionProvider {
    private final Supplier<List<Target>> targets;
    private final Supplier<java.nio.file.Path> baseDir;
    private boolean groovy;

    ScriptAssist(Supplier<List<Target>> targets, Supplier<java.nio.file.Path> baseDir) {
        this.targets = targets;
        this.baseDir = baseDir;
        setAutoActivationRules(true, ".\"'={");
    }

    void setGroovy(boolean g) { groovy = g; }

    // =====================================================================
    //  baglam
    // =====================================================================

    /** Imlecin baglami. */
    static final class Ctx {
        String prefix = "";
        boolean inString;
        /** Icinde bulunulan cagri ya da komut adi. */
        String call;
        /** Cagrinin kacinci argumani yaziliyor (0 tabanli). */
        int arg;
        /** Onceki argumanlarin ham metni. */
        List<String> args = new ArrayList<>();
        /** Groovy: "nesne." sonrasi uye; nesne adi. */
        String member;
        /** "alan: deger" / "alan=deger" deger konumunda alan adi. */
        String valueOf;
        /** Groovy: m["...  indeksleme. */
        String indexOf;
        /** Senaryo dili: satirin ilk sozcugu yaziliyor. */
        boolean commandPos;
        /** Senaryo dili: ${ icindeyiz. */
        boolean inVar;
    }

    private static String lineBefore(JTextComponent c) {
        try {
            int caret = c.getCaretPosition();
            Element root = c.getDocument().getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(caret));
            return c.getDocument().getText(line.getStartOffset(), caret - line.getStartOffset());
        } catch (BadLocationException e) {
            return "";
        }
    }

    Ctx context(JTextComponent comp) { return groovy ? groovyContext(lineBefore(comp)) : dslContext(lineBefore(comp)); }

    private static boolean identChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private static String trailingIdent(String s) {
        int i = s.length();
        while (i > 0 && identChar(s.charAt(i - 1))) i--;
        return s.substring(i);
    }

    /** Bir cagri cercevesi (parantez ya da komut bicimi). */
    private static final class Frame {
        final String name;
        final List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        Frame(String name) { this.name = name; }
    }

    private static final Pattern COMMAND_STYLE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(?![=+\\-*/<>!.:?|&])");

    static Ctx groovyContext(String s) {
        Ctx c = new Ctx();
        java.util.ArrayDeque<Frame> st = new java.util.ArrayDeque<>();
        int start = 0;
        Matcher cm = COMMAND_STYLE.matcher(s);
        if (cm.find() && GroovyInfo.METHODS.containsKey(cm.group(1))) {
            st.push(new Frame(cm.group(1)));
            start = cm.end();
        } else {
            st.push(new Frame(null));
        }
        boolean inStr = false;
        char quote = 0;
        int strStart = -1;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            Frame top = st.peek();
            if (inStr) {
                if (ch == '\\' && i + 1 < s.length()) {
                    top.cur.append(ch).append(s.charAt(++i));
                    continue;
                }
                top.cur.append(ch);
                if (ch == quote) inStr = false;
                continue;
            }
            switch (ch) {
                case '"':
                case '\'':
                    inStr = true;
                    quote = ch;
                    strStart = i;
                    top.cur.append(ch);
                    break;
                case '(':
                    st.push(new Frame(trailingIdent(s.substring(0, i).replaceAll("\\s+$", ""))));
                    break;
                case '[':
                case '{':
                    st.push(new Frame(null));
                    break;
                case ')':
                case ']':
                case '}':
                    if (st.size() > 1) st.pop();
                    st.peek().cur.append('…');
                    break;
                case ',':
                    top.args.add(top.cur.toString().trim());
                    top.cur = new StringBuilder();
                    break;
                default:
                    top.cur.append(ch);
            }
        }
        Frame top = st.peek();
        c.call = top.name;
        c.arg = top.args.size();
        c.args = top.args;
        String cur = top.cur.toString();
        if (inStr) {
            c.inString = true;
            c.prefix = s.substring(strStart + 1);
            String before = s.substring(0, strStart).replaceAll("\\s+$", "");
            Matcher kv = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*|\"[^\"]*\"|'[^']*')\\s*:$").matcher(before);
            if (kv.find()) c.valueOf = unquote(kv.group(1));
            Matcher ix = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\[$").matcher(before);
            if (ix.find()) c.indexOf = ix.group(1);
            return c;
        }
        c.prefix = trailingIdent(s);
        String rest = s.substring(0, s.length() - c.prefix.length());
        if (rest.endsWith(".")) {
            c.member = trailingIdent(rest.substring(0, rest.length() - 1));
            return c;
        }
        Matcher kv = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*|\"[^\"]*\"|'[^']*')\\s*:\\s*$").matcher(rest);
        if (kv.find() && cur.contains(":")) c.valueOf = unquote(kv.group(1));
        return c;
    }

    private static String unquote(String s) {
        return (s.startsWith("\"") || s.startsWith("'")) && s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
    }

    static Ctx dslContext(String s) {
        Ctx c = new Ctx();
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') {
                quoted = true;
                any = true;
            } else if (ch == '#') {
                c.call = "#"; // yorum: oneri yok
                return c;
            } else if (Character.isWhitespace(ch)) {
                if (any) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(ch);
                any = true;
            }
        }
        boolean partial = any || quoted;
        String current = partial ? cur.toString() : "";
        c.inString = quoted;
        if (tokens.isEmpty()) {
            c.commandPos = true;
            c.prefix = current;
            return c;
        }
        c.call = tokens.get(0).toLowerCase(Locale.ROOT);
        c.arg = tokens.size() - 1;
        c.args = tokens.subList(1, tokens.size());
        int v = current.lastIndexOf("${");
        if (v >= 0 && current.indexOf('}', v) < 0) {
            c.inVar = true;
            c.prefix = current.substring(v + 2);
            return c;
        }
        int eq = current.indexOf('=');
        if (eq > 0 && !quoted) {
            c.valueOf = current.substring(0, eq);
            c.prefix = current.substring(eq + 1);
            return c;
        }
        c.prefix = current;
        return c;
    }

    // =====================================================================
    //  oneriler
    // =====================================================================

    @Override public String getAlreadyEnteredText(JTextComponent comp) { return context(comp).prefix; }

    @Override public List<Completion> getCompletionsAt(JTextComponent tc, Point p) { return null; }

    @Override public List<ParameterizedCompletion> getParameterizedCompletions(JTextComponent tc) { return null; }

    @Override protected List<Completion> getCompletionsImpl(JTextComponent comp) {
        Ctx c = context(comp);
        List<Completion> all = groovy ? groovyCandidates(c, comp.getText()) : dslCandidates(c, comp.getText());
        return filter(all, c.prefix);
    }

    /** Bas eslesmesi once, sonra icerme; Turkce harfler sadelestirilir. */
    static List<Completion> filter(List<Completion> all, String prefix) {
        String p = fold(prefix);
        List<Completion> head = new ArrayList<>();
        List<Completion> mid = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Completion x : all) {
            String key = x.getInputText() + "\u0000" + x.getReplacementText();
            if (!seen.add(key)) continue;
            String t = fold(x.getInputText());
            if (p.isEmpty() || t.startsWith(p)) head.add(x);
            else if (p.length() >= 2 && t.contains(p)) mid.add(x);
        }
        // tek oneri ve yazilanla ayniysa gosterme
        if (head.size() == 1 && mid.isEmpty() && fold(head.get(0).getReplacementText()).equals(p)) return new ArrayList<>();
        head.addAll(mid);
        return head;
    }

    /** Turkce harfleri sadelestirip kucuk harfe cevirir ("gönder" = "gonder"). */
    static String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'ı': case 'I': case 'İ': case 'i': sb.append('i'); break;
                case 'ğ': case 'Ğ': sb.append('g'); break;
                case 'ü': case 'Ü': sb.append('u'); break;
                case 'ş': case 'Ş': sb.append('s'); break;
                case 'ö': case 'Ö': sb.append('o'); break;
                case 'ç': case 'Ç': sb.append('c'); break;
                case '"': break;
                default: sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Listede gorunen etiket ile eklenen metin ayri olabilen oneri (orn. tirnakli baglanti adi). */
    private static final class Item extends BasicCompletion {
        private final String label;

        Item(DefaultCompletionProvider p, String replacement, String shortDesc, String summary, Icon icon, String label) {
            super(p, replacement, shortDesc, summary);
            this.label = label;
            setIcon(icon);
        }

        @Override public String getInputText() { return label; }

        @Override public String toString() { return label; }
    }

    private Completion item(String replacement, String shortDesc, String summary, Icon icon, String label) {
        return new Item(this, replacement, shortDesc, summary, icon, label);
    }

    // ---- Groovy ----

    private List<Completion> groovyCandidates(Ctx c, String doc) {
        List<Completion> out = new ArrayList<>();
        List<Target> ts = targets.get();
        GroovyInfo.Method m = c.call == null ? null : GroovyInfo.METHODS.get(c.call);
        GroovyInfo.Arg kind = m == null ? null : m.arg(c.arg);
        if (c.inString) {
            if (c.indexOf != null) {
                for (Target.Field f : varFields(c.indexOf, doc, ts)) out.add(field(f.path, f, ""));
                return out;
            }
            if (c.valueOf != null && m != null) {
                for (Target.Field f : msgFields(m, c, doc, ts)) {
                    if (f.path.equals(c.valueOf) && f.enums != null) for (String e : f.enums) out.add(enumValue(e, f));
                }
                return out;
            }
            if (kind == GroovyInfo.Arg.EP) for (Target t : filterTargets(ts, c.call)) out.add(endpoint(t, t.name));
            else if (kind == GroovyInfo.Arg.MSG) out.addAll(messages(resolve(c.args.isEmpty() ? "" : c.args.get(0), doc), ts));
            else if (kind == GroovyInfo.Arg.MBTABLE) out.addAll(words(new String[]{"hr", "ir", "coil", "di"}, "Modbus tablosu"));
            else if (kind == GroovyInfo.Arg.OIDTYPE) out.addAll(words(SNMP_TYPES, "SNMP tipi"));
            return out;
        }
        if (c.member != null) {
            if (c.member.equals("sim")) for (GroovyInfo.Method x : GroovyInfo.METHODS.values()) out.add(method(x, true));
            else for (Target.Field f : varFields(c.member, doc, ts)) {
                if (f.path.matches("[A-Za-z_][A-Za-z0-9_]*")) out.add(field(f.path, f, ""));
            }
            return out;
        }
        if (c.valueOf != null && m != null) {
            for (Target.Field f : msgFields(m, c, doc, ts)) {
                if (f.path.equals(c.valueOf) && f.enums != null) {
                    for (String e : f.enums) out.add(enumValue(e.equals("true") || e.equals("false") ? e : "\"" + e + "\"", f));
                }
            }
        } else if (kind == GroovyInfo.Arg.FIELDS) {
            for (Target.Field f : msgFields(m, c, doc, ts)) {
                String key = f.path.matches("[A-Za-z_][A-Za-z0-9_]*") ? f.path : "\"" + f.path + "\"";
                out.add(field(key + ": ", f, f.path));
            }
        }
        for (String v : groovyVariables(doc)) out.add(variable(v));
        if (c.call == null || kind == null || c.valueOf != null || kind == GroovyInfo.Arg.FIELDS) {
            for (GroovyInfo.Method x : GroovyInfo.METHODS.values()) out.add(method(x, false));
            for (String[] t : GroovyInfo.TEMPLATES) out.add(template(t[0], t[1], t[2], "kalıp"));
            for (String k : GroovyInfo.KEYWORDS) out.add(keyword(k));
            out.add(variable("sim"));
            out.add(variable("vars"));
        }
        return out;
    }

    /** Groovy'de bir argumanin degeri: "..." ise metin, def x = "..." ile tanimli degiskense degeri. */
    private static String resolve(String arg, String doc) {
        String a = arg.trim();
        if (a.length() >= 2 && (a.startsWith("\"") || a.startsWith("'"))) return a.substring(1, a.length() - 1);
        if (a.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Matcher m = Pattern.compile("(?m)(?:def|String)\\s+" + Pattern.quote(a) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(doc);
            String last = null;
            while (m.find()) last = m.group(2);
            if (last != null) return last;
        }
        return null;
    }

    private List<Target.Field> msgFields(GroovyInfo.Method m, Ctx c, String doc, List<Target> ts) {
        if (m == null || c.args.size() < 2) return new ArrayList<>();
        return fieldsOf(resolve(c.args.get(0), doc), resolve(c.args.get(1), doc), ts);
    }

    /** def m = waitFor(X, "MSG") ya da last(...) ile atanan degiskenin alanlari. */
    private static List<Target.Field> varFields(String var, String doc, List<Target> ts) {
        Matcher m = Pattern.compile("(?m)(?:def\\s+)?" + Pattern.quote(var)
                + "\\s*=\\s*(?:waitFor|last)\\s*\\(?\\s*([^,]+?)\\s*,\\s*([^,)]+)").matcher(doc);
        String ep = null;
        String msg = null;
        while (m.find()) {
            ep = resolve(m.group(1), doc);
            msg = resolve(m.group(2), doc);
        }
        return fieldsOf(ep, msg, ts);
    }

    private static List<Target.Field> fieldsOf(String ep, String msg, List<Target> ts) {
        List<Target.Field> out = new ArrayList<>();
        if (msg == null) return out;
        for (Target t : ts) {
            if (ep != null && !t.name.equals(ep)) continue;
            MessageDef md = Target.message(t.schema, msg);
            if (md != null) {
                out.addAll(Target.fields(t.schema, md));
                if (ep != null) break;
            }
        }
        return out;
    }

    private static Set<String> groovyVariables(String doc) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?:\\bdef\\s+|\\bfor\\s*\\(\\s*(?:def\\s+)?|\\{\\s*)([A-Za-z_][A-Za-z0-9_]*)").matcher(doc);
        while (m.find()) out.add(m.group(1));
        Matcher p = Pattern.compile("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*->").matcher(doc);
        while (p.find()) {
            out.add(p.group(1));
            out.add(p.group(2));
        }
        out.removeAll(java.util.Arrays.asList(GroovyInfo.KEYWORDS));
        return out;
    }

    // ---- senaryo dili ----

    private List<Completion> dslCandidates(Ctx c, String doc) {
        List<Completion> out = new ArrayList<>();
        if ("#".equals(c.call)) return out;
        List<Target> ts = targets.get();
        if (c.commandPos) {
            for (DslInfo.Cmd x : DslInfo.COMMANDS.values()) out.add(command(x));
            return out;
        }
        if (c.inVar) {
            for (String v : dslVariables(doc)) out.add(item(v + "}", "değişken", null, ICON_VAR, v));
            return out;
        }
        DslInfo.Cmd cmd = DslInfo.COMMANDS.get(c.call);
        if (cmd == null) return out;
        if (c.valueOf != null) {
            for (Target.Field f : fieldsOf(c.args.isEmpty() ? null : c.args.get(0), c.args.size() > 1 ? c.args.get(1) : null, ts)) {
                if (f.path.equals(c.valueOf) && f.enums != null) for (String e : f.enums) out.add(enumValue(e, f));
            }
            if (out.isEmpty() && c.call.equals("var")) out.addAll(expressionHelpers(doc));
            return out;
        }
        DslInfo.Arg kind = cmd.arg(c.arg);
        if (kind == null) {
            if (c.call.equals("modbus") && c.arg == 1) kind = DslInfo.Arg.MBOP;
        }
        if (kind == null) {
            if (c.call.equals("if") || c.call.equals("while") || c.call.equals("assert") || c.call.equals("var") && c.arg >= 2
                    || c.call.equals("log") || c.call.equals("wait") || c.call.equals("call") && c.arg >= 1) {
                out.addAll(expressionHelpers(doc));
            }
            return out;
        }
        switch (kind) {
            case EP:
                for (Target t : filterTargets(ts, c.call)) {
                    out.add(endpoint(t, c.inString || !t.name.contains(" ") ? t.name : "\"" + t.name + "\""));
                }
                break;
            case MSG:
                out.addAll(messages(c.args.isEmpty() ? null : c.args.get(0), ts));
                break;
            case FIELDS:
                for (Target.Field f : fieldsOf(c.args.get(0), c.args.size() > 1 ? c.args.get(1) : null, ts)) out.add(field(f.path + "=", f, f.path));
                break;
            case MBOP: out.addAll(words(new String[]{"read", "write", "coil"}, "Modbus işlemi")); break;
            case MBTABLE: out.addAll(words(new String[]{"hr", "ir", "coil", "di"}, "Modbus tablosu")); break;
            case SNMPOP: out.addAll(words(new String[]{"get", "getnext", "walk", "set"}, "SNMP işlemi")); break;
            case ONOFF: out.addAll(words(new String[]{"on", "off"}, "")); break;
            case FUNC:
                Matcher m = Pattern.compile("(?m)^\\s*function\\s+([A-Za-z_][A-Za-z0-9_]*)(.*)$").matcher(doc);
                while (m.find()) out.add(item(m.group(1), "fonksiyon", "<b>function " + m.group(1) + m.group(2) + "</b>", ICON_FUNC, m.group(1)));
                break;
            case FILE:
                java.nio.file.Path base = baseDir.get();
                java.io.File dir = base != null ? base.toFile() : new java.io.File(".");
                String[] files = dir.list((d, n) -> n.endsWith(".scn"));
                if (files != null) {
                    java.util.Arrays.sort(files);
                    out.addAll(words(files, "senaryo dosyası"));
                }
                break;
            default:
                break;
        }
        return out;
    }

    private List<Completion> expressionHelpers(String doc) {
        List<Completion> out = new ArrayList<>();
        for (String v : dslVariables(doc)) out.add(item("${" + v + "}", "değişken", null, ICON_VAR, v));
        for (String[] f : DslInfo.FUNCTIONS) {
            out.add(item(f[0] + "(", "işlev", "<b>" + esc(f[1]) + "</b><br>" + esc(f[2]), ICON_FUNC, f[0]));
        }
        return out;
    }

    static Set<String> dslVariables(String doc) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*var\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(doc);
        while (m.find()) out.add(m.group(1));
        Matcher f = Pattern.compile("(?m)^\\s*function\\s+[A-Za-z_][A-Za-z0-9_]*((?:\\s+[A-Za-z_][A-Za-z0-9_]*)*)").matcher(doc);
        while (f.find()) for (String p : f.group(1).trim().split("\\s+")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ---- ortak ----

    private static final String[] SNMP_TYPES = {"INTEGER", "OCTET_STRING", "HEX_STRING", "OID", "IPADDRESS", "COUNTER32",
            "GAUGE32", "TIMETICKS", "COUNTER64", "UNSIGNED32"};

    /** Komuta uygun baglantilar (modbus -&gt; master, mbset -&gt; slave ...). */
    private static List<Target> filterTargets(List<Target> ts, String call) {
        List<Target> out = new ArrayList<>();
        for (Target t : ts) {
            boolean ok;
            switch (call == null ? "" : call) {
                case "modbus": case "modbusRead": case "modbusWrite": case "modbusCoil": ok = t.modbusMaster; break;
                case "mbset": ok = t.modbusSlave; break;
                case "snmp": case "snmpGet": case "snmpWalk": case "snmpSet": ok = t.snmpManager; break;
                case "trap": ok = t.snmpAgent; break;
                case "send": case "set": case "expect": ok = t.schema != null; break;
                default: ok = true;
            }
            if (ok) out.add(t);
        }
        return out.isEmpty() ? ts : out;
    }

    private List<Completion> messages(String ep, List<Target> ts) {
        List<Completion> out = new ArrayList<>();
        for (Target t : ts) {
            if (ep != null && !t.name.equals(ep)) continue;
            if (t.modbusMaster) out.addAll(words(new String[]{"FC01", "FC02", "FC03", "FC04", "FC05", "FC06", "FC15", "FC16"}, "Modbus yanıtı"));
            if (t.snmpManager) out.addAll(words(new String[]{"GET", "GETNEXT", "GETBULK", "SET", "TRAP", "RESPONSE"}, "SNMP"));
            if (t.schema == null) continue;
            for (MessageDef m : t.schema.messages()) {
                StringBuilder d = new StringBuilder("<b>").append(esc(m.name)).append("</b>  (id ").append(m.id).append(")<br><br>");
                for (Target.Field f : Target.fields(t.schema, m)) {
                    d.append("<code>").append(esc(f.path)).append("</code> — ").append(esc(f.type));
                    if (f.enums != null && !f.type.equals("bool")) d.append(" {").append(esc(String.join(" | ", f.enums))).append('}');
                    d.append("<br>");
                }
                out.add(item(m.name, "mesaj " + m.id + " · " + t.name, d.toString(), ICON_MSG, m.name));
            }
        }
        return out;
    }

    private List<Completion> words(String[] ws, String desc) {
        List<Completion> out = new ArrayList<>();
        for (String w : ws) out.add(item(w, desc, null, ICON_WORD, w));
        return out;
    }

    private Completion endpoint(Target t, String replacement) {
        String desc = "<b>" + esc(t.name) + "</b><br>" + esc(t.kind) + (t.schema == null ? "" : "<br>şemalı: " + t.schema.messages().size() + " mesaj");
        return item(replacement, t.kind, desc, ICON_EP, t.name);
    }

    private Completion field(String replacement, Target.Field f, String label) {
        String d = "<b>" + esc(f.path) + "</b><br>" + esc(f.type)
                + (f.enums != null ? "<br>seçenekler: " + esc(String.join(", ", f.enums)) : "")
                + (f.def != null ? "<br>varsayılan: " + esc(f.def) : "");
        return item(replacement, f.type, d, ICON_FIELD, label.isEmpty() ? f.path : label);
    }

    private Completion enumValue(String v, Target.Field f) {
        return item(v, f.path, null, ICON_WORD, v.replace("\"", ""));
    }

    private Completion variable(String v) { return item(v, "değişken", null, ICON_VAR, v); }

    private Completion keyword(String k) { return item(k, "anahtar sözcük", null, ICON_WORD, k); }

    private Completion method(GroovyInfo.Method x, boolean member) {
        String def = member ? x.name + "(" + "${cursor})" : x.template;
        TemplateCompletion t = new TemplateCompletion(this, x.name, x.signature, def, x.signature,
                "<b>" + esc(x.signature) + "</b><br><br>" + esc(x.doc));
        t.setIcon(ICON_FUNC);
        return t;
    }

    private Completion command(DslInfo.Cmd x) {
        TemplateCompletion t = new TemplateCompletion(this, x.name, x.syntax, x.template, x.syntax,
                "<b>" + esc(x.syntax) + "</b><br><br>" + esc(x.doc));
        t.setIcon(DslInfo.BLOCK_WORDS.contains(x.name) ? ICON_WORD : ICON_FUNC);
        return t;
    }

    private Completion template(String name, String def, String desc, String kind) {
        TemplateCompletion t = new TemplateCompletion(this, name, name + " — " + desc, def, kind,
                "<b>" + esc(desc) + "</b><pre>" + esc(def.replace("${cursor}", "").replaceAll("\\$\\{([^}]*)}", "$1")) + "</pre>");
        t.setIcon(ICON_TEMPLATE);
        return t;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ---- ikonlar ----

    static final Icon ICON_FUNC = new LetterIcon('m', 0x2F6FEB);
    static final Icon ICON_FIELD = new LetterIcon('f', 0xB26A00);
    static final Icon ICON_VAR = new LetterIcon('v', 0x1A7F4B);
    static final Icon ICON_EP = new LetterIcon('b', 0x8250DF);
    static final Icon ICON_MSG = new LetterIcon('M', 0xC2410C);
    static final Icon ICON_WORD = new LetterIcon('k', 0x6E7781);
    static final Icon ICON_TEMPLATE = new LetterIcon('t', 0x0E7490);

    /** Renkli daire icinde tek harf (IntelliJ'deki tur ikonlari gibi). */
    static final class LetterIcon implements Icon {
        private final char letter;
        private final java.awt.Color color;

        LetterIcon(char letter, int rgb) {
            this.letter = letter;
            this.color = new java.awt.Color(rgb);
        }

        @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
            g.fillOval(x, y, 14, 14);
            g.setColor(color);
            g.drawOval(x, y, 13, 13);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 9));
            java.awt.FontMetrics fm = g.getFontMetrics();
            String s = String.valueOf(letter);
            g.drawString(s, x + (14 - fm.stringWidth(s)) / 2, y + (14 + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }

        @Override public int getIconWidth() { return 14; }

        @Override public int getIconHeight() { return 14; }
    }
}
