package comm.snmp;

import org.snmp4j.TransportStateReference;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Gelen ve giden ham SNMP datagramlarini trafik loguna aktaran UDP tasimasi. */
final class LoggingUdpTransport extends DefaultUdpTransportMapping {
    interface Sink {
        void packet(boolean incoming, String peer, byte[] data);
    }

    private final Sink sink;

    /** Dinleyici, SNMP dagiticisindan once eklenir; tampon kopyasi okunur, konum degismez. */
    LoggingUdpTransport(UdpAddress local, Sink sink) throws IOException {
        super(local, true);
        this.sink = sink;
        addTransportListener((transport, address, buf, ref) -> {
            ByteBuffer b = buf.duplicate();
            byte[] data = new byte[b.remaining()];
            b.get(data);
            sink.packet(true, peer(address), data);
        });
    }

    @Override public void sendMessage(UdpAddress target, byte[] message, TransportStateReference ref) throws IOException {
        super.sendMessage(target, message, ref);
        sink.packet(false, peer(target), message);
    }

    static String peer(Address a) {
        if (a instanceof UdpAddress) {
            UdpAddress u = (UdpAddress) a;
            return u.getInetAddress().getHostAddress() + ":" + u.getPort();
        }
        return String.valueOf(a);
    }
}
