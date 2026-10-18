package comm.scenario;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.FramingConfig;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import comm.modbus.ModbusMasterEndpoint;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.schema.Values;
import comm.transport.TcpClientEndpoint;
import comm.transport.TcpServerEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioTest {
    @Test void tokenizerHandlesQuotesAndComments() {
        assertEquals(java.util.Arrays.asList("send", "Örnek TCP istemci", "KOMUT", "text=a \"b\" c"),
                Scenario.tokenize("send \"Örnek TCP istemci\" KOMUT text=\"a \\\"b\\\" c\"  # yorum"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.tokenize("log \"açık"));
    }

    @Test void parserBuildsNestedRepeatsAndReportsLines() throws Exception {
        List<Scenario.Step> s = Scenario.parse("log a\nrepeat 2\n  repeat 3\n    wait 1\n  end\nend\n");
        assertEquals(2, s.size());
        assertEquals(2, s.get(1).count);
        assertEquals(3, s.get(1).body.get(0).count);

        ScenarioException e = assertThrows(ScenarioException.class, () -> Scenario.parse("log a\nfoo x\n"));
        assertTrue(e.getMessage().startsWith("satır 2"), e.getMessage());
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("repeat 2\nlog a\n"));
        assertTrue(e.getMessage().contains("end"), e.getMessage());
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("set x MSG yanlis\n"));
        assertTrue(e.getMessage().contains("alan=değer"), e.getMessage());
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("modbus m oku 3 0 1\n"));
        assertTrue(e.getMessage().startsWith("satır 1"), e.getMessage());
    }

    // ---- uctan uca: basit bir baglam (uygulamadaki ana pencerenin yaptigini taklit eder) ----

    private final TrafficBus bus = new TrafficBus();
    private final List<Endpoint> open = new ArrayList<>();
    private final Map<String, Endpoint> byName = new HashMap<>();
    private final Map<Endpoint, Map<String, Map<String, Object>>> tx = new ConcurrentHashMap<>();
    private final Map<Endpoint, Map<String, Decoded>> lastRx = new ConcurrentHashMap<>();

    private final class TestContext implements ScenarioContext {
        @Override public Endpoint endpoint(String name) {
            Endpoint e = byName.get(name);
            if (e == null) throw new IllegalArgumentException("bağlantı bulunamadı: " + name);
            return e;
        }

        private MessageDef def(Endpoint ep, String msg) {
            MessageDef m = ep.schema().byName(msg);
            if (m == null) throw new IllegalArgumentException("şemada mesaj yok: " + msg);
            return m;
        }

        private Map<String, Object> values(Endpoint ep, MessageDef m) {
            return tx.computeIfAbsent(ep, k -> new ConcurrentHashMap<>()).computeIfAbsent(m.name, k -> new LinkedHashMap<>());
        }

        @Override public void assign(Endpoint ep, String msg, Map<String, String> a) {
            MessageDef m = def(ep, msg);
            List<String> p = Values.applyTexts(m.body, values(ep, m), "", a);
            if (!p.isEmpty()) throw new IllegalArgumentException(String.join("; ", p));
        }

        @Override public byte[] message(Endpoint ep, String msg, Map<String, String> a) {
            assign(ep, msg, a);
            MessageDef m = def(ep, msg);
            return Codec.encode(ep.schema(), m, null, values(ep, m));
        }

        @Override public Map<String, String> lastReceived(Endpoint ep, String msg) {
            Decoded d = lastRx.getOrDefault(ep, new HashMap<String, Decoded>()).get(msg);
            return d == null ? null : Values.receivedTexts(d.message.body, d.body, "");
        }

        @Override public TrafficBus bus() { return bus; }
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    private <T extends Endpoint> T add(String name, T e) {
        e.config().name = name;
        open.add(e);
        byName.put(name, e);
        return e;
    }

    @Test void runsScenarioAgainstRealEndpoints() throws Exception {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) {
                if (e.dir == Direction.RX && e.decoded != null && e.decoded.ok()) {
                    lastRx.computeIfAbsent(e.endpoint, k -> new ConcurrentHashMap<>()).put(e.decoded.message.name, e.decoded);
                }
            }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
        Schema schema = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        FramingConfig lp = new FramingConfig();
        lp.type = FramingConfig.Type.LENGTH_PREFIX;

        EndpointConfig sc = new EndpointConfig();
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        sc.framing = lp;
        TcpServerEndpoint server = add("sunucu", new TcpServerEndpoint(sc, bus));
        server.setSchema(schema);
        server.start();
        EndpointConfig cc = new EndpointConfig();
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.remotePort = server.boundPort();
        cc.framing = lp.copy();
        add("istemci 1", new TcpClientEndpoint(cc, bus)).setSchema(schema);

        EndpointConfig slc = new EndpointConfig();
        slc.kind = EndpointConfig.Kind.MODBUS_SLAVE;
        slc.localAddress = "127.0.0.1";
        slc.localPort = 0;
        ModbusSlaveEndpoint slave = add("slave", new ModbusSlaveEndpoint(slc, bus));
        slave.start();
        EndpointConfig mc = new EndpointConfig();
        mc.kind = EndpointConfig.Kind.MODBUS_MASTER;
        mc.remotePort = slave.boundPort();
        ModbusMasterEndpoint master = add("master", new ModbusMasterEndpoint(mc, bus));

        String script = String.join("\n",
                "start \"istemci 1\"",
                "start master",
                "repeat 2",
                "  send \"istemci 1\" KONUM speed=120 heading=-45",
                "  waitfor sunucu KONUM 2000",
                "end",
                "expect sunucu KONUM speed=120 heading=-45 lat=39.9334",
                "expect sunucu KONUM speed=99",
                "send \"istemci 1\" HEDEF_LISTESI tracks=2 tracks[1].identity=HOSTILE",
                "waitfor sunucu HEDEF_LISTESI",
                "expect sunucu HEDEF_LISTESI count=2 tracks[1].identity=HOSTILE",
                "mbset slave hr 10 7,8,9",
                "modbus master read 3 10 3",
                "waitfor master FC03",
                "log tamam");
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        ScenarioRunner r = new ScenarioRunner();
        r.start(script, new TestContext(), (line, err) -> out.add((err ? "E " : "  ") + line), done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS), "senaryo bitmedi: " + out);
        String all = String.join("\n", out);
        assertTrue(all.contains("tamam"), all);
        assertTrue(all.contains("6 kontrol, 1 başarısız"), all);
        assertTrue(all.contains("✗ sunucu KONUM.speed = 99  (alınan: 120)"), all);
        assertEquals(9, master.tables().get(ModbusTables.Table.HOLDING, 12));
        assertEquals(3, server.stats().rxFrames(), all); // 2 KONUM + 1 HEDEF_LISTESI
    }

    @Test void failingCommandStopsScenario() throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        new ScenarioRunner().start("log önce\nstart yok\nlog sonra\n", new TestContext(),
                (line, err) -> out.add(line), done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        String all = String.join("\n", out);
        assertTrue(all.contains("satır 2 (start): bağlantı bulunamadı: yok"), all);
        assertTrue(!all.contains("sonra"), all);
    }

    // ---- degiskenler, kosullar, kontroller ----

    private List<String> runScript(String script) throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        ScenarioRunner r = new ScenarioRunner();
        r.start(script, new TestContext(), (line, err) -> out.add((err ? "E " : "  ") + line), done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS), "bitmedi: " + out);
        out.add("#failures=" + r.failures() + " checks=" + r.checks() + " errored=" + r.errored());
        return out;
    }

    @Test void variablesConditionsAndAsserts() throws Exception {
        String all = String.join("\n", runScript(String.join("\n",
                "var toplam = 0",
                "repeat 4",
                "  var i = counter(1, 1)",
                "  var toplam = ${toplam} + ${i}",
                "  if ${i} % 2 == 0",
                "    log çift ${i}",
                "  else",
                "    log tek ${i}",
                "  end",
                "end",
                "assert ${toplam} == 10",
                "assert ${toplam} > 20",
                "var ad = \"a b\"",
                "assert ${ad} == \"a b\"",
                "exit tamam",
                "log buraya gelmemeli")));
        assertTrue(all.contains("tek 1") && all.contains("çift 2") && all.contains("çift 4"), all);
        assertTrue(all.contains("✓ ${toplam} == 10"), all);
        assertTrue(all.contains("✗ ${toplam} > 20  (10 > 20 değil)"), all);
        assertTrue(all.contains("exit ile bitti"), all);
        assertTrue(!all.contains("buraya gelmemeli"), all);
        assertTrue(all.contains("#failures=1 checks=3 errored=false"), all);
    }

    @Test void continueOnErrorAndFail() throws Exception {
        String all = String.join("\n", runScript(String.join("\n",
                "continue_on_error on",
                "start yok",
                "log sonra",
                "continue_on_error off",
                "fail bilerek",
                "log gelmez")));
        assertTrue(all.contains("devam ediliyor") && all.contains("sonra"), all);
        assertTrue(all.contains("✗ fail: bilerek") && !all.contains("gelmez"), all);
        assertTrue(all.contains("#failures=2"), all);

        ScenarioException e = assertThrows(ScenarioException.class, () -> Scenario.parse("if 1 == 1\nlog a\n"));
        assertTrue(e.getMessage().contains("if"), e.getMessage());
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("else\n"));
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("assert 1 2\n"));
        assertTrue(e.getMessage().contains("=="), e.getMessage());
        e = assertThrows(ScenarioException.class, () -> Scenario.parse("var 1x = 5\n"));
    }
}
