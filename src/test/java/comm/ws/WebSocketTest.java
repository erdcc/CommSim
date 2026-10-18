package comm.ws;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import comm.schema.Codec;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.schema.Values;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTest {
    private final TrafficBus bus = new TrafficBus();
    private final List<LogEntry> log = new CopyOnWriteArrayList<>();
    private final List<Endpoint> open = new ArrayList<>();

    WebSocketTest() {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    private static void await(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "zaman aşımı: " + what);
    }

    private WsServerEndpoint server(String path, String proto) throws Exception {
        EndpointConfig c = new EndpointConfig();
        c.kind = EndpointConfig.Kind.WS_SERVER;
        c.localAddress = "127.0.0.1";
        c.localPort = 0;
        c.wsPath = path;
        c.wsSubprotocol = proto;
        WsServerEndpoint s = new WsServerEndpoint(c, bus);
        open.add(s);
        s.start();
        return s;
    }

    private WsClientEndpoint client(int port, String path, String proto) {
        EndpointConfig c = new EndpointConfig();
        c.kind = EndpointConfig.Kind.WS_CLIENT;
        c.remotePort = port;
        c.wsPath = path;
        c.wsSubprotocol = proto;
        WsClientEndpoint e = new WsClientEndpoint(c, bus);
        open.add(e);
        e.start();
        return e;
    }

    private List<LogEntry> rx(Endpoint ep) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : log) if (e.endpoint == ep && e.dir == Direction.RX) out.add(e);
        return out;
    }

    @Test void acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsConnection.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test void schemaMessagesBothWaysWithSubprotocol() throws Exception {
        Schema schema = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        WsServerEndpoint s = server("/veri", "commsim.v1");
        s.setSchema(schema);
        WsClientEndpoint c = client(s.boundPort(), "/veri", "commsim.v1");
        c.setSchema(schema);
        await(c::isConnected, "bağlantı");
        await(() -> !s.peers().isEmpty(), "sunucuda eş");

        MessageDef m = schema.byName("KOMUT");
        c.send(Codec.encode(schema, m, null, Values.defaults(m.body)), null);
        await(() -> !rx(s).isEmpty(), "sunucu RX");
        assertEquals("KOMUT", rx(s).get(0).decoded.message.name);
        assertTrue(rx(s).get(0).decoded.warnings.isEmpty());

        s.send("merhaba".getBytes(StandardCharsets.UTF_8), null);
        await(() -> !rx(c).isEmpty(), "istemci RX");
        assertEquals("merhaba", new String(rx(c).get(0).data, StandardCharsets.UTF_8));
        assertTrue(log.stream().anyMatch(e -> e.endpoint == c && e.dir == Direction.INFO && e.text.contains("alt protokol commsim.v1")));
    }

    @Test void handlesFragmentationPingAndRejections() throws Exception {
        WsServerEndpoint s = server("/", "");
        // elle yazilmis istemci: el sikisma + parcali metin mesaji + ping
        try (Socket raw = new Socket("127.0.0.1", s.boundPort())) {
            OutputStream out = raw.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            DataInputStream in = new DataInputStream(raw.getInputStream());
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
            assertTrue(head.toString().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), head.toString());
            out.write(masked(0x01, "par".getBytes(StandardCharsets.UTF_8)));       // TEXT, fin=0
            out.write(masked(0x80, "çalı".getBytes(StandardCharsets.UTF_8)));      // CONT, fin=1
            out.write(masked(0x89, "p".getBytes(StandardCharsets.UTF_8)));         // PING
            await(() -> !rx(s).isEmpty(), "parçalı mesaj");
            assertEquals("parçalı", new String(rx(s).get(0).data, StandardCharsets.UTF_8));
            assertEquals(0x8A, in.readUnsignedByte()); // PONG
            assertEquals(1, in.readUnsignedByte());
            assertEquals('p', in.readUnsignedByte());
        }

        // yanlis yol -> 404; alt protokol uyusmazligi -> reddedilir
        WsServerEndpoint strict = server("/sadece", "x.v1");
        WsClientEndpoint wrongPath = client(strict.boundPort(), "/baska", "x.v1");
        await(() -> wrongPath.state() == Endpoint.State.STOPPED, "yol reddi");
        assertTrue(log.stream().anyMatch(e -> e.endpoint == wrongPath && e.dir == Direction.ERROR && e.text.contains("404")));
        WsClientEndpoint wrongProto = client(strict.boundPort(), "/sadece", "y.v2");
        await(() -> wrongProto.state() == Endpoint.State.STOPPED, "protokol reddi");
        assertTrue(log.stream().anyMatch(e -> e.endpoint == strict && e.dir == Direction.ERROR && e.text.contains("alt protokol")));
    }

    @Test void clientReconnectsAfterServerRestart() throws Exception {
        WsServerEndpoint s = server("/", "");
        int port = s.boundPort();
        WsClientEndpoint c = client(port, "/", "");
        c.config().reconnectMs = 100;
        await(c::isConnected, "ilk bağlantı");
        s.stop();
        await(() -> !c.isConnected(), "kopma");
        s.config().localPort = port;
        s.start();
        await(c::isConnected, "yeniden bağlantı");
        c.send(new byte[]{1, 2, (byte) 0xFF}, null); // gecersiz UTF-8 -> binary
        await(() -> !rx(s).isEmpty(), "binary");
        assertEquals(3, rx(s).get(0).data.length);
    }

    private static byte[] masked(int b0, byte[] p) {
        byte[] mask = {1, 2, 3, 4};
        byte[] out = new byte[2 + 4 + p.length];
        out[0] = (byte) b0;
        out[1] = (byte) (0x80 | p.length);
        System.arraycopy(mask, 0, out, 2, 4);
        for (int i = 0; i < p.length; i++) out[6 + i] = (byte) (p[i] ^ mask[i & 3]);
        return out;
    }
}
