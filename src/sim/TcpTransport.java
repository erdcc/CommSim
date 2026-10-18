package sim;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hafif TCP iletimi. Sunucu modunda birden fazla istemci kabul eder (gonderim hepsine gider),
 * istemci modunda tek sunucuya baglanir. Cerceveleme: int32 uzunluk + yuk.
 */
public final class TcpTransport implements AutoCloseable {
    public interface Listener {
        /** Alici thread'inden cagrilir. */
        void onPacket(byte[] data, String from);
        void onStatus(String message);
        /** Istemci baglanamadi ya da baglanti koptu; iletim tamamen durdu. */
        void onClosed();
        /** Istemci modunda sunucuya baglanildi (alici thread'inden cagrilir). */
        default void onConnected() { }
    }

    private static final int MAX_FRAME = 1 << 20;

    private final Listener listener;
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile Object session;
    private volatile boolean clientMode;

    public TcpTransport(Listener listener) { this.listener = listener; }

    private record Peer(Socket socket, DataInputStream in, DataOutputStream out, String addr) {
        void send(byte[] data) throws IOException {
            synchronized (out) {
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            }
        }
        void close() {
            try { socket.close(); } catch (IOException ignored) { /* kapaniyor */ }
        }
    }

    public synchronized void listen(int port) throws IOException {
        close();
        ServerSocket ss = new ServerSocket(port);
        Object token = new Object();
        session = token;
        server = ss;
        clientMode = false;
        daemon("tcp-accept", () -> {
            while (!ss.isClosed()) {
                try {
                    addPeer(ss.accept(), token);
                } catch (IOException e) {
                    if (!ss.isClosed()) status(token, "Kabul hatası: " + e.getMessage());
                }
            }
        });
    }

    /** Bloklamaz; sonuc onStatus / onClosed ile bildirilir. */
    public synchronized void connect(String host, int port) {
        close();
        Object token = new Object();
        session = token;
        clientMode = true;
        daemon("tcp-connect", () -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 3000);
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
                if (session == token) {
                    status(token, "Bağlanılamadı: " + e.getMessage());
                    closeIf(token);
                }
                return;
            }
            if (session != token) {
                try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
                return;
            }
            addPeer(s, token);
        });
    }

    public int peerCount() { return peers.size(); }

    /** Tum eslere gonderir; gonderilen es sayisini dondurur. */
    public int send(byte[] data) throws IOException {
        if (peers.isEmpty()) throw new IOException("bağlı eş yok");
        int n = 0;
        for (Peer p : peers) {
            try {
                p.send(data);
                n++;
            } catch (IOException e) {
                p.close(); // okuyucu thread temizler
            }
        }
        if (n == 0) throw new IOException("gönderilemedi");
        return n;
    }

    private void addPeer(Socket s, Object token) {
        Peer p;
        try {
            s.setTcpNoDelay(true);
            p = new Peer(s, new DataInputStream(s.getInputStream()),
                    new DataOutputStream(new BufferedOutputStream(s.getOutputStream())),
                    s.getInetAddress().getHostAddress() + ":" + s.getPort());
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { /* kapaniyor */ }
            return;
        }
        peers.add(p);
        if (clientMode && session == token) listener.onConnected();
        status(token, "Eş bağlandı: " + p.addr());
        Peer peer = p;
        daemon("tcp-read-" + p.addr(), () -> readLoop(peer, token));
    }

    private void readLoop(Peer p, Object token) {
        String why = "bağlantı kapandı";
        try {
            while (true) {
                int len = p.in().readInt();
                if (len < 0 || len > MAX_FRAME) {
                    why = "geçersiz çerçeve boyu: " + len;
                    break;
                }
                byte[] buf = new byte[len];
                p.in().readFully(buf);
                if (session == token) listener.onPacket(buf, p.addr());
            }
        } catch (IOException e) {
            if (session == token) why = e.getMessage() == null ? why : e.getMessage();
        }
        peers.remove(p);
        p.close();
        if (session != token) return;
        status(token, "Eş ayrıldı: " + p.addr() + " (" + why + ")");
        if (clientMode) closeIf(token);
    }

    private void status(Object token, String msg) {
        if (session == token) listener.onStatus(msg);
    }

    private void closeIf(Object token) {
        synchronized (this) {
            if (session != token) return;
            close();
        }
        listener.onClosed();
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    @Override public synchronized void close() {
        session = null;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { /* kapaniyor */ }
            server = null;
        }
        for (Peer p : peers) p.close();
        peers.clear();
    }
}
