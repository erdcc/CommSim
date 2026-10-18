package comm.runtime;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.core.ResponseRule;
import comm.core.TrafficBus;
import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.Values;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Otomatik yanit kurallarini calistirir. Gelen her cerceve icin baglantinin kurallari sirayla denenir;
 * ilk eslesen kural (gecikmeyle) yaniti gonderen ese yollar. Modbus ve SNMP uclari kendi yanitlarini verir.
 */
public final class RuleEngine implements TrafficBus.Listener {
    private static final Pattern COND = Pattern.compile("^\\s*(.+?)\\s*(!=|>=|<=|=|>|<)\\s*(.*?)\\s*$");

    /** Kural basina derlenmis atamalar (durumlu ureteçler kurallar arasinda korunur). */
    private static final class Compiled {
        String text;
        Map<String, Expr> assignments = new LinkedHashMap<>();
    }

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rules");
        t.setDaemon(true);
        return t;
    });
    private final Map<ResponseRule, AtomicLong> counts = new IdentityHashMap<>();
    private final Map<ResponseRule, Compiled> compiled = new IdentityHashMap<>();

    public RuleEngine(TrafficBus bus) { bus.addListener(this); }

    public void shutdown() { exec.shutdownNow(); }

    /** Kuralin tetiklenme sayisi. */
    public long count(ResponseRule r) {
        synchronized (counts) {
            AtomicLong c = counts.get(r);
            return c == null ? 0 : c.get();
        }
    }

    @Override public void onStateChanged(Endpoint endpoint) { }

    @Override public void onEntry(LogEntry e) {
        if (e.dir != Direction.RX || !e.isFrame()) return;
        Endpoint ep = e.endpoint;
        if (ep.config().kind.isModbus() || ep.config().kind.isSnmp() || ep.config().rules.isEmpty()) return;
        exec.execute(() -> handle(ep, e));
    }

    private void handle(Endpoint ep, LogEntry e) {
        int index = 0;
        for (ResponseRule r : ep.config().rules) {
            index++;
            if (!r.enabled) continue;
            try {
                if (!matches(r, e)) continue;
            } catch (IllegalArgumentException ex) {
                ep.report(e.peer, "Kural #" + index + " koşulu hatalı: " + ex.getMessage(), true);
                continue;
            }
            synchronized (counts) {
                counts.computeIfAbsent(r, k -> new AtomicLong()).incrementAndGet();
            }
            if (r.reply.trim().isEmpty()) return;
            int n = index;
            Runnable send = () -> respond(ep, r, e, n);
            if (r.delayMs > 0) exec.schedule(send, r.delayMs, TimeUnit.MILLISECONDS);
            else send.run();
            return; // ilk eslesen kural
        }
    }

    /** Kural eslesiyor mu. @throws IllegalArgumentException kosul yazimi hataliysa */
    static boolean matches(ResponseRule r, LogEntry e) {
        String when = r.when.trim();
        if (when.isEmpty()) return false;
        if (!when.equals("*")) {
            if (when.toLowerCase(Locale.ROOT).startsWith("hex:")) {
                byte[] p = Hex.parse(when.substring(4));
                if (indexOf(e.data, p) < 0) return false;
            } else {
                Decoded d = e.decoded;
                if (d == null || d.message == null || !d.message.name.equalsIgnoreCase(when)) return false;
            }
        }
        return conditionHolds(r.condition, received(e.decoded));
    }

    static boolean conditionHolds(String condition, Map<String, String> rx) {
        if (condition == null || condition.trim().isEmpty()) return true;
        for (String part : condition.split(";")) {
            if (part.trim().isEmpty()) continue;
            Matcher m = COND.matcher(part);
            if (!m.matches()) throw new IllegalArgumentException("'alan=değer' bekleniyor: " + part.trim());
            String actual = rx.get(m.group(1));
            if (actual == null) return false;
            if (!compare(actual, m.group(2), m.group(3))) return false;
        }
        return true;
    }

    private static boolean compare(String a, String op, String b) { return Expr.compare(a, op, b); }

    /** Gelen mesajin degerleri: govde yollari ve "başlık." onekli baslik yollari. */
    static Map<String, String> received(Decoded d) {
        Map<String, String> out = new LinkedHashMap<>();
        if (d == null || d.message == null) return out;
        out.putAll(Values.receivedTexts(d.message.body, d.body, ""));
        // baslik semasi mesaj tanimindan bagimsiz; cozumdeki ham degerlerden yazilir
        for (Map.Entry<String, Object> h : d.header.entrySet()) out.put(ParameterStore.HEADER + h.getKey(), String.valueOf(h.getValue()));
        return out;
    }

    private void respond(Endpoint ep, ResponseRule r, LogEntry e, int index) {
        try {
            byte[] data = build(ep, r, e);
            ep.send(data, peerFor(ep, e));
        } catch (IllegalArgumentException | IOException ex) {
            ep.report(e.peer, "Kural #" + index + " yanıtı gönderilemedi: " + ex.getMessage(), true);
        }
    }

    /** TCP istemcide es tek; sunucu ve UDP'de mesaji gonderen ese yanit verilir. */
    private static String peerFor(Endpoint ep, LogEntry e) {
        return ep.config().kind.isClient() ? null : e.peer;
    }

    byte[] build(Endpoint ep, ResponseRule r, LogEntry e) {
        String reply = r.reply.trim();
        String lower = reply.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hex:")) return Hex.parse(reply.substring(4));
        if (lower.startsWith("text:")) return Hex.parseText(reply.substring(5));
        Schema s = ep.schema();
        if (s == null) throw new IllegalArgumentException("bağlantıya şema atanmamış (yanıt için hex: ya da text: kullanın)");
        MessageDef m = s.byName(reply);
        if (m == null) throw new IllegalArgumentException("şemada mesaj yok: " + reply);
        Map<String, String> rx = received(e.decoded);
        Expr.Context ctx = new Expr.Context() {
            @Override public String rx(String path) { return rx.get(path); }
        };
        Map<String, String> header = new LinkedHashMap<>();
        Map<String, String> body = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> a : compile(r).entrySet()) {
            String k = a.getKey();
            if (k.startsWith("baslik.")) k = ParameterStore.HEADER + k.substring(7);
            (k.startsWith(ParameterStore.HEADER) ? header : body).put(k, a.getValue().eval(ctx));
        }
        Map<String, Object> hv = Values.defaults(s.header);
        Map<String, Object> bv = Values.defaults(m.body);
        List<String> problems = new ArrayList<>(Values.applyTexts(s.header, hv, ParameterStore.HEADER, header));
        problems.addAll(Values.applyTexts(m.body, bv, "", body));
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return Codec.encode(s, m, hv, bv);
    }

    private Map<String, Expr> compile(ResponseRule r) {
        synchronized (compiled) {
            Compiled c = compiled.get(r);
            if (c == null || !r.assignments.equals(c.text)) {
                c = new Compiled();
                c.text = r.assignments;
                c.assignments = parseAssignments(r.assignments);
                compiled.put(r, c);
            }
            return c.assignments;
        }
    }

    /** "a=ifade; b=ifade" -&gt; yol -&gt; ifade. @throws IllegalArgumentException */
    public static Map<String, Expr> parseAssignments(String text) {
        Map<String, Expr> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String part : text.split(";")) {
            if (part.trim().isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("'alan=ifade' bekleniyor: " + part.trim());
            out.put(part.substring(0, eq).trim(), Expr.parse(part.substring(eq + 1)));
        }
        return out;
    }

    /** Kural alanlarini dogrular (arayuz icin). @throws IllegalArgumentException */
    public static void validate(ResponseRule r) {
        String w = r.when.trim();
        if (w.toLowerCase(Locale.ROOT).startsWith("hex:")) Hex.parse(w.substring(4));
        conditionHolds(r.condition, new java.util.HashMap<String, String>());
        String reply = r.reply.trim().toLowerCase(Locale.ROOT);
        if (reply.startsWith("hex:")) Hex.parse(r.reply.trim().substring(4));
        if (reply.startsWith("text:")) Hex.parseText(r.reply.trim().substring(5));
        parseAssignments(r.assignments);
    }

    private static int indexOf(byte[] data, byte[] p) {
        outer:
        for (int i = 0; i + p.length <= data.length; i++) {
            for (int k = 0; k < p.length; k++) if (data[i + k] != p[k]) continue outer;
            return i;
        }
        return -1;
    }
}
