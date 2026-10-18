package comm.transport;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** Bir TCP soketi: yazma kilitli, okuma dongusu cerceveleyiciden gecirir. */
final class TcpPeer {
    final Socket socket;
    final String addr;
    private final OutputStream out;

    TcpPeer(Socket s) throws IOException {
        socket = s;
        s.setTcpNoDelay(true);
        out = new BufferedOutputStream(s.getOutputStream());
        addr = s.getInetAddress().getHostAddress() + ":" + s.getPort();
    }

    void write(byte[] wire) throws IOException {
        synchronized (out) {
            out.write(wire);
            out.flush();
        }
    }

    /** Soket kapanana dek okur; kapanma nedenini dondurur. */
    String readLoop(Framer.Decoder decoder, Framer.Sink sink) {
        byte[] buf = new byte[64 * 1024];
        try {
            InputStream in = socket.getInputStream();
            int r;
            while ((r = in.read(buf)) >= 0) {
                if (r > 0) decoder.feed(buf, 0, r, sink);
            }
            return "karşı taraf kapattı";
        } catch (Framer.FramingException e) {
            return "çerçeve hatası: " + e.getMessage();
        } catch (IOException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // kapaniyor
        }
    }
}
