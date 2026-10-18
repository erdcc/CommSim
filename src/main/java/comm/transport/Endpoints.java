package comm.transport;

import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.TrafficBus;
import comm.modbus.ModbusMasterEndpoint;
import comm.modbus.ModbusSlaveEndpoint;
import comm.snmp.SnmpAgentEndpoint;
import comm.snmp.SnmpManagerEndpoint;

/** Ayara gore dogru Endpoint'i uretir. Yeni protokoller buraya eklenir. */
public final class Endpoints {
    private Endpoints() { }

    public static Endpoint create(EndpointConfig cfg, TrafficBus bus) {
        switch (cfg.kind) {
            case TCP_SERVER: return new TcpServerEndpoint(cfg, bus);
            case TCP_CLIENT: return new TcpClientEndpoint(cfg, bus);
            case UDP: return new UdpEndpoint(cfg, bus);
            case MODBUS_SLAVE: return new ModbusSlaveEndpoint(cfg, bus);
            case MODBUS_MASTER: return new ModbusMasterEndpoint(cfg, bus);
            case SNMP_AGENT: return new SnmpAgentEndpoint(cfg, bus);
            case SNMP_MANAGER: return new SnmpManagerEndpoint(cfg, bus);
            case WS_SERVER: return new comm.ws.WsServerEndpoint(cfg, bus);
            case WS_CLIENT: return new comm.ws.WsClientEndpoint(cfg, bus);
            default: throw new IllegalArgumentException("desteklenmeyen tür: " + cfg.kind);
        }
    }
}
