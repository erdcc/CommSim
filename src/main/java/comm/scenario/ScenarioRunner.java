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

    /** exit komutu: senaryoyu basariyla bitirir. */
    private static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exit() { super(null, null, false, false); }
    }

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
        if (thread != null) throw new ScenarioException("senaryo zaten çalışıyor");
        List<Step> steps = Scenario.parse(text);
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
                    run(steps, ctx, out);
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
                for (int i = 0; i < s.count; i++) run(s.body, ctx, out);
                continue;
            }
            try {
                if (s.command.equals("if")) {
                    run(condition(s) ? s.body : s.elseBody, ctx, out);
                    continue;
                }
                exec(s, ctx, out);
            } catch (Exit x) {
                throw x;
            } catch (RuntimeException | IOException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (continueOnError) {
                    failures++;
                    out.line("HATA satır " + s.line + " (" + s.command + "): " + msg + " — devam ediliyor", true);
                    continue;
                }
                throw new ScenarioException("satır " + s.line + " (" + s.command + "): " + msg);
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

    private void waitFor(Endpoint ep, String message, long timeoutMs) throws InterruptedException, IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (inbox) {
            while (true) {
                for (java.util.Iterator<LogEntry> it = inbox.iterator(); it.hasNext(); ) {
                    LogEntry e = it.next();
                    if (e.endpoint == ep && matches(e.decoded.message.name, message)) {
                        it.remove();
                        return;
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

    private void expect(ScenarioContext ctx, Endpoint ep, String message, Map<String, String> want, Output out) {
        Map<String, String> got = ctx.lastReceived(ep, message);
        for (Map.Entry<String, String> w : want.entrySet()) {
            checks++;
            String actual = got == null ? null : got.get(w.getKey());
            boolean ok = actual != null && sameValue(actual, w.getValue());
            if (!ok) failures++;
            out.line((ok ? "✓ " : "✗ ") + ep.name() + " " + message + "." + w.getKey() + " = " + w.getValue()
                    + (ok ? "" : got == null ? " (mesaj hiç alınmadı)" : "  (alınan: " + actual + ")"), !ok);
        }
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
