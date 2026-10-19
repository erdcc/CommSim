package comm.transport;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Threads;
import comm.core.TrafficBus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

/** Tek sunucuya baglanan TCP istemci; istenirse koptugunda yeniden baglanir. */
public class TcpClientEndpoint extends Endpoint {
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final Framer framer;
    /** Her start yeni bir oturum; eski thread'ler oturumu degismis gorunce sessizce cikar. */
    private volatile Object session;
    private volatile TcpPeer peer;

    public TcpClientEndpoint(EndpointConfig config, TrafficBus bus) {
        this(config, bus, Framer.create(config.framing));
    }

    /** Ozel cerceveleme ile (orn. Modbus MBAP). */
    protected TcpClientEndpoint(EndpointConfig config, TrafficBus bus, Framer framer) {
        super(config, bus);
        this.framer = framer;
    }

    /** Tam bir cerceve alindi (okuyucu thread'inden). */
    protected void onFrame(String peer, byte[] data) {
        received(peer, data);
    }

    /** Sunucuya bagli mi. */
    public boolean isConnected() { return peer != null; }

    @Override public synchronized void start() {
        if (session != null) return;
        Object token = new Object();
        session = token;
        setState(State.STARTING);
        Threads.daemon(name() + "-connect", () -> run(token));
    }

    private void run(Object token) {
        String target = config.remoteHost + ":" + config.remotePort;
        while (session == token) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(config.remoteHost, config.remotePort), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                closeQuietly(s);
                if (session != token) return;
                error(target, "Bağlanılamadı: " + reason(e));
                if (!retry(token)) return;
                continue;
            }
            TcpPeer p;
            try {
                p = new TcpPeer(s, config());
            } catch (IOException e) {
                closeQuietly(s);
                if (!retry(token)) return;
                continue;
            }
            synchronized (this) {
                if (session != token) {
                    p.close();
                    return;
                }
                peer = p;
            }
            setState(State.RUNNING);
            info(p.addr, "Bağlandı");
            String why = p.readLoop(framer.decoder(), data -> onFrame(p.addr, data));
            p.close();
            synchronized (this) {
                if (session != token) return;
                peer = null;
            }
            info(p.addr, "Bağlantı koptu (" + why + ")");
            if (!retry(token)) return;
        }
    }

    /** Yeniden deneme ayarliysa bekler ve true doner; degilse oturumu kapatir. */
    private boolean retry(Object token) {
        if (config.reconnectMs <= 0) {
            synchronized (this) {
                if (session != token) return false;
                session = null;
            }
            setState(State.STOPPED);
            return false;
        }
        setState(State.STARTING);
        Threads.sleep(config.reconnectMs);
        return session == token;
    }

    @Override public synchronized void stop() {
        boolean wasActive = session != null;
        session = null;
        TcpPeer p = peer;
        peer = null;
        if (p != null) p.close();
        if (wasActive) info(null, "Durduruldu");
        setState(State.STOPPED);
    }

    @Override protected void sendRaw(byte[] payload, String target) throws IOException {
        TcpPeer p = peer;
        if (p == null) throw new IOException("bağlı değil");
        try {
            p.write(framer.encode(payload));
        } catch (IOException e) {
            error(p.addr, "Gönderilemedi: " + reason(e));
            p.close();
            throw e;
        }
        sent(p.addr, payload);
    }

    @Override public List<String> peers() {
        TcpPeer p = peer;
        return p == null ? Collections.<String>emptyList() : Collections.singletonList(p.addr);
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
    }
}
