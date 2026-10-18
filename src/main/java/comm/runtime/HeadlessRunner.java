package comm.runtime;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.project.ProjectFile;
import comm.scenario.ScenarioContext;
import comm.scenario.ScenarioException;
import comm.scenario.ScenarioRunner;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaException;
import comm.schema.SchemaLibrary;
import comm.schema.Values;
import comm.snmp.OidTable;
import comm.snmp.SnmpAgentEndpoint;
import comm.transport.Endpoints;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Arayuzsuz calistirici: projeyi yukler, senaryoyu calistirir, sonucu cikis koduyla bildirir.
 * <pre>java -jar commsim.jar --run proje.json [senaryo.scn] [--start-all] [--timeout 120] [--verbose]</pre>
 * Cikis kodu: 0 basarili, 1 kontrol (expect/assert/fail) basarisiz, 2 hata/zaman asimi/kullanim hatasi.
 */
public final class HeadlessRunner implements ScenarioContext {
    public static final int OK = 0, FAILED = 1, ERROR = 2;

    private final TrafficBus bus = new TrafficBus();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private final Map<Endpoint, ParameterStore> stores = new IdentityHashMap<>();
    private final PrintStream out;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");

    private HeadlessRunner(PrintStream out) { this.out = out; }

    /** Komut satiri girisi; cikis kodunu dondurur. */
    public static int main(String[] args, PrintStream out) {
        Path project = null;
        Path scenario = null;
        boolean startAll = false;
        boolean verbose = false;
        int timeout = 600;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--run": project = java.nio.file.Paths.get(args[++i]); break;
                    case "--start-all": startAll = true; break;
                    case "--verbose": verbose = true; break;
                    case "--timeout": timeout = Integer.parseInt(args[++i]); break;
                    default:
                        if (a.startsWith("--")) throw new IllegalArgumentException("bilinmeyen seçenek: " + a);
                        scenario = java.nio.file.Paths.get(a);
                }
            }
        } catch (RuntimeException e) {
            out.println("Kullanım: java -jar commsim.jar --run proje.json [senaryo.scn] [--start-all] [--timeout sn] [--verbose]");
            out.println("Hata: " + e.getMessage());
            return ERROR;
        }
        if (project == null) {
            out.println("Kullanım: java -jar commsim.jar --run proje.json [senaryo.scn] [--start-all] [--timeout sn] [--verbose]");
            return ERROR;
        }
        HeadlessRunner r = new HeadlessRunner(out);
        try {
            return r.run(project, scenario, startAll, verbose, timeout);
        } finally {
            for (Endpoint ep : r.endpoints) ep.stop();
        }
    }

    private void line(String s) {
        synchronized (out) {
            out.println(fmt.format(new Date()) + "  " + s);
        }
    }

    private int run(Path projectFile, Path scenarioFile, boolean startAll, boolean verbose, int timeoutSec) {
        ProjectFile p;
        String script;
        try {
            p = ProjectFile.load(projectFile);
            script = scenarioFile != null ? new String(Files.readAllBytes(scenarioFile), StandardCharsets.UTF_8) : p.scenario;
        } catch (IOException e) {
            line("HATA dosya okunamadı: " + e.getMessage());
            return ERROR;
        }
        if (script == null || script.trim().isEmpty()) {
            line("HATA çalıştırılacak senaryo yok (projede senaryo boş, dosya da verilmedi)");
            return ERROR;
        }
        if (!load(p)) return ERROR;
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) {
                ParameterStore st = stores.get(e.endpoint);
                if (st != null) {
                    synchronized (st) {
                        st.record(e);
                    }
                }
                if (e.dir == Direction.ERROR) line("! " + e.endpoint.name() + ": " + e.text);
                else if (verbose) {
                    line((e.isFrame() ? (e.dir == Direction.RX ? "← " : "→ ") : "• ") + e.endpoint.name() + "  "
                            + (e.isFrame() ? (e.decoded != null ? e.decoded.label() + "  " : "") + Hex.preview(e.data, 24) : e.text));
                }
            }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
        RuleEngine rules = new RuleEngine(bus);
        LiveGenerators live = new LiveGenerators(() -> endpoints);
        try {
            if (startAll) {
                for (Endpoint ep : endpoints) if (ep.config().kind.isServer() || ep.config().kind.isSnmp()) startQuietly(ep);
                Thread.sleep(150);
                for (Endpoint ep : endpoints) if (!ep.config().kind.isServer() && !ep.config().kind.isSnmp()) startQuietly(ep);
                Thread.sleep(300);
            }
            ScenarioRunner runner = new ScenarioRunner();
            CountDownLatch done = new CountDownLatch(1);
            try {
                runner.start(script, this, (text, error) -> line((error ? "" : "") + text), done::countDown);
            } catch (ScenarioException e) {
                line("HATA sözdizimi: " + e.getMessage());
                return ERROR;
            }
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                runner.stop();
                done.await(5, TimeUnit.SECONDS);
                line("HATA zaman aşımı (" + timeoutSec + " s)");
                return ERROR;
            }
            int code = runner.errored() ? ERROR : runner.failures() > 0 ? FAILED : OK;
            line("SONUÇ: " + (code == OK ? "BAŞARILI" : code == FAILED ? "BAŞARISIZ" : "HATA") + "  ("
                    + runner.checks() + " kontrol, " + runner.failures() + " başarısız)");
            return code;
        } catch (InterruptedException e) {
            return ERROR;
        } finally {
            rules.shutdown();
            live.shutdown();
        }
    }

    private void startQuietly(Endpoint ep) {
        try {
            ep.start();
        } catch (IOException | RuntimeException e) {
            line("! " + ep.name() + " başlatılamadı: " + e.getMessage());
        }
    }

    /** Projedeki baglantilari, semalari, degerleri ve tablolari yukler. */
    private boolean load(ProjectFile p) {
        SchemaLibrary lib = new SchemaLibrary();
        for (ProjectFile.EndpointEntry e : p.endpoints) {
            Endpoint ep = Endpoints.create(e.config, bus);
            ParameterStore st = new ParameterStore();
            try {
                Schema s = lib.get(e.config.schemaFile);
                if (s != null) s = s.withOverrides(e.config.schemaOverrides);
                ep.setSchema(s);
                st.setSchema(s);
            } catch (IOException | SchemaException ex) {
                line("HATA " + ep.name() + " şeması: " + ex.getMessage());
                return false;
            }
            if (st.schema() != null) {
                List<String> problems = new ArrayList<>(Values.applyTexts(st.schema().header, st.txHeader(), ParameterStore.HEADER, e.txHeader));
                for (Map.Entry<String, Map<String, String>> m : e.txMessages.entrySet()) {
                    MessageDef def = st.schema().byName(m.getKey());
                    if (def != null) problems.addAll(Values.applyTexts(def.body, st.state(def).txBody, "", m.getValue()));
                }
                for (Map.Entry<String, Map<String, String>> g : e.generators.entrySet()) {
                    MessageDef def = g.getKey().isEmpty() ? null : st.schema().byName(g.getKey());
                    if (!g.getKey().isEmpty() && def == null) continue;
                    for (Map.Entry<String, String> x : g.getValue().entrySet()) {
                        try {
                            st.setGenerator(def, x.getKey(), x.getValue());
                        } catch (IllegalArgumentException ex) {
                            problems.add("üreteç " + x.getKey() + ": " + ex.getMessage());
                        }
                    }
                }
                for (String pr : problems) line("! " + ep.name() + ": " + pr);
            }
            if (ep instanceof ModbusSlaveEndpoint && e.modbusTables != null) {
                for (Map.Entry<String, Map<Integer, Integer>> t : e.modbusTables.entrySet()) {
                    ModbusTables.Table table = ModbusTables.Table.valueOf(t.getKey());
                    for (Map.Entry<Integer, Integer> v : t.getValue().entrySet()) {
                        ((ModbusSlaveEndpoint) ep).tables().set(table, v.getKey(), v.getValue());
                    }
                }
            }
            if (ep instanceof SnmpAgentEndpoint) {
                try {
                    ((SnmpAgentEndpoint) ep).loadOidFile();
                    if (e.oidTable != null) {
                        ((SnmpAgentEndpoint) ep).table().copyFrom(OidTable.parse(String.join("\n", e.oidTable), ep.name()));
                    }
                } catch (IOException ex) {
                    line("HATA " + ep.name() + " OID tablosu: " + ex.getMessage());
                    return false;
                }
            }
            endpoints.add(ep);
            stores.put(ep, st);
        }
        line(endpoints.size() + " bağlantı yüklendi");
        return true;
    }

    // ---- ScenarioContext ----

    @Override public Endpoint endpoint(String name) {
        for (Endpoint ep : endpoints) if (ep.name().equals(name)) return ep;
        for (Endpoint ep : endpoints) if (ep.name().equalsIgnoreCase(name)) return ep;
        throw new IllegalArgumentException("bağlantı bulunamadı: " + name);
    }

    private ParameterStore store(Endpoint ep) {
        ParameterStore st = stores.get(ep);
        if (st == null || st.schema() == null) throw new IllegalArgumentException(ep.name() + " bağlantısına şema atanmamış");
        return st;
    }

    private static MessageDef def(ParameterStore st, String message) {
        MessageDef m = st.schema().byName(message);
        if (m == null) throw new IllegalArgumentException("şemada mesaj yok: " + message);
        return m;
    }

    @Override public void assign(Endpoint ep, String message, Map<String, String> assignments) {
        ParameterStore st = store(ep);
        synchronized (st) {
            MessageDef m = def(st, message);
            Map<String, String> header = new LinkedHashMap<>();
            Map<String, String> body = new LinkedHashMap<>();
            for (Map.Entry<String, String> a : assignments.entrySet()) {
                String k = a.getKey().startsWith("baslik.") ? ParameterStore.HEADER + a.getKey().substring(7) : a.getKey();
                (k.startsWith(ParameterStore.HEADER) ? header : body).put(k, a.getValue());
            }
            List<String> problems = new ArrayList<>(Values.applyTexts(st.schema().header, st.txHeader(), ParameterStore.HEADER, header));
            problems.addAll(Values.applyTexts(m.body, st.state(m).txBody, "", body));
            if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        }
    }

    @Override public byte[] message(Endpoint ep, String message, Map<String, String> assignments) {
        assign(ep, message, assignments);
        ParameterStore st = store(ep);
        synchronized (st) {
            return st.encode(def(st, message));
        }
    }

    @Override public Map<String, String> lastReceived(Endpoint ep, String message) {
        ParameterStore st = store(ep);
        synchronized (st) {
            MessageDef m = def(st, message);
            ParameterStore.MsgState ms = st.state(m);
            if (ms.lastRx == null) return null;
            Map<String, String> out = Values.receivedTexts(st.schema().header, ms.lastRx.header, ParameterStore.HEADER);
            out.putAll(Values.receivedTexts(m.body, ms.lastRx.body, ""));
            return out;
        }
    }

    @Override public TrafficBus bus() { return bus; }
}
