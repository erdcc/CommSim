package comm.runtime;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.LiveGenerator;
import comm.core.TrafficBus;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.snmp.SnmpAgentEndpoint;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.OID;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTest {
    private static String eval(String s) { return Expr.parse(s).eval(Expr.EMPTY); }

    @Test void expressionsEvaluate() {
        assertEquals("GOTO", eval("GOTO"));
        assertEquals("01 02 0A", eval("01 02 0A"));
        assertEquals("a b", eval("\"a b\""));
        assertEquals("-5", eval("-5"));
        assertEquals("39.9334", eval("39.9334"));
        assertEquals("7", eval("1 + 2 * 3"));
        assertEquals("2.5", eval("5 / 2"));
        assertEquals("0xA5", eval("0xA5"));
        assertEquals("166", eval("0xA5 + 1"));
        assertEquals("true", eval("true"));

        Expr c = Expr.parse("counter(10, 5, 20)");
        assertEquals(Arrays.asList("10", "15", "20", "10"), Arrays.asList(c.eval(null), c.eval(null), c.eval(null), c.eval(null)));
        Expr l = Expr.parse("list(A|B|C)");
        assertEquals("A", l.eval(null));
        assertEquals("B", l.eval(null));
        Expr t = Expr.parse("toggle()");
        assertEquals("false", t.eval(null));
        assertEquals("true", t.eval(null));
        for (int i = 0; i < 50; i++) {
            long r = Long.parseLong(eval("random(3, 5)"));
            assertTrue(r >= 3 && r <= 5, String.valueOf(r));
        }
        Expr.Context at = new Expr.Context() {
            @Override public long now() { return 2500; }

            @Override public String rx(String path) { return path.equals("başlık.seq") ? "41" : null; }

            @Override public String var(String name) { return name.equals("x") ? "3" : null; }
        };
        assertEquals("50", Expr.parse("ramp(0, 100, 5000)").eval(at));
        assertEquals("100", Expr.parse("sine(0, 100, 10000)").eval(at));
        assertEquals("42", Expr.parse("$rx.başlık.seq + 1").eval(at));
        assertEquals("6", Expr.parse("${x} * 2").eval(at));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("$rx.yok").eval(at));
        assertThrows(IllegalArgumentException.class, () -> Expr.parse("cuonter(1)"));
        assertThrows(IllegalArgumentException.class, () -> eval("1 / 0"));
    }

    @Test void presetsSnapshotAndApplyValues() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        ParameterStore st = new ParameterStore();
        st.setSchema(s);
        MessageDef komut = s.byName("KOMUT");
        st.state(komut).txBody.put("command", "RTB");
        st.state(komut).txBody.put("lat", 41.0);
        comm.core.MessagePreset p = st.snapshot(komut, "Eve dön");
        assertEquals("KOMUT", p.message);
        assertEquals("RTB", p.values.get("command"));
        st.resetDefaults(komut);
        assertEquals("HOLD", st.state(komut).txBody.get("command"));
        long v = st.version();
        assertTrue(st.apply(p).isEmpty());
        assertTrue(st.version() > v);
        assertEquals("RTB", st.state(komut).txBody.get("command"));
        assertEquals(41.0, st.state(komut).txBody.get("lat"));
        // semada olmayan alan uyari olarak doner, digerleri yine uygulanir
        p.values.put("yok", "1");
        List<String> problems = st.apply(p);
        assertEquals(1, problems.size());
        assertEquals("RTB", st.state(komut).txBody.get("command"));
        comm.core.MessagePreset missing = new comm.core.MessagePreset();
        missing.message = "BOYLE_MESAJ_YOK";
        assertThrows(IllegalArgumentException.class, () -> st.apply(missing));
    }

    @Test void messageGeneratorsAdvanceOnlyOnSend() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        ParameterStore st = new ParameterStore();
        st.setSchema(s);
        MessageDef hb = s.byName("HEARTBEAT");
        st.setGenerator(hb, "uptime", "counter(100, 10)");
        st.setGenerator(null, "başlık.seq", "counter(1, 1)");
        st.preview(hb);
        st.preview(hb);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Decoded d = Codec.decode(s, st.encode(hb));
            seen.add(d.header.get("seq") + "/" + d.body.get("uptime"));
        }
        assertEquals(Arrays.asList("1/100", "2/110", "3/120"), seen);
        assertEquals(120L, st.state(hb).txBody.get("uptime")); // tabloda da gorunur
        st.setGenerator(hb, "uptime", "");
        assertEquals(0, st.generatorCount(hb));
        st.setGenerator(hb, "status", "list(OK|FAIL)");
        assertEquals("OK", Codec.decode(s, st.encode(hb)).body.get("status"));
        st.setGenerator(hb, "status", "list(OK|YOK)");
        st.encode(hb); // OK
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> st.encode(hb));
        assertTrue(e.getMessage().contains("status"), e.getMessage());
        assertEquals(1, st.generatorTexts().get("").size());
    }

    @Test void liveGeneratorsWriteSlaveAndAgentTables() throws Exception {
        TrafficBus bus = new TrafficBus();
        EndpointConfig sc = new EndpointConfig();
        sc.kind = EndpointConfig.Kind.MODBUS_SLAVE;
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        ModbusSlaveEndpoint slave = new ModbusSlaveEndpoint(sc, bus);
        LiveGenerator g = new LiveGenerator();
        g.target = "hr:7";
        g.expr = "counter(1, 1)";
        g.intervalMs = 20;
        sc.liveGenerators.add(g);
        LiveGenerator bad = new LiveGenerator();
        bad.target = "xx:1";
        bad.expr = "5";
        sc.liveGenerators.add(bad);

        EndpointConfig ac = new EndpointConfig();
        ac.kind = EndpointConfig.Kind.SNMP_AGENT;
        ac.localAddress = "127.0.0.1";
        ac.localPort = 0;
        SnmpAgentEndpoint agent = new SnmpAgentEndpoint(ac, bus);
        LiveGenerator sg = new LiveGenerator();
        sg.target = "1.3.6.1.2.1.1.5.0";
        sg.expr = "list(a|b)";
        sg.intervalMs = 20;
        ac.liveGenerators.add(sg);

        List<Endpoint> eps = Arrays.asList(slave, agent);
        LiveGenerators live = new LiveGenerators(() -> eps);
        try {
            slave.start();
            agent.start();
            long end = System.currentTimeMillis() + 2000;
            while (slave.tables().get(ModbusTables.Table.HOLDING, 7) < 3 && System.currentTimeMillis() < end) Thread.sleep(10);
            assertTrue(slave.tables().get(ModbusTables.Table.HOLDING, 7) >= 3);
            assertTrue(!bad.enabled, "hatalı hedef kapatılmalı");
            String v = agent.table().get(new OID("1.3.6.1.2.1.1.5.0")).toString();
            assertTrue(v.equals("a") || v.equals("b"), v);
        } finally {
            live.shutdown();
            slave.stop();
            agent.stop();
        }
        Map<String, String> unused = new HashMap<>(Collections.<String, String>emptyMap());
        assertTrue(unused.isEmpty());
    }

    // ---- otomatik yanit kurallari ----

    private static <T> T await(java.util.function.Supplier<T> f, java.util.function.Predicate<T> ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        T v = f.get();
        while (!ok.test(v) && System.currentTimeMillis() < end) {
            Thread.sleep(10);
            v = f.get();
        }
        assertTrue(ok.test(v), "zaman aşımı: " + what + " (" + v + ")");
        return v;
    }

    @Test void rulesAnswerMatchingMessages() throws Exception {
        TrafficBus bus = new TrafficBus();
        List<comm.core.LogEntry> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(comm.core.LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
        RuleEngine engine = new RuleEngine(bus);
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        comm.core.FramingConfig lp = new comm.core.FramingConfig();
        lp.type = comm.core.FramingConfig.Type.LENGTH_PREFIX;

        EndpointConfig sc = new EndpointConfig();
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        sc.framing = lp;
        comm.core.ResponseRule rule = new comm.core.ResponseRule();
        rule.when = "KOMUT";
        rule.condition = "command=GOTO; lat>40";
        rule.delayMs = 50;
        rule.reply = "HEARTBEAT";
        rule.assignments = "başlık.seq=$rx.başlık.seq + 1; uptime=counter(5,5); status=FAIL";
        sc.rules.add(rule);
        comm.transport.TcpServerEndpoint server = new comm.transport.TcpServerEndpoint(sc, bus);
        server.setSchema(s);
        EndpointConfig cc = new EndpointConfig();
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.framing = lp.copy();
        try {
            server.start();
            cc.remotePort = server.boundPort();
            comm.transport.TcpClientEndpoint client = new comm.transport.TcpClientEndpoint(cc, bus);
            client.setSchema(s);
            client.start();
            await(client::state, x -> x == Endpoint.State.RUNNING, "bağlantı");

            ParameterStore st = new ParameterStore();
            st.setSchema(s);
            MessageDef k = s.byName("KOMUT");
            Values(st, k, "command", "GOTO");
            Values(st, k, "lat", "41.5");
            st.setGenerator(null, "başlık.seq", "counter(10,1)");
            long t0 = System.currentTimeMillis();
            client.send(st.encode(k), null);
            client.send(st.encode(k), null);
            Values(st, k, "command", "HOLD");
            client.send(st.encode(k), null); // kosul tutmaz -> yanit yok

            List<Decoded> replies = await(() -> replies(log, client), l -> l.size() >= 2, "yanıtlar");
            assertTrue(System.currentTimeMillis() - t0 >= 50, "gecikme uygulanmalı");
            assertEquals("HEARTBEAT", replies.get(0).message.name);
            assertEquals(11L, replies.get(0).header.get("seq"));
            assertEquals(12L, replies.get(1).header.get("seq"));
            assertEquals(5L, replies.get(0).body.get("uptime"));
            assertEquals(10L, replies.get(1).body.get("uptime"));
            assertEquals("FAIL", replies.get(0).body.get("status"));
            Thread.sleep(200);
            assertEquals(2, replies(log, client).size());
            assertEquals(2, engine.count(rule));
            client.stop();
        } finally {
            server.stop();
        }

        // semasiz UDP: hex esle, metinle yanit ver
        EndpointConfig uc = new EndpointConfig();
        uc.kind = EndpointConfig.Kind.UDP;
        uc.localAddress = "127.0.0.1";
        uc.localPort = 0;
        comm.core.ResponseRule ping = new comm.core.ResponseRule();
        ping.when = "hex:50 49 4E 47";
        ping.reply = "text:PONG";
        uc.rules.add(ping);
        comm.transport.UdpEndpoint a = new comm.transport.UdpEndpoint(uc, bus);
        EndpointConfig bc = new EndpointConfig();
        bc.kind = EndpointConfig.Kind.UDP;
        bc.localAddress = "127.0.0.1";
        bc.localPort = 0;
        comm.transport.UdpEndpoint b = new comm.transport.UdpEndpoint(bc, bus);
        try {
            a.start();
            b.start();
            b.config().remotePort = a.boundPort();
            b.send(comm.core.Hex.parseText("xxPINGxx"), null);
            await(() -> log.stream().anyMatch(e -> e.endpoint == b && e.dir == comm.core.Direction.RX
                    && new String(e.data, java.nio.charset.StandardCharsets.UTF_8).equals("PONG")), x -> x, "PONG");
        } finally {
            a.stop();
            b.stop();
            engine.shutdown();
        }
    }

    private static void Values(ParameterStore st, MessageDef m, String path, String text) {
        List<String> p = comm.schema.Values.applyTexts(m.body, st.state(m).txBody, "", Collections.singletonMap(path, text));
        assertTrue(p.isEmpty(), p.toString());
    }

    private static List<Decoded> replies(List<comm.core.LogEntry> log, Endpoint client) {
        List<Decoded> out = new ArrayList<>();
        for (comm.core.LogEntry e : log) {
            if (e.endpoint == client && e.dir == comm.core.Direction.RX && e.decoded != null && e.decoded.ok()) out.add(e.decoded);
        }
        return out;
    }

    // ---- kayit ve tekrar oynatma ----

    @Test void recordAndReplayKeepsBytesAndTiming(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        TrafficBus bus = new TrafficBus();
        List<comm.core.LogEntry> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(comm.core.LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
        EndpointConfig ac = new EndpointConfig();
        ac.kind = EndpointConfig.Kind.UDP;
        ac.name = "alıcı";
        ac.localAddress = "127.0.0.1";
        ac.localPort = 0;
        comm.transport.UdpEndpoint rx = new comm.transport.UdpEndpoint(ac, bus);
        EndpointConfig bc = ac.copy();
        bc.name = "verici";
        comm.transport.UdpEndpoint tx = new comm.transport.UdpEndpoint(bc, bus);
        try {
            rx.start();
            tx.start();
            tx.config().remotePort = rx.boundPort();
            for (int i = 0; i < 3; i++) {
                tx.send(new byte[]{(byte) i, 0x55}, null);
                Thread.sleep(200);
            }
            Recording rec = Recording.of(new ArrayList<>(log));
            java.nio.file.Path f = dir.resolve("t.rec");
            rec.save(f);
            Recording back = Recording.load(f);
            assertEquals(rec.entries.size(), back.entries.size());
            assertTrue(back.groups().containsKey("verici|TX"), back.groups().toString());
            assertTrue(back.duration() >= 350, "süre " + back.duration());

            log.clear();
            Map<String, Endpoint> map = new java.util.LinkedHashMap<>();
            map.put("verici|TX", tx);
            Replayer r = new Replayer();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            String[] summary = new String[1];
            long t0 = System.currentTimeMillis();
            r.start(back, map, 4.0, false, false, new Replayer.Listener() {
                @Override public void progress(int sent, int total, long elapsedMs, int errors) { }

                @Override public void finished(String s) {
                    summary[0] = s;
                    done.countDown();
                }
            });
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long took = System.currentTimeMillis() - t0;
            assertTrue(took >= 80 && took < 400, "4× hızda ~100 ms beklenir: " + took);
            assertTrue(summary[0].contains("3 çerçeve"), summary[0]);
            await(() -> log.stream().filter(e -> e.endpoint == rx && e.dir == comm.core.Direction.RX).count(), n -> n == 3, "alım");
            List<String> got = new ArrayList<>();
            for (comm.core.LogEntry e : log) if (e.endpoint == rx && e.dir == comm.core.Direction.RX) got.add(comm.core.Hex.toHex(e.data));
            assertEquals(Arrays.asList("00 55", "01 55", "02 55"), got);

            assertThrows(java.io.IOException.class, () -> {
                java.nio.file.Path bad = dir.resolve("bad.rec");
                java.nio.file.Files.write(bad, "{\"x\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Recording.load(bad);
            });
        } finally {
            rx.stop();
            tx.stop();
        }
    }
}
