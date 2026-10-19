package comm.scenario;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import comm.modbus.Modbus;
import comm.modbus.ModbusMasterEndpoint;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables.Table;
import comm.scenario.Scenario.Step;
import comm.snmp.SnmpAgentEndpoint;
import comm.snmp.SnmpManagerEndpoint;
import comm.snmp.SnmpValues;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Senaryoyu arka plan thread'inde calistirir. Bir hata senaryoyu durdurur; expect hatalari sayilir. */
public final class ScenarioRunner {
    public interface Output {
        void line(String text, boolean error);
    }

    private static final long DEFAULT_WAITFOR_MS = 5000;

    private static final int INBOX_MAX = 10_000;

    /** Senaryo boyunca alinan cozulmus mesajlar; waitfor eslesen ilk kaydi tuketir (yaris olmaz). */
    private final List<LogEntry> inbox = new java.util.LinkedList<>();
    private final TrafficBus.Listener inboxListener = new TrafficBus.Listener() {
        @Override public void onEntry(LogEntry e) {
            if (e.dir != Direction.RX || e.decoded == null || e.decoded.message == null) return;
            synchronized (inbox) {
                inbox.add(e);
                if (inbox.size() > INBOX_MAX) inbox.remove(0);
                inbox.notifyAll();
            }
        }

        @Override public void onStateChanged(Endpoint endpoint) { }
    };
    private volatile boolean stopRequested;
    private volatile Thread thread;
    private volatile int checks;
    private volatile int failures;
    private volatile boolean errored;
    private volatile boolean continueOnError;
    private final java.util.Map<String, String> vars = new java.util.concurrent.ConcurrentHashMap<>();
    /** Adim basina derlenmis ifadeler: durumlu ureteçler (counter...) donguler boyunca korunur. */
    private final java.util.Map<Step, comm.runtime.Expr[]> compiled = new java.util.IdentityHashMap<>();
    private volatile ScenarioContext ctx;

    /** Akis denetimi: exit, break ve return komutlari bu istisnalarla yukari tasinir. */
    private static class Flow extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Flow() { super(null, null, false, false); }
    }

    /** exit komutu: senaryoyu basariyla bitirir. */
    private static final class Exit extends Flow {
        private static final long serialVersionUID = 1L;
    }

    private static final class Break extends Flow {
        private static final long serialVersionUID = 1L;
    }

    private static final class Return extends Flow {
        private static final long serialVersionUID = 1L;
    }

    private static final int CALL_DEPTH_MAX = 100;
    private volatile java.util.Map<String, Scenario.Function> functions = java.util.Collections.emptyMap();
    private int callDepth;

    /** Son calismanin kontrol sayisi. */
    public int checks() { return checks; }

    /** Son calismada basarisiz kontrol ya da (hatayla devam modunda) hatali komut sayisi. */
    public int failures() { return failures; }

    /** Son calisma hata ya da durdurma ile mi bitti. */
    public boolean errored() { return errored; }

    /** Degiskenlerin anlik kopyasi. */
    public java.util.Map<String, String> vars() { return new java.util.TreeMap<>(vars); }

    public boolean isRunning() { return thread != null; }

    public void stop() {
        stopRequested = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /**
     * Senaryoyu ayristirir ve baslatir.
     * @throws ScenarioException sozdizimi hatasi (calistirmadan once)
     */
    public synchronized void start(String text, ScenarioContext ctx, Output out, Runnable onDone) throws ScenarioException {
        start(text, null, ctx, out, onDone);
    }

    /** @param baseDir include yollarinin klasoru (senaryo dosyasinin klasoru); null ise calisma klasoru */
    public synchronized void start(String text, java.nio.file.Path baseDir, ScenarioContext ctx, Output out, Runnable onDone)
            throws ScenarioException {
        if (thread != null) throw new ScenarioException("senaryo zaten çalışıyor");
        Scenario.Program program = Scenario.parseProgram(text, baseDir);
        functions = program.functions;
        launch(ctx, out, onDone, () -> run(program.steps, ctx, out));
    }

    /**
     * Groovy betigini derler ve calistirir. Betik, {@link Api} yontemlerini dogrudan cagirir:
     * start("..."), send("...", "KONUM", speed: 120), waitFor(...), check(...).
     * @param fileName hata mesajlarinda gorunen ad (orn. test.groovy)
     * @throws ScenarioException derleme hatasi (calistirmadan once)
     */
    public synchronized void startGroovy(String code, String fileName, java.nio.file.Path baseDir, ScenarioContext ctx,
                                         Output out, Runnable onDone) throws ScenarioException {
        if (thread != null) throw new ScenarioException("senaryo zaten çalışıyor");
        functions = java.util.Collections.emptyMap();
        GroovySupport.Compiled compiled = GroovySupport.compile(code, fileName, baseDir);
        launch(ctx, out, onDone, () -> compiled.run(new Api(ctx, out), () -> stopRequested));
    }

    /** Calistirilacak govde. */
    private interface Body {
        void run() throws InterruptedException, ScenarioException;
    }

    private void launch(ScenarioContext ctx, Output out, Runnable onDone, Body body) {
        callDepth = 0;
        stopRequested = false;
        checks = 0;
        failures = 0;
        errored = false;
        continueOnError = false;
        vars.clear();
        compiled.clear();
        this.ctx = ctx;
        synchronized (inbox) {
            inbox.clear();
        }
        TrafficBus bus = ctx.bus();
        bus.addListener(inboxListener);
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                out.line("Senaryo başladı", false);
                String how = "bitti";
                try {
                    body.run();
                } catch (Exit x) {
                    how = "exit ile bitti";
                }
                out.line(String.format(Locale.ROOT, "Senaryo %s (%.1f s)%s", how, (System.currentTimeMillis() - t0) / 1000.0,
                        checks == 0 && failures == 0 ? "" : ", " + checks + " kontrol, " + failures + " başarısız"), failures > 0);
            } catch (InterruptedException e) {
                errored = true;
                out.line("Senaryo durduruldu", true);
            } catch (ScenarioException e) {
                errored = true;
                out.line("HATA " + e.getMessage() + "; senaryo durdu", true);
            } finally {
                bus.removeListener(inboxListener);
                thread = null;
                if (onDone != null) onDone.run();
            }
        }, "scenario");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void run(List<Step> steps, ScenarioContext ctx, Output out) throws InterruptedException, ScenarioException {
        for (Step s : steps) {
            if (stopRequested) throw new InterruptedException();
            if (s.command.equals("repeat")) {
                int n = s.count >= 0 ? s.count : repeatCount(s);
                try {
                    for (int i = 0; i < n; i++) run(s.body, ctx, out);
                } catch (Break b) {
                    // dongu bitti
                }
                continue;
            }
            try {
                switch (s.command) {
                    case "if":
                        run(condition(s) ? s.body : s.elseBody, ctx, out);
                        continue;
                    case "while": {
                        int n = 0;
                        try {
                            while (condition(s)) {
                                if (++n > Scenario.WHILE_MAX) {
                                    throw new IllegalStateException("while " + Scenario.WHILE_MAX + " turu aştı (sonsuz döngü?)");
                                }
                                run(s.body, ctx, out);
                            }
                        } catch (Break b) {
                            // dongu bitti
                        }
                        continue;
                    }
                    case "break":
                        throw new Break();
                    case "return":
                        throw new Return();
                    case "call":
                        call(s, ctx, out);
                        continue;
                    default:
                        exec(s, ctx, out);
                }
            } catch (Flow x) {
                throw x;
            } catch (RuntimeException | IOException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (continueOnError) {
                    failures++;
                    out.line("HATA " + s.where() + " (" + s.command + "): " + msg + " — devam ediliyor", true);
                    continue;
                }
                throw new ScenarioException(s.where() + " (" + s.command + "): " + msg);
            }
        }
    }

    /** "repeat ${n}" gibi ifadeli adet. */
    private int repeatCount(Step s) throws ScenarioException {
        String v;
        try {
            v = exprs(s, s.args.get(0))[0].eval(exprContext);
        } catch (RuntimeException e) {
            throw new ScenarioException(s.where() + " (repeat): " + e.getMessage());
        }
        try {
            int n = (int) Math.round(Double.parseDouble(v.trim()));
            if (n < 0) throw new ScenarioException(s.where() + " (repeat): adet negatif: " + n);
            return n;
        } catch (NumberFormatException e) {
            throw new ScenarioException(s.where() + " (repeat): adet sayı değil: " + v);
        }
    }

    /** Fonksiyonu cagirir: argumanlar parametre degiskenlerine yazilir, donuste eski degerler geri gelir. */
    private void call(Step s, ScenarioContext ctx, Output out) throws InterruptedException, ScenarioException {
        Scenario.Function f = functions.get(s.args.get(0));
        if (f == null) throw new IllegalArgumentException("tanımsız fonksiyon: " + s.args.get(0));
        if (callDepth >= CALL_DEPTH_MAX) throw new IllegalStateException("çağrı derinliği " + CALL_DEPTH_MAX + " aşıldı (sonsuz özyineleme?)");
        String[] texts = s.args.subList(1, s.args.size()).toArray(new String[0]);
        comm.runtime.Expr[] e = exprs(s, texts);
        String[] values = new String[texts.length];
        for (int i = 0; i < texts.length; i++) values[i] = e[i].eval(exprContext);
        java.util.Map<String, String> saved = new java.util.HashMap<>();
        for (int i = 0; i < f.params.size(); i++) {
            String p = f.params.get(i);
            saved.put(p, vars.get(p));
            vars.put(p, values[i]);
        }
        callDepth++;
        try {
            run(f.body, ctx, out);
        } catch (Return r) {
            // fonksiyondan cikis
        } finally {
            callDepth--;
            for (java.util.Map.Entry<String, String> x : saved.entrySet()) {
                if (x.getValue() == null) vars.remove(x.getKey());
                else vars.put(x.getKey(), x.getValue());
            }
        }
    }

    /** Ifadelerin degisken ve "son alinan" degerlere erisimi. */
    private final comm.runtime.Expr.Context exprContext = new comm.runtime.Expr.Context() {
        @Override public String var(String name) { return vars.get(name); }

        @Override public String last(String endpoint, String message, String path) {
            java.util.Map<String, String> m = ctx.lastReceived(ctx.endpoint(endpoint), message);
            return m == null ? null : m.get(path);
        }
    };

    /** Adimin ifadeleri (bir kez derlenir). */
    private comm.runtime.Expr[] exprs(Step s, String... texts) {
        synchronized (compiled) {
            comm.runtime.Expr[] e = compiled.get(s);
            if (e == null) {
                e = new comm.runtime.Expr[texts.length];
                for (int i = 0; i < texts.length; i++) e[i] = comm.runtime.Expr.parse(texts[i]);
                compiled.put(s, e);
            }
            return e;
        }
    }

    private boolean condition(Step s) {
        comm.runtime.Expr[] e = exprs(s, s.args.get(0), s.args.get(2));
        return comm.runtime.Expr.compare(e[0].eval(exprContext), s.args.get(1), e[1].eval(exprContext));
    }

    /** ${degisken} yerlerine degerlerini koyar. */
    private String substitute(String arg) {
        if (!arg.contains("${")) return arg;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < arg.length()) {
            int a = arg.indexOf("${", i);
            if (a < 0) {
                sb.append(arg, i, arg.length());
                break;
            }
            int b = arg.indexOf('}', a);
            if (b < 0) throw new IllegalArgumentException("kapanmamış ${ : " + arg);
            String name = arg.substring(a + 2, b).trim();
            String v = vars.get(name);
            if (v == null) throw new IllegalArgumentException("tanımsız değişken: " + name);
            sb.append(arg, i, a).append(v);
            i = b + 1;
        }
        return sb.toString();
    }

    private void exec(Step s, ScenarioContext ctx, Output out) throws InterruptedException, IOException {
        switch (s.command) {
            case "var": {
                String text = String.join(" ", s.args.subList(2, s.args.size()));
                String v = exprs(s, text)[0].eval(exprContext);
                vars.put(s.args.get(0), v);
                return;
            }
            case "assert": {
                comm.runtime.Expr[] e = exprs(s, s.args.get(0), s.args.get(2));
                String l = e[0].eval(exprContext);
                String r = e[1].eval(exprContext);
                boolean ok = comm.runtime.Expr.compare(l, s.args.get(1), r);
                checks++;
                if (!ok) failures++;
                out.line((ok ? "✓ " : "✗ ") + s.args.get(0) + " " + s.args.get(1) + " " + s.args.get(2)
                        + (ok ? "" : "  (" + l + " " + s.args.get(1) + " " + r + " değil)"), !ok);
                return;
            }
            case "continue_on_error":
                continueOnError = s.args.get(0).equalsIgnoreCase("on");
                return;
            case "exit":
                out.line("exit" + (s.args.isEmpty() ? "" : ": " + substitute(String.join(" ", s.args))), false);
                throw new Exit();
            case "fail": {
                failures++;
                out.line("✗ fail" + (s.args.isEmpty() ? "" : ": " + substitute(String.join(" ", s.args))), true);
                throw new Exit();
            }
            default:
                break;
        }
        List<String> a = new java.util.ArrayList<>(s.args.size());
        for (String x : s.args) a.add(substitute(x));
        switch (s.command) {
            case "log":
                out.line(String.join(" ", a), false);
                return;
            case "wait":
                sleep(Long.parseLong(a.get(0)));
                return;
            default:
                break;
        }
        Endpoint ep = ctx.endpoint(a.get(0));
        switch (s.command) {
            case "start":
                ep.start();
                waitState(ep);
                out.line(ep.name() + ": " + ep.state(), false);
                break;
            case "stop":
                ep.stop();
                out.line(ep.name() + ": durduruldu", false);
                break;
            case "send": {
                byte[] b = ctx.message(ep, a.get(1), Scenario.assignments(a.subList(2, a.size())));
                ep.send(b, null);
                break;
            }
            case "set":
                ctx.assign(ep, a.get(1), Scenario.assignments(a.subList(2, a.size())));
                break;
            case "hex":
                ep.send(Hex.parse(String.join(" ", a.subList(1, a.size()))), null);
                break;
            case "text":
                ep.send(Hex.parseText(String.join(" ", a.subList(1, a.size()))), null);
                break;
            case "waitfor":
                waitFor(ep, a.get(1), a.size() > 2 ? Long.parseLong(a.get(2)) : DEFAULT_WAITFOR_MS);
                out.line(ep.name() + ": " + a.get(1) + " alındı", false);
                break;
            case "expect":
                expect(ctx, ep, a.get(1), Scenario.assignments(a.subList(2, a.size())), out);
                break;
            case "modbus":
                modbus(ep, a);
                break;
            case "mbset": {
                if (!(ep instanceof ModbusSlaveEndpoint)) throw new IllegalArgumentException(ep.name() + " bir Modbus slave değil");
                Table t;
                switch (a.get(1).toLowerCase(Locale.ROOT)) {
                    case "coil": t = Table.COILS; break;
                    case "di": t = Table.DISCRETE; break;
                    case "hr": t = Table.HOLDING; break;
                    default: t = Table.INPUT;
                }
                int addr = intArg(a.get(2));
                int[] values = intList(a.get(3));
                if (addr < 0 || addr + values.length > 65536) throw new IllegalArgumentException("adres aralık dışında");
                ((ModbusSlaveEndpoint) ep).tables().write(t, addr, values);
                break;
            }
            case "snmp":
                snmp(ep, a);
                break;
            case "trap": {
                if (!(ep instanceof SnmpAgentEndpoint)) throw new IllegalArgumentException(ep.name() + " bir SNMP agent değil");
                SnmpAgentEndpoint ag = (SnmpAgentEndpoint) ep;
                List<VariableBinding> vbs = new ArrayList<>();
                for (String o : a.subList(2, a.size())) {
                    OID oid = SnmpValues.oid(o);
                    Variable v = ag.table().get(oid);
                    if (v == null) throw new IllegalArgumentException("OID tabloda yok: " + o);
                    vbs.add(new VariableBinding(oid, v));
                }
                ag.sendTrap(SnmpValues.oid(a.get(1)), vbs);
                break;
            }
            default:
                throw new IllegalArgumentException("bilinmeyen komut");
        }
    }

    private void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        long left;
        while ((left = end - System.currentTimeMillis()) > 0) {
            if (stopRequested) throw new InterruptedException();
            Thread.sleep(Math.min(left, 50));
        }
    }

    /** Baglanti calisir duruma gelene dek (en fazla 5 s) bekler. */
    private void waitState(Endpoint ep) throws InterruptedException, IOException {
        long end = System.currentTimeMillis() + 5000;
        while (ep.state() == Endpoint.State.STARTING && System.currentTimeMillis() < end) sleep(20);
        if (ep.state() != Endpoint.State.RUNNING) {
            throw new IOException(ep.name() + " başlatılamadı (" + ep.state() + (ep.stats().lastError().isEmpty() ? "" : ": " + ep.stats().lastError()) + ")");
        }
    }

    private LogEntry waitFor(Endpoint ep, String message, long timeoutMs) throws InterruptedException, IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (inbox) {
            while (true) {
                for (java.util.Iterator<LogEntry> it = inbox.iterator(); it.hasNext(); ) {
                    LogEntry e = it.next();
                    if (e.endpoint == ep && matches(e.decoded.message.name, message)) {
                        it.remove();
                        return e;
                    }
                }
                if (stopRequested) throw new InterruptedException();
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException(message + " " + timeoutMs + " ms içinde gelmedi");
                inbox.wait(Math.min(left, 50));
            }
        }
    }

    /** Sema mesajlarinda tam ad; Modbus/SNMP gibi uretilen adlarda baslangic eslesmesi. */
    static boolean matches(String actual, String wanted) {
        return actual.equalsIgnoreCase(wanted) || actual.toLowerCase(Locale.ROOT).startsWith(wanted.toLowerCase(Locale.ROOT));
    }

    private boolean expect(ScenarioContext ctx, Endpoint ep, String message, Map<String, String> want, Output out) {
        Map<String, String> got = ctx.lastReceived(ep, message);
        boolean all = true;
        for (Map.Entry<String, String> w : want.entrySet()) {
            checks++;
            String actual = got == null ? null : got.get(w.getKey());
            boolean ok = actual != null && sameValue(actual, w.getValue());
            if (!ok) failures++;
            all &= ok;
            out.line((ok ? "✓ " : "✗ ") + ep.name() + " " + message + "." + w.getKey() + " = " + w.getValue()
                    + (ok ? "" : got == null ? " (mesaj hiç alınmadı)" : "  (alınan: " + actual + ")"), !ok);
        }
        return all;
    }

    private static boolean sameValue(String a, String b) {
        if (a.equals(b)) return true;
        try {
            return Double.parseDouble(a.replace(',', '.')) == Double.parseDouble(b.replace(',', '.'));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void modbus(Endpoint ep, List<String> a) throws IOException {
        if (!(ep instanceof ModbusMasterEndpoint)) throw new IllegalArgumentException(ep.name() + " bir Modbus master değil");
        ModbusMasterEndpoint m = (ModbusMasterEndpoint) ep;
        switch (a.get(1).toLowerCase(Locale.ROOT)) {
            case "read":
                m.request(Modbus.readRequest(intArg(a.get(2)), intArg(a.get(3)), intArg(a.get(4))));
                break;
            case "write": {
                int[] v = intList(a.get(3));
                m.request(v.length == 1 ? Modbus.writeRegister(intArg(a.get(2)), v[0]) : Modbus.writeRegisters(intArg(a.get(2)), v));
                break;
            }
            default:
                m.request(Modbus.writeCoil(intArg(a.get(2)), intArg(a.get(3)) != 0));
        }
    }

    private static void snmp(Endpoint ep, List<String> a) {
        if (!(ep instanceof SnmpManagerEndpoint)) throw new IllegalArgumentException(ep.name() + " bir SNMP manager değil");
        SnmpManagerEndpoint m = (SnmpManagerEndpoint) ep;
        String op = a.get(1).toLowerCase(Locale.ROOT);
        if (op.equals("set")) {
            Variable v = SnmpValues.parse(a.get(3).toUpperCase(Locale.ROOT), String.join(" ", a.subList(4, a.size())));
            m.set(Collections.singletonList(new VariableBinding(SnmpValues.oid(a.get(2)), v)));
            return;
        }
        List<OID> oids = new ArrayList<>();
        for (String s : a.subList(2, a.size())) oids.add(SnmpValues.oid(s));
        if (op.equals("get")) m.get(oids);
        else if (op.equals("getnext")) m.getNext(oids);
        else m.walk(oids.get(0));
    }

    // =====================================================================
    //  Groovy betikleri icin API
    // =====================================================================

    /**
     * Groovy betiklerinin cagirdigi islemler. Senaryo dilindeki komutlarin karsiligidir;
     * kontroller (check/expect) senaryo sonunda ayni sekilde sayilir.
     */
    public final class Api {
        private final ScenarioContext ctx;
        private final Output out;

        Api(ScenarioContext ctx, Output out) {
            this.ctx = ctx;
            this.out = out;
        }

        private Endpoint ep(String name) { return ctx.endpoint(name); }

        private void alive() throws InterruptedException {
            if (stopRequested) throw new InterruptedException();
        }

        public void log(Object message) { out.line(String.valueOf(message), false); }

        public void start(String endpoint) throws InterruptedException, IOException {
            alive();
            Endpoint e = ep(endpoint);
            e.start();
            waitState(e);
            out.line(e.name() + ": " + e.state(), false);
        }

        public void stop(String endpoint) {
            Endpoint e = ep(endpoint);
            e.stop();
            out.line(e.name() + ": durduruldu", false);
        }

        public void sleep(long ms) throws InterruptedException { ScenarioRunner.this.sleep(ms); }

        public void send(String endpoint, String message, Map<?, ?> values) throws IOException, InterruptedException {
            alive();
            Endpoint e = ep(endpoint);
            e.send(ctx.message(e, message, texts(values)), null);
        }

        public void set(String endpoint, String message, Map<?, ?> values) {
            ctx.assign(ep(endpoint), message, texts(values));
        }

        public void sendHex(String endpoint, String hex) throws IOException { ep(endpoint).send(Hex.parse(hex), null); }

        public void sendText(String endpoint, String text) throws IOException { ep(endpoint).send(Hex.parseText(text), null); }

        public void sendBytes(String endpoint, byte[] data) throws IOException { ep(endpoint).send(data, null); }

        /** Mesaj gelene dek bekler; gelen mesajin alanlarini (yol -&gt; metin) dondurur. */
        public Map<String, String> waitFor(String endpoint, String message, long timeoutMs) throws InterruptedException, IOException {
            Endpoint e = ep(endpoint);
            LogEntry got = ScenarioRunner.this.waitFor(e, message, timeoutMs);
            out.line(e.name() + ": " + message + " alındı", false);
            return values(e, got.decoded);
        }

        /** Cozulmus mesajin alanlari: semali mesajda last() ile ayni bicim, digerlerinde cozum satirlari. */
        private Map<String, String> values(Endpoint e, comm.schema.Decoded d) {
            comm.schema.Schema sc = e.schema();
            if (sc != null && d.message != null && sc.byName(d.message.name) == d.message) {
                Map<String, String> m = comm.schema.Values.receivedTexts(sc.header, d.header, "başlık.");
                m.putAll(comm.schema.Values.receivedTexts(d.message.body, d.body, ""));
                return m;
            }
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (comm.schema.Decoded.Span sp : d.spans) m.put(sp.path, sp.text);
            return m;
        }

        /** Mesajin son alinan degerleri; hic alinmadiysa null. */
        public Map<String, String> last(String endpoint, String message) { return ctx.lastReceived(ep(endpoint), message); }

        /** Son alinan degerleri karsilastirir (sayilar sayisal olarak); her alan bir kontrol sayilir. */
        public boolean expect(String endpoint, String message, Map<?, ?> want) {
            return ScenarioRunner.this.expect(ctx, ep(endpoint), message, texts(want), out);
        }

        /** Genel kontrol: sonuc sayilir ve loga yazilir. */
        public boolean check(boolean ok, String description) {
            checks++;
            if (!ok) failures++;
            out.line((ok ? "✓ " : "✗ ") + description, !ok);
            return ok;
        }

        public void fail(String message) {
            failures++;
            out.line("✗ fail" + (message == null || message.isEmpty() ? "" : ": " + message), true);
            throw new Exit();
        }

        public void exit(String message) {
            out.line("exit" + (message == null || message.isEmpty() ? "" : ": " + message), false);
            throw new Exit();
        }

        public void modbusRead(String endpoint, int function, int address, int count) throws IOException {
            modbus(ep(endpoint), java.util.Arrays.asList(endpoint, "read", "" + function, "" + address, "" + count));
        }

        public void modbusWrite(String endpoint, int address, List<?> values) throws IOException {
            modbus(ep(endpoint), java.util.Arrays.asList(endpoint, "write", "" + address, join(values)));
        }

        public void modbusCoil(String endpoint, int address, boolean on) throws IOException {
            modbus(ep(endpoint), java.util.Arrays.asList(endpoint, "coil", "" + address, on ? "1" : "0"));
        }

        /** Slave tablosuna yazar; table: coil | di | hr | ir. */
        public void mbset(String endpoint, String table, int address, List<?> values) throws IOException, InterruptedException {
            exec(new Step(0, "mbset", java.util.Arrays.asList(endpoint, table, "" + address, join(values))), ctx, out);
        }

        public void snmpGet(String endpoint, List<String> oids) {
            List<String> a = new ArrayList<>(java.util.Arrays.asList(endpoint, "get"));
            a.addAll(oids);
            snmp(ep(endpoint), a);
        }

        public void snmpWalk(String endpoint, String oid) { snmp(ep(endpoint), java.util.Arrays.asList(endpoint, "walk", oid)); }

        public void snmpSet(String endpoint, String oid, String type, Object value) {
            snmp(ep(endpoint), java.util.Arrays.asList(endpoint, "set", oid, type, String.valueOf(value)));
        }

        public void trap(String endpoint, String trapOid, List<String> oids) throws IOException, InterruptedException {
            List<String> a = new ArrayList<>(java.util.Arrays.asList(endpoint, trapOid));
            a.addAll(oids);
            exec(new Step(0, "trap", a), ctx, out);
        }

        /** Senaryo dilinde yazilmis satirlari calistirir (degiskenler ortak). */
        public void dsl(String text) throws ScenarioException, InterruptedException {
            Scenario.Program p = Scenario.parseProgram(text, null);
            java.util.Map<String, Scenario.Function> old = functions;
            java.util.Map<String, Scenario.Function> merged = new java.util.HashMap<>(old);
            merged.putAll(p.functions);
            functions = merged;
            try {
                run(p.steps, ctx, out);
            } finally {
                functions = old;
            }
        }

        /** Senaryo dili ile ortak degiskenler (${ad}). */
        public Map<String, String> getVars() { return vars; }

        /** Alt duzey erisim: baglanti nesnesi. */
        public Endpoint endpoint(String name) { return ep(name); }

        public int getChecks() { return checks; }

        public int getFailures() { return failures; }
    }

    private static Map<String, String> texts(Map<?, ?> values) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        // Groovy anahtarlari GString olabilir ("tracks[$i].identity"); metne cevrilir
        if (values != null) for (Map.Entry<?, ?> e : values.entrySet()) m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return m;
    }

    private static String join(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    static int intArg(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        try {
            return t.startsWith("0x") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sayı bekleniyor: " + s);
        }
    }

    static int[] intList(String s) {
        String[] p = s.split(",");
        int[] v = new int[p.length];
        for (int i = 0; i < p.length; i++) v[i] = intArg(p[i]);
        return v;
    }
}
