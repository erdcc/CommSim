package comm.transport;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Threads;
import comm.core.TrafficBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * UDP ucu: unicast, broadcast ve multicast. Her datagram bir cercevedir.
 * Hedef verilmezse varsayilan hedefe (remoteHost:remotePort) gonderir.
 */
public final class UdpEndpoint extends Endpoint {
    private static final int MAX_KNOWN_PEERS = 32;

    private volatile DatagramSocket socket;
    /** Son gorulen gondericiler (en yenisi sonda). */
    private final LinkedHashSet<String> known = new LinkedHashSet<>();

    public UdpEndpoint(EndpointConfig config, TrafficBus bus) {
        super(config, bus);
    }

    @Override public synchronized void start() throws IOException {
        if (socket != null) return;
        setState(State.STARTING);
        DatagramSocket ds;
        try {
            ds = open();
        } catch (IOException e) {
            error(null, "UDP açılamadı: " + reason(e));
            setState(State.ERROR);
            throw e;
        }
        socket = ds;
        setState(State.RUNNING);
        String msg = "Dinleniyor: " + ds.getLocalAddress().getHostAddress() + ":" + ds.getLocalPort();
        if (!config.multicastGroup.isEmpty()) msg += ", grup " + config.multicastGroup;
        info(null, msg);
        Threads.daemon(name() + "-udp", () -> receiveLoop(ds));
    }

    private DatagramSocket open() throws IOException {
        DatagramSocket ds;
        if (config.multicastGroup.isEmpty()) {
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(config.localAddress, config.localPort));
        } else {
            MulticastSocket ms = new MulticastSocket(null);
            try {
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(config.localPort));
                ms.setTimeToLive(config.multicastTtl);
                ms.setLoopbackMode(!config.multicastLoopback); // Java'da true = loopback KAPALI
                InetAddress group = InetAddress.getByName(config.multicastGroup);
                if (!group.isMulticastAddress()) throw new IOException(config.multicastGroup + " multicast adresi değil");
                NetworkInterface ni = null;
                if (!config.networkInterface.isEmpty()) {
                    ni = NetworkInterface.getByName(config.networkInterface);
                    if (ni == null) throw new IOException("ağ arayüzü bulunamadı: " + config.networkInterface);
                    ms.setNetworkInterface(ni);
                }
                ms.joinGroup(new InetSocketAddress(group, 0), ni);
            } catch (IOException e) {
                ms.close();
                throw e;
            }
            ds = ms;
        }
        try {
            ds.setBroadcast(config.broadcast);
        } catch (IOException e) {
            ds.close();
            throw e;
        }
        return ds;
    }

    /** Gercekte dinlenen port (0 verildiyse sistemin sectigi). */
    public int boundPort() {
        DatagramSocket ds = socket;
        return ds == null ? -1 : ds.getLocalPort();
    }

    private void receiveLoop(DatagramSocket ds) {
        byte[] buf = new byte[65535];
        DatagramPacket pk = new DatagramPacket(buf, buf.length);
        while (socket == ds) {
            pk.setLength(buf.length);
            try {
                ds.receive(pk);
            } catch (IOException e) {
                if (socket == ds) {
                    error(null, "Alma hatası: " + reason(e));
                    Threads.sleep(100);
                }
                continue;
            }
            String from = pk.getAddress().getHostAddress() + ":" + pk.getPort();
            remember(from);
            received(from, Arrays.copyOfRange(pk.getData(), pk.getOffset(), pk.getOffset() + pk.getLength()));
        }
    }

    private void remember(String from) {
        boolean added;
        synchronized (known) {
            added = known.remove(from) ? false : true;
            known.add(from);
            if (known.size() > MAX_KNOWN_PEERS) {
                Iterator<String> it = known.iterator();
                it.next();
                it.remove();
            }
        }
        if (added) peersChanged();
    }

    @Override public synchronized void stop() {
        DatagramSocket ds = socket;
        socket = null;
        if (ds != null) {
            ds.close();
            info(null, "Durduruldu");
        }
        synchronized (known) {
            known.clear();
        }
        setState(State.STOPPED);
    }

    @Override protected void sendRaw(byte[] payload, String peer) throws IOException {
        DatagramSocket ds = socket;
        if (ds == null) throw new IOException("çalışmıyor");
        if (payload.length > 65507) throw new IOException("UDP yükü çok büyük: " + payload.length);
        SocketAddress to = peer == null
                ? new InetSocketAddress(config.remoteHost, config.remotePort)
                : parse(peer);
        String label = peer == null ? config.remoteHost + ":" + config.remotePort : peer;
        try {
            ds.send(new DatagramPacket(payload, payload.length, to));
        } catch (IOException e) {
            error(label, "Gönderilemedi: " + reason(e));
            throw e;
        }
        sent(label, payload);
    }

    static InetSocketAddress parse(String hostPort) throws IOException {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0) throw new IOException("adres host:port biçiminde olmalı: " + hostPort);
        try {
            return new InetSocketAddress(hostPort.substring(0, i), Integer.parseInt(hostPort.substring(i + 1)));
        } catch (IllegalArgumentException e) {
            throw new IOException("geçersiz adres: " + hostPort);
        }
    }

    @Override public List<String> peers() {
        synchronized (known) {
            return new ArrayList<>(known);
        }
    }
}
