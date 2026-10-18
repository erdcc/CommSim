package comm.core;

import comm.schema.Codec;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultTest {
    private final TrafficBus bus = new TrafficBus();
    private final List<LogEntry> log = new CopyOnWriteArrayList<>();
    private final List<Endpoint> open = new ArrayList<>();
    private Schema schema;
    private TcpServerEndpoint server;
    private TcpClientEndpoint client;

    FaultTest() {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    private void connect(FaultConfig f) throws Exception {
        schema = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        FramingConfig lp = new FramingConfig();
        lp.type = FramingConfig.Type.LENGTH_PREFIX;
        EndpointConfig sc = new EndpointConfig();
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        sc.framing = lp;
        server = new TcpServerEndpoint(sc, bus);
        server.setSchema(schema);
        open.add(server);
        server.start();
        EndpointConfig cc = new EndpointConfig();
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.remotePort = server.boundPort();
        cc.framing = lp.copy();
        cc.faults = f;
        client = new TcpClientEndpoint(cc, bus);
        client.setSchema(schema);
        open.add(client);
        client.start();
        long end = System.currentTimeMillis() + 3000;
        while (client.state() != Endpoint.State.RUNNING && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    private byte[] heartbeat() {
        MessageDef m = schema.byName("HEARTBEAT");
        return Codec.encode(schema, m, null, Values.defaults(m.body));
    }

    private List<LogEntry> serverRx() {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : log) if (e.endpoint == server && e.dir == Direction.RX) out.add(e);
        return out;
    }

    private void waitRx(int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (serverRx().size() < n && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    @Test void checksumAndLengthCorruptionIsDetectedByReceiver() throws Exception {
        FaultConfig f = new FaultConfig();
        f.enabled = true;
        f.checksumPct = 100;
        f.lengthPct = 100;
        connect(f);
        client.send(heartbeat(), null);
        waitRx(1);
        LogEntry rx = serverRx().get(0);
        String w = rx.decoded.warnings.toString();
        assertTrue(w.contains("crc") && w.contains("len"), w);
        assertTrue(log.stream().anyMatch(e -> e.endpoint == client && e.dir == Direction.INFO && e.text.contains("checksum bozuldu")));
    }

    @Test void dropDuplicateAndDelay() throws Exception {
        FaultConfig f = new FaultConfig();
        f.enabled = true;
        f.dropPct = 100;
        connect(f);
        client.send(heartbeat(), null);
        Thread.sleep(200);
        assertEquals(0, serverRx().size());

        f.dropPct = 0;
        f.duplicatePct = 100;
        f.delayMs = 150;
        long t0 = System.currentTimeMillis();
        client.send(heartbeat(), null);
        waitRx(2);
        assertEquals(2, serverRx().size());
        assertTrue(serverRx().get(0).time - t0 >= 140, "gecikme: " + (serverRx().get(0).time - t0));
        assertTrue(serverRx().get(0).decoded.warnings.isEmpty());

        f.enabled = false; // kapaliyken dokunulmaz
        client.send(heartbeat(), null);
        waitRx(3);
        assertEquals(3, serverRx().size());
    }
}
