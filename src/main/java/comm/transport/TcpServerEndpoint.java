package comm.transport;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Threads;
import comm.core.TrafficBus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Birden cok istemci kabul eden TCP sunucu. Gonderim tum eslere ya da secilen ese gider. */
public class TcpServerEndpoint extends Endpoint {
    private final Framer framer;
    private final List<TcpPeer> peers = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;

    public TcpServerEndpoint(EndpointConfig config, TrafficBus bus) {
        this(config, bus, Framer.create(config.framing));
    }

    /** Ozel cerceveleme ile (orn. Modbus MBAP). */
    protected TcpServerEndpoint(EndpointConfig config, TrafficBus bus, Framer framer) {
        super(config, bus);
        this.framer = framer;
    }

    /** Tam bir cerceve alindi (okuyucu thread'inden). Alt siniflar cevap uretmek icin genisletir. */
    protected void onFrame(String peer, byte[] data) {
        received(peer, data);
    }

    @Override public synchronized void start() throws IOException {
        if (server != null) return;
        setState(State.STARTING);
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(config.localAddress, config.localPort));
        } catch (IOException e) {
            ss.close();
            error(null, "Dinlenemedi " + config.localAddress + ":" + config.localPort + ": " + reason(e));
            setState(State.ERROR);
            throw e;
        }
        server = ss;
        setState(State.RUNNING);
        info(null, "Dinleniyor: " + config.localAddress + ":" + ss.getLocalPort());
        Threads.daemon(name() + "-accept", () -> acceptLoop(ss));
    }

    /** Gercekte dinlenen port (0 verildiyse sistemin sectigi). */
    public int boundPort() {
        ServerSocket ss = server;
        return ss == null ? -1 : ss.getLocalPort();
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    error(null, "Kabul hatası: " + reason(e));
                    Threads.sleep(100);
                }
                continue;
            }
            addPeer(ss, s);
        }
    }

    private void addPeer(ServerSocket ss, Socket s) {
        TcpPeer p;
        try {
            p = new TcpPeer(s, config());
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
            return;
        }
        synchronized (this) {
            if (server != ss) {
                p.close();
                return;
            }
            peers.add(p);
        }
        info(p.addr, "Eş bağlandı");
        peersChanged();
        Threads.daemon(name() + "-read-" + p.addr, () -> {
            String why = p.readLoop(framer.decoder(), data -> onFrame(p.addr, data));
            peers.remove(p);
            p.close();
            if (server == ss) {
                info(p.addr, "Eş ayrıldı (" + why + ")");
                peersChanged();
            }
        });
    }

    @Override public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { /* kapaniyor */ }
        }
        for (TcpPeer p : peers) p.close();
        peers.clear();
        if (ss != null) info(null, "Durduruldu");
        setState(State.STOPPED);
    }

    @Override protected void sendRaw(byte[] payload, String peer) throws IOException {
        byte[] wire = framer.encode(payload);
        int ok = 0;
        boolean matched = false;
        for (TcpPeer p : peers) {
            if (peer != null && !peer.equals(p.addr)) continue;
            matched = true;
            try {
                p.write(wire);
                sent(p.addr, payload);
                ok++;
            } catch (IOException e) {
                error(p.addr, "Gönderilemedi: " + reason(e));
                p.close(); // okuyucu thread temizler
            }
        }
        if (!matched) throw new IOException(peer == null ? "bağlı eş yok" : "eş bulunamadı: " + peer);
        if (ok == 0) throw new IOException("gönderilemedi");
    }

    @Override public List<String> peers() {
        List<String> out = new ArrayList<>();
        for (TcpPeer p : peers) out.add(p.addr);
        return out;
    }
}
