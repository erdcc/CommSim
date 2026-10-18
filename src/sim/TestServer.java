package sim;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Gelen mesajlari ekrana yazan konsol TCP sunucusu (platform gate'lerini denemek icin).
 * Kullanim: ./server.sh [port] [messages.def]
 */
public final class TestServer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private TestServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
        Path defFile = Path.of(args.length > 1 ? args[1] : "messages.def");
        Codec codec = new Codec(DefinitionLoader.load(defFile));

        TcpTransport server = new TcpTransport(new TcpTransport.Listener() {
            @Override public void onPacket(byte[] data, String from) {
                String t = LocalTime.now().format(TIME);
                try {
                    Model.Message m = codec.decode(data, data.length);
                    StringBuilder sb = new StringBuilder(t + " " + from + "  " + m.def().name() + " (" + data.length + " bayt)");
                    m.values().forEach((k, v) -> sb.append("\n    ").append(k).append(" = ").append(Model.describe(v)));
                    System.out.println(sb);
                } catch (IllegalArgumentException e) {
                    System.out.println(t + " " + from + "  çözülemedi: " + e.getMessage() + "\n" + Codec.hex(data, data.length));
                }
                System.out.flush();
            }

            @Override public void onStatus(String message) { System.out.println("[durum] " + message); }

            @Override public void onClosed() { }
        });
        server.listen(port);
        System.out.println("Test sunucusu dinliyor: TCP " + port + "  (durdurmak için Ctrl+C)");
        Thread.currentThread().join();
    }
}
