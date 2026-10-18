package comm.transport;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.FramingConfig;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import comm.schema.Codec;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.schema.Values;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gercek soketlerle 127.0.0.1 uzerinden uctan uca denemeler. */
class LoopbackTest {
    private final TrafficBus bus = new TrafficBus();
    private final BlockingQueue<LogEntry> rx = new LinkedBlockingQueue<>();
    private final List<Endpoint> open = new ArrayList<>();

    LoopbackTest() {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) {
                if (e.dir == Direction.RX) rx.add(e);
            }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    private <T extends Endpoint> T track(T e) {
        open.add(e);
        return e;
    }

    private LogEntry nextRx() throws InterruptedException {
        LogEntry e = rx.poll(3, TimeUnit.SECONDS);
        assertNotNull(e, "zaman aşımı: çerçeve gelmedi");
        return e;
    }

    private static void await(Endpoint e, Endpoint.State s) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (e.state() != s && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals(s, e.state());
    }

    private static FramingConfig lengthPrefix() {
        FramingConfig f = new FramingConfig();
        f.type = FramingConfig.Type.LENGTH_PREFIX;
        f.lengthSize = 2;
        return f;
    }

    @Test void tcpServerAndClientExchangeFrames() throws Exception {
        EndpointConfig sc = new EndpointConfig();
        sc.name = "srv";
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        sc.framing = lengthPrefix();
        TcpServerEndpoint server = track(new TcpServerEndpoint(sc, bus));
        server.start();

        EndpointConfig cc = new EndpointConfig();
        cc.name = "cli";
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.remotePort = server.boundPort();
        cc.framing = lengthPrefix();
        TcpClientEndpoint client = track(new TcpClientEndpoint(cc, bus));
        client.start();
        await(client, Endpoint.State.RUNNING);

        client.send(Hex.parse("01 02 03"), null);
        LogEntry got = nextRx();
        assertEquals(server, got.endpoint);
        assertEquals("01 02 03", Hex.toHex(got.data));

        long until = System.currentTimeMillis() + 3000;
        while (server.peers().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10);
        server.send(Hex.parse("AA"), server.peers().get(0));
        got = nextRx();
        assertEquals(client, got.endpoint);
        assertEquals("AA", Hex.toHex(got.data));
        assertEquals(1, client.stats().rxFrames());
        assertEquals(1, server.stats().txFrames());

        server.stop();
        await(client, Endpoint.State.STOPPED);
    }

    @Test void tcpClientReportsRefusedConnection() throws Exception {
        EndpointConfig cc = new EndpointConfig();
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.remotePort = 1; // ayricalikli port; dinleyen yok
        TcpClientEndpoint client = track(new TcpClientEndpoint(cc, bus));
        client.start();
        await(client, Endpoint.State.STOPPED);
        assertEquals(1, client.stats().errors());
    }

    @Test void udpUnicastBothWays() throws Exception {
        EndpointConfig ac = new EndpointConfig();
        ac.name = "a";
        ac.kind = EndpointConfig.Kind.UDP;
        ac.localAddress = "127.0.0.1";
        ac.localPort = 0;
        UdpEndpoint a = track(new UdpEndpoint(ac, bus));
        a.start();

        EndpointConfig bc = ac.copy();
        bc.name = "b";
        bc.remotePort = a.boundPort();
        UdpEndpoint b = track(new UdpEndpoint(bc, bus));
        b.start();

        b.send(Hex.parseText("merhaba"), null);
        LogEntry got = nextRx();
        assertEquals(a, got.endpoint);
        assertEquals("merhaba", new String(got.data, "UTF-8"));
        assertTrue(got.peer.endsWith(":" + b.boundPort()));

        a.send(Hex.parse("00 FF"), got.peer);
        got = nextRx();
        assertEquals(b, got.endpoint);
        assertEquals("00 FF", Hex.toHex(got.data));
        assertEquals(1, a.peers().size());
    }

    @Test void schemaMessagesAreDecodedOnBothSides() throws Exception {
        Schema schema = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        EndpointConfig sc = new EndpointConfig();
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        sc.framing = lengthPrefix();
        TcpServerEndpoint server = track(new TcpServerEndpoint(sc, bus));
        server.setSchema(schema);
        server.start();
        EndpointConfig cc = new EndpointConfig();
        cc.kind = EndpointConfig.Kind.TCP_CLIENT;
        cc.remotePort = server.boundPort();
        cc.framing = lengthPrefix();
        TcpClientEndpoint client = track(new TcpClientEndpoint(cc, bus));
        client.setSchema(schema);
        client.start();
        await(client, Endpoint.State.RUNNING);

        MessageDef m = schema.byName("KOMUT");
        Map<String, Object> body = Values.defaults(m.body);
        body.put("command", "ENGAGE");
        body.put("lat", 41.0);
        client.send(Codec.encode(schema, m, null, body), null);

        LogEntry got = nextRx();
        assertNotNull(got.decoded);
        assertTrue(got.decoded.ok(), got.decoded.error);
        assertEquals("KOMUT", got.decoded.message.name);
        assertEquals("ENGAGE", got.decoded.body.get("command"));
        assertEquals(41.0, got.decoded.body.get("lat"));
    }
}
