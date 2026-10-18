package comm.ws;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Threads;
import comm.core.TrafficBus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

/** WebSocket istemci; istenirse koptugunda yeniden baglanir ve periyodik ping atar. */
public final class WsClientEndpoint extends Endpoint {
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private volatile Object session;
    private volatile WsConnection conn;

    public WsClientEndpoint(EndpointConfig config, TrafficBus bus) { super(config, bus); }

    private String url() {
        return "ws://" + config.remoteHost + ":" + config.remotePort + (config.wsPath.isEmpty() ? "/" : config.wsPath);
    }

    @Override public synchronized void start() {
        if (session != null) return;
        Object token = new Object();
        session = token;
        setState(State.STARTING);
        Threads.daemon(name() + "-ws-connect", () -> run(token));
    }

    private void run(Object token) {
        while (session == token) {
            Socket s = new Socket();
            WsConnection c;
            try {
                s.connect(new InetSocketAddress(config.remoteHost, config.remotePort), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(10_000);
                c = WsConnection.connect(s, config.remoteHost, config.remotePort, config.wsPath, config.wsSubprotocol);
                s.setSoTimeout(0);
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
                if (session != token) return;
                error(url(), "Bağlanılamadı: " + reason(e));
                if (!retry(token)) return;
                continue;
            }
            synchronized (this) {
                if (session != token) {
                    c.closeSocket();
                    return;
                }
                conn = c;
            }
            setState(State.RUNNING);
            info(c.addr, "WebSocket bağlandı: " + url() + (c.protocol.isEmpty() ? "" : " (alt protokol " + c.protocol + ")"));
            Thread pinger = config.wsPingMs > 0 ? Threads.daemon(name() + "-ws-ping", () -> {
                while (conn == c && Threads.sleep(Math.max(100, config.wsPingMs))) {
                    try {
                        c.ping();
                    } catch (IOException e) {
                        c.closeSocket();
                    }
                }
            }) : null;
            String why = c.readLoop(new WsConnection.Handler() {
                @Override public void message(byte[] data, boolean text) { received(c.addr, data); }
            });
            c.closeSocket();
            if (pinger != null) pinger.interrupt();
            synchronized (this) {
                if (session != token) return;
                conn = null;
            }
            info(c.addr, "WebSocket koptu (" + why + ")");
            if (!retry(token)) return;
        }
    }

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
        boolean was = session != null;
        session = null;
        WsConnection c = conn;
        conn = null;
        if (c != null) {
            c.close(1000, "normal");
            c.closeSocket();
        }
        if (was) info(null, "Durduruldu");
        setState(State.STOPPED);
    }

    public boolean isConnected() { return conn != null; }

    @Override protected void sendRaw(byte[] payload, String peer) throws IOException {
        WsConnection c = conn;
        if (c == null) throw new IOException("bağlı değil");
        try {
            c.sendMessage(payload, config.wsText && WsConnection.isUtf8(payload));
        } catch (IOException e) {
            error(c.addr, "Gönderilemedi: " + reason(e));
            c.closeSocket();
            throw e;
        }
        sent(c.addr, payload);
    }

    @Override public List<String> peers() {
        WsConnection c = conn;
        return c == null ? Collections.<String>emptyList() : Collections.singletonList(c.addr);
    }
}
