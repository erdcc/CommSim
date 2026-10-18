package comm.runtime;

import comm.core.EndpointConfig;
import comm.core.FramingConfig;
import comm.project.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessRunnerTest {
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Path project(Path dir, String scenario) throws Exception {
        int port = freePort();
        ProjectFile p = new ProjectFile();
        FramingConfig lp = new FramingConfig();
        lp.type = FramingConfig.Type.LENGTH_PREFIX;
        EndpointConfig s = new EndpointConfig();
        s.name = "Sunucu";
        s.localAddress = "127.0.0.1";
        s.localPort = port;
        s.framing = lp;
        s.schemaFile = java.nio.file.Paths.get("schemas/ornek.def").toAbsolutePath().toString();
        comm.core.ResponseRule r = new comm.core.ResponseRule();
        r.when = "KOMUT";
        r.reply = "HEARTBEAT";
        r.assignments = "uptime=$rx.platformId * 2";
        s.rules.add(r);
        EndpointConfig c = s.copy();
        c.name = "İstemci";
        c.kind = EndpointConfig.Kind.TCP_CLIENT;
        c.remotePort = port;
        c.rules.clear();
        ProjectFile.EndpointEntry es = new ProjectFile.EndpointEntry();
        es.config = s;
        ProjectFile.EndpointEntry ec = new ProjectFile.EndpointEntry();
        ec.config = c;
        ec.txMessages.put("KOMUT", java.util.Collections.singletonMap("platformId", "21"));
        p.endpoints.add(es);
        p.endpoints.add(ec);
        p.scenario = scenario;
        Path f = dir.resolve("p.json");
        p.save(f);
        return f;
    }

    private static int run(String out[], String... args) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int code = HeadlessRunner.main(args, new PrintStream(b, true));
        out[0] = new String(b.toByteArray(), StandardCharsets.UTF_8);
        return code;
    }

    @Test void runsProjectScenarioAndReportsExitCodes(@TempDir Path dir) throws Exception {
        String ok = String.join("\n",
                "send İstemci KOMUT",
                "waitfor İstemci HEARTBEAT 2000",
                "var u = last(İstemci, HEARTBEAT, uptime)",
                "assert ${u} == 42",
                "expect Sunucu KOMUT platformId=21");
        Path p = project(dir, ok);
        String[] out = new String[1];
        assertEquals(HeadlessRunner.OK, run(out, "--run", p.toString(), "--start-all", "--timeout", "20"), out[0]);
        assertTrue(out[0].contains("SONUÇ: BAŞARILI") && out[0].contains("2 kontrol, 0 başarısız"), out[0]);

        Path bad = dir.resolve("kotu.scn");
        Files.write(bad, "send İstemci KOMUT\nwaitfor İstemci HEARTBEAT\nassert last(İstemci, HEARTBEAT, uptime) == 1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(HeadlessRunner.FAILED, run(out, "--run", p.toString(), bad.toString(), "--start-all"), out[0]);

        Path syntax = dir.resolve("sozdizimi.scn");
        Files.write(syntax, "repeat 2\nlog a\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(HeadlessRunner.ERROR, run(out, "--run", p.toString(), syntax.toString()), out[0]);
        assertTrue(out[0].contains("sözdizimi"), out[0]);

        Path slow = dir.resolve("yavas.scn");
        Files.write(slow, "wait 5000\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(HeadlessRunner.ERROR, run(out, "--run", p.toString(), slow.toString(), "--timeout", "1"), out[0]);
        assertTrue(out[0].contains("zaman aşımı"), out[0]);
        assertEquals(HeadlessRunner.ERROR, run(out, "--run"), out[0]);
    }
}
