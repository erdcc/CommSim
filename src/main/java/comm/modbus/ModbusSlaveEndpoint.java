package comm.modbus;

import comm.core.Direction;
import comm.core.EndpointConfig;
import comm.core.Threads;
import comm.core.TrafficBus;
import comm.schema.Decoded;
import comm.transport.TcpServerEndpoint;

import java.io.IOException;

/** Modbus TCP slave (sunucu): gelen istekleri kendi tablolarina uygular ve yanitlar. */
public final class ModbusSlaveEndpoint extends TcpServerEndpoint {
    private final ModbusTables tables = new ModbusTables();
    /** Yanit gonderilirken, log cozumunun istek adreslerini bilmesi icin. */
    private final ThreadLocal<byte[]> currentRequest = new ThreadLocal<>();

    public ModbusSlaveEndpoint(EndpointConfig config, TrafficBus bus) {
        super(config, bus, new MbapFramer());
    }

    public ModbusTables tables() { return tables; }

    @Override protected void onFrame(String peer, byte[] adu) {
        received(peer, adu);
        if (adu.length < 8) return;
        if (Modbus.protocolId(adu) != 0) {
            error(peer, "Protokol id 0 değil; yanıtlanmadı");
            return;
        }
        int unit = Modbus.unit(adu);
        if (!config.modbusAnyUnit && unit != config.modbusUnitId) {
            info(peer, "Unit " + unit + " için gelen istek yok sayıldı (bu slave: " + config.modbusUnitId + ")");
            return;
        }
        byte[] req = Modbus.pdu(adu);
        byte[] resp = Modbus.process(req, tables);
        if (config.modbusDelayMs > 0) Threads.sleep(config.modbusDelayMs);
        currentRequest.set(req);
        try {
            send(Modbus.adu(Modbus.tid(adu), unit, resp), peer);
        } catch (IOException ignored) {
            // gonderim hatasi zaten loglandi
        } finally {
            currentRequest.remove();
        }
    }

    @Override protected Decoded decode(byte[] data, Direction dir) {
        return ModbusDecoder.decode(data, dir == Direction.RX, currentRequest.get());
    }
}
