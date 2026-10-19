package comm.ws;

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

/** WebSocket sunucu: birden cok istemci; her uygulama mesaji trafik logunda bir cercevedir. */
public final class WsServerEndpoint extends Endpoint {
    private final List<WsConnection> peers = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile Thread pinger;

    public WsServerEndpoint(EndpointConfig config, TrafficBus bus) { super(config, bus); }

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
        info(null, "WebSocket dinleniyor: ws://" + config.localAddress + ":" + ss.getLocalPort() + path());
        Threads.daemon(name() + "-ws-accept", () -> acceptLoop(ss));
        if (config.wsPingMs > 0) pinger = Threads.daemon(name() + "-ws-ping", () -> pingLoop(ss));
    }

    private String path() { return config.wsPath.isEmpty() ? "/" : config.wsPath; }

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
            Threads.daemon(name() + "-ws-conn", () -> serve(ss, s));
        }
    }

    private void serve(ServerSocket ss, Socket s) {
        String from = s.getInetAddress().getHostAddress() + ":" + s.getPort();
        WsConnection c;
        try {
            s.setSoTimeout(10_000); // el sikisma suresi
            s.setTcpNoDelay(config().tcpNoDelay);
            s.setKeepAlive(config().tcpKeepAlive);
            c = WsConnection.accept(s, config.wsPath, config.wsSubprotocol);
            s.setSoTimeout(0);
        } catch (IOException e) {
            error(from, "El sıkışma başarısız: " + reason(e));
            try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
            return;
        }
        if (server != ss) {
            c.closeSocket();
            return;
        }
        peers.add(c);
        info(c.addr, "WebSocket bağlandı" + (c.protocol.isEmpty() ? "" : " (alt protokol " + c.protocol + ")"));
        peersChanged();
        String why = c.readLoop(new WsConnection.Handler() {
            @Override public void message(byte[] data, boolean text) { received(c.addr, data); }
        });
        peers.remove(c);
        c.closeSocket();
        if (server == ss) {
            info(c.addr, "WebSocket ayrıldı (" + why + ")");
            peersChanged();
        }
    }

    private void pingLoop(ServerSocket ss) {
        while (server == ss && Threads.sleep(Math.max(100, config.wsPingMs))) {
            for (WsConnection c : peers) {
                try {
                    c.ping();
                } catch (IOException e) {
                    c.closeSocket();
                }
            }
        }
    }

    @Override public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { /* kapaniyor */ }
        }
        for (WsConnection c : peers) {
            c.close(1001, "going away");
            c.closeSocket();
        }
        peers.clear();
        if (pinger != null) pinger.interrupt();
        if (ss != null) info(null, "Durduruldu");
        setState(State.STOPPED);
    }

    @Override protected void sendRaw(byte[] payload, String peer) throws IOException {
        boolean text = config.wsText && WsConnection.isUtf8(payload);
        int ok = 0;
        boolean matched = false;
        for (WsConnection c : peers) {
            if (peer != null && !peer.equals(c.addr)) continue;
            matched = true;
            try {
                c.sendMessage(payload, text);
                sent(c.addr, payload);
                ok++;
            } catch (IOException e) {
                error(c.addr, "Gönderilemedi: " + reason(e));
                c.closeSocket();
            }
        }
        if (!matched) throw new IOException(peer == null ? "bağlı istemci yok" : "eş bulunamadı: " + peer);
        if (ok == 0) throw new IOException("gönderilemedi");
    }

    @Override public List<String> peers() {
        List<String> out = new ArrayList<>();
        for (WsConnection c : peers) out.add(c.addr);
        return out;
    }
}
