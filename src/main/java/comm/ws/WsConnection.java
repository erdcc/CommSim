package comm.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tek bir WebSocket baglantisi (RFC 6455): el sikisma, cerceveleme, maskeleme, parcali mesajlar,
 * ping/pong ve kapanis. Okuma tek thread'den, yazma kilitli.
 */
public final class WsConnection {
    public static final int OP_CONT = 0, OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Alinan uygulama mesaji (metin ya da binary). */
    public interface Handler {
        void message(byte[] data, boolean text);

        default void control(String note) { }
    }

    final Socket socket;
    public final String addr;
    private final DataInputStream in;
    private final OutputStream out;
    /** Istemci cerceveleri maskelenir. */
    private final boolean client;
    /** El sikismada anlasilan alt protokol; yoksa "". */
    public String protocol = "";
    private volatile boolean closeSent;

    private WsConnection(Socket s, boolean client) throws IOException {
        socket = s; // soket secenekleri (no delay, keep-alive) ucta ayarlanir
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = new BufferedOutputStream(s.getOutputStream());
        this.client = client;
        addr = s.getInetAddress().getHostAddress() + ":" + s.getPort();
    }

    // ---- el sikisma ----

    static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] h = sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CRLFCRLF'ye kadar HTTP basligini okur (fazladan byte okumadan). */
    private String readHead() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("el sıkışma yarıda kaldı");
            b.write(c);
            if (b.size() > MAX_HEADER) throw new IOException("HTTP başlığı çok uzun");
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static Map<String, String> headers(String head) {
        Map<String, String> h = new LinkedHashMap<>();
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        return h;
    }

    /**
     * Sunucu tarafi el sikisma.
     * @param path       bos degilse istek yolu bununla eslesmeli
     * @param protocol   bos degilse istemcinin onerdigi alt protokoller arasinda olmali
     */
    public static WsConnection accept(Socket s, String path, String protocol) throws IOException {
        WsConnection c = new WsConnection(s, false);
        String head = c.readHead();
        String[] request = head.split("\r\n", 2)[0].split(" ");
        Map<String, String> h = headers(head);
        String key = h.get("sec-websocket-key");
        if (request.length < 3 || !request[0].equals("GET") || key == null
                || !"websocket".equalsIgnoreCase(h.getOrDefault("upgrade", ""))) {
            c.writeRaw("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
            throw new IOException("WebSocket isteği değil: " + head.split("\r\n", 2)[0]);
        }
        String reqPath = request[1];
        if (path != null && !path.isEmpty() && !reqPath.split("\\?")[0].equals(path)) {
            c.writeRaw("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
            throw new IOException("yol eşleşmedi: " + reqPath + " (beklenen " + path + ")");
        }
        StringBuilder resp = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Accept: ").append(accept(key)).append("\r\n");
        String offered = h.getOrDefault("sec-websocket-protocol", "");
        if (protocol != null && !protocol.isEmpty()) {
            boolean ok = false;
            for (String p : offered.split(",")) ok |= p.trim().equals(protocol);
            if (!ok) {
                c.writeRaw("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
                throw new IOException("alt protokol desteklenmiyor: '" + offered + "' (beklenen " + protocol + ")");
            }
            resp.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");
            c.protocol = protocol;
        }
        c.writeRaw(resp.append("\r\n").toString());
        return c;
    }

    /** Istemci tarafi el sikisma. */
    public static WsConnection connect(Socket s, String host, int port, String path, String protocol) throws IOException {
        WsConnection c = new WsConnection(s, true);
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        StringBuilder req = new StringBuilder("GET ").append(path == null || path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n");
        if (protocol != null && !protocol.isEmpty()) req.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");
        c.writeRaw(req.append("\r\n").toString());
        String head = c.readHead();
        String status = head.split("\r\n", 2)[0];
        if (!status.contains(" 101")) throw new IOException("sunucu yükseltmeyi reddetti: " + status);
        Map<String, String> h = headers(head);
        if (!accept(key).equals(h.get("sec-websocket-accept"))) throw new IOException("Sec-WebSocket-Accept doğrulanamadı");
        c.protocol = h.getOrDefault("sec-websocket-protocol", "");
        return c;
    }

    private void writeRaw(String s) throws IOException {
        synchronized (out) {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }

    // ---- cerceveler ----

    /** Tek cerceve yazar (istemcide maskeli). */
    public void write(int opcode, byte[] payload) throws IOException {
        int n = payload.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(n + 14);
        b.write(0x80 | opcode);
        int maskBit = client ? 0x80 : 0;
        if (n < 126) {
            b.write(maskBit | n);
        } else if (n <= 0xFFFF) {
            b.write(maskBit | 126);
            b.write(n >>> 8);
            b.write(n);
        } else {
            b.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) b.write((int) ((long) n >>> (8 * i)));
        }
        if (client) {
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            b.write(mask, 0, 4);
            byte[] masked = new byte[n];
            for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            b.write(masked, 0, n);
        } else {
            b.write(payload, 0, n);
        }
        synchronized (out) {
            out.write(b.toByteArray());
            out.flush();
        }
    }

    public void sendMessage(byte[] data, boolean text) throws IOException { write(text ? OP_TEXT : OP_BINARY, data); }

    public void ping() throws IOException { write(OP_PING, "commsim".getBytes(StandardCharsets.US_ASCII)); }

    /** Kapanis cercevesi gonderir (bir kez). */
    public void close(int code, String reason) {
        if (closeSent) return;
        closeSent = true;
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + Math.min(r.length, 120)];
        p[0] = (byte) (code >>> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, p.length - 2);
        try {
            write(OP_CLOSE, p);
        } catch (IOException ignored) {
            // baglanti zaten kopmus olabilir
        }
    }

    public void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // kapaniyor
        }
    }

    /** Baglanti kapanana dek mesajlari okur; kapanma nedenini dondurur. */
    public String readLoop(Handler h) {
        ByteArrayOutputStream partial = null;
        boolean partialText = false;
        try {
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                if ((b0 & 0x70) != 0) return protocolError("RSV bitleri ayarlı (uzantı desteklenmiyor)");
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                if (masked == client) return protocolError(client ? "sunucu çerçevesi maskeli" : "istemci çerçevesi maskesiz");
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > MAX_MESSAGE) return protocolError("çerçeve çok büyük: " + len);
                byte[] mask = new byte[4];
                if (masked) in.readFully(mask);
                byte[] p = new byte[(int) len];
                in.readFully(p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                switch (op) {
                    case OP_TEXT:
                    case OP_BINARY:
                        if (partial != null) return protocolError("önceki parçalı mesaj bitmeden yeni mesaj");
                        if (fin) {
                            h.message(p, op == OP_TEXT);
                        } else {
                            partial = new ByteArrayOutputStream();
                            partial.write(p, 0, p.length);
                            partialText = op == OP_TEXT;
                        }
                        break;
                    case OP_CONT:
                        if (partial == null) return protocolError("beklenmeyen devam çerçevesi");
                        partial.write(p, 0, p.length);
                        if (partial.size() > MAX_MESSAGE) return protocolError("mesaj çok büyük");
                        if (fin) {
                            h.message(partial.toByteArray(), partialText);
                            partial = null;
                        }
                        break;
                    case OP_PING:
                        write(OP_PONG, p);
                        h.control("ping alındı, pong gönderildi");
                        break;
                    case OP_PONG:
                        h.control("pong alındı");
                        break;
                    case OP_CLOSE: {
                        int code = p.length >= 2 ? ((p[0] & 0xFF) << 8) | (p[1] & 0xFF) : 1005;
                        String reason = p.length > 2 ? new String(p, 2, p.length - 2, StandardCharsets.UTF_8) : "";
                        close(code, "");
                        return "kapanış " + code + (reason.isEmpty() ? "" : " (" + reason + ")");
                    }
                    default:
                        return protocolError("bilinmeyen opcode " + op);
                }
            }
        } catch (EOFException e) {
            return "karşı taraf kapattı";
        } catch (IOException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private String protocolError(String why) {
        close(1002, "protocol error");
        return "protokol hatası: " + why;
    }

    /** Metin olarak gonderilebilir mi (gecerli UTF-8). */
    public static boolean isUtf8(byte[] b) {
        java.nio.charset.CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();
        try {
            d.decode(java.nio.ByteBuffer.wrap(b));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }
}
