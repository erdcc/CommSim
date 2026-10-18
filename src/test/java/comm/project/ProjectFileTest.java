package comm.project;

import comm.core.EndpointConfig;
import comm.core.FramingConfig;
import comm.core.ModbusPoll;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.schema.Values;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileTest {
    @Test void savesAndLoadsAllEndpointSettings(@TempDir Path dir) throws Exception {
        ProjectFile p = new ProjectFile();
        EndpointConfig c = new EndpointConfig();
        c.name = "Master ğüşİ";
        c.kind = EndpointConfig.Kind.MODBUS_MASTER;
        c.remotePort = 5020;
        c.framing.type = FramingConfig.Type.DELIMITER;
        c.framing.delimiterHex = "0D 0A";
        ModbusPoll poll = new ModbusPoll();
        poll.function = 4;
        poll.address = 100;
        poll.intervalMs = 250;
        c.modbusPolls.add(poll);
        c.snmpVersion = "V3";
        c.snmpUser = "u";
        ProjectFile.EndpointEntry e = new ProjectFile.EndpointEntry();
        e.config = c;
        e.txMessages.put("KONUM", Collections.singletonMap("speed", "7"));
        Map<Integer, Integer> hr = new LinkedHashMap<>();
        hr.put(3, 65535);
        e.modbusTables = Collections.singletonMap("HOLDING", hr);
        e.oidTable = Collections.singletonList("1.3.6.1.4.1.1.0  INTEGER  rw  5");
        p.endpoints.add(e);
        p.scenario = "log merhaba\n";

        Path f = dir.resolve("p.json");
        p.save(f);
        ProjectFile q = ProjectFile.load(f);
        EndpointConfig d = q.endpoints.get(0).config;
        assertEquals("Master ğüşİ", d.name);
        assertEquals(EndpointConfig.Kind.MODBUS_MASTER, d.kind);
        assertEquals(FramingConfig.Type.DELIMITER, d.framing.type);
        assertEquals("0D 0A", d.framing.delimiterHex);
        assertEquals(1, d.modbusPolls.size());
        assertEquals(250, d.modbusPolls.get(0).intervalMs);
        assertTrue(d.modbusPolls instanceof java.util.concurrent.CopyOnWriteArrayList);
        assertEquals("V3", d.snmpVersion);
        assertEquals("7", q.endpoints.get(0).txMessages.get("KONUM").get("speed"));
        assertEquals(Integer.valueOf(65535), q.endpoints.get(0).modbusTables.get("HOLDING").get(3));
        assertEquals("log merhaba\n", q.scenario);
    }

    @Test void messageValuesRoundTripAsText() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        MessageDef m = s.byName("HEDEF_LISTESI");
        Map<String, Object> v = Values.complete(m.body, new LinkedHashMap<>());
        Values.applyTexts(m.body, v, "", Collections.singletonMap("tracks", "2"));
        Map<String, String> set = new LinkedHashMap<>();
        set.put("tracks[1].identity", "HOSTILE");
        set.put("tracks[0].lat", "41.5");
        assertTrue(Values.applyTexts(m.body, v, "", set).isEmpty());

        Map<String, String> texts = Values.toTexts(m.body, v, "");
        assertEquals("2", texts.get("tracks"));
        Map<String, Object> w = Values.complete(m.body, new LinkedHashMap<>());
        List<String> problems = Values.applyTexts(m.body, w, "", texts);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Values.toTexts(m.body, v, ""), Values.toTexts(m.body, w, ""));
        assertEquals(1, Values.applyTexts(m.body, w, "", Collections.singletonMap("yok", "1")).size());
    }
}
