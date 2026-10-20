package comm.ui.editor;

import comm.schema.Schema;
import comm.schema.SchemaParser;
import comm.scenario.ScriptCheck;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Editorun ekran gerektirmeyen mantigi: imlec baglami ve denetimler. */
class ScriptAssistTest {
    @Test void groovyContextFindsCallArgumentAndString() {
        ScriptAssist.Ctx c = ScriptAssist.groovyContext("    send \"Örnek TCP istemci\", \"KO");
        assertEquals("send", c.call);
        assertEquals(1, c.arg);
        assertTrue(c.inString);
        assertEquals("KO", c.prefix);
        assertEquals("\"Örnek TCP istemci\"", c.args.get(0));

        c = ScriptAssist.groovyContext("def m = waitFor(sunucu, \"");
        assertEquals("waitFor", c.call);
        assertEquals(1, c.arg);
        assertEquals("", c.prefix);

        c = ScriptAssist.groovyContext("send ist, \"KOMUT\", command: \"HO");
        assertEquals("command", c.valueOf);
        assertEquals("HO", c.prefix);

        c = ScriptAssist.groovyContext("check m.spe");
        assertEquals("m", c.member);
        assertEquals("spe", c.prefix);

        c = ScriptAssist.groovyContext("log(last(\"a\", \"B\")[\"tr");
        assertEquals("tr", c.prefix);
        assertTrue(c.inString);
    }

    @Test void dslContextTracksTokens() {
        ScriptAssist.Ctx c = ScriptAssist.dslContext("  sen");
        assertTrue(c.commandPos);
        assertEquals("sen", c.prefix);

        c = ScriptAssist.dslContext("send \"Örnek TCP istemci\" KONUM spe");
        assertEquals("send", c.call);
        assertEquals(2, c.arg);
        assertEquals("spe", c.prefix);
        assertEquals("Örnek TCP istemci", c.args.get(0));

        c = ScriptAssist.dslContext("send x KOMUT command=GO");
        assertEquals("command", c.valueOf);
        assertEquals("GO", c.prefix);

        c = ScriptAssist.dslContext("log hız ${hi");
        assertTrue(c.inVar);
        assertEquals("hi", c.prefix);

        c = ScriptAssist.dslContext("start \"Örn");
        assertTrue(c.inString);
        assertEquals("Örn", c.prefix);
        assertEquals(0, c.arg);

        assertEquals("#", ScriptAssist.dslContext("log a # yorum").call);
    }

    @Test void foldMatchesTurkishLetters() {
        assertEquals("ornek tcp", ScriptAssist.fold("\"Örnek TCP"));
        assertEquals("gonder", ScriptAssist.fold("GÖNDER"));
    }

    private static List<Target> targets() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        return Arrays.asList(
                new Target("Örnek TCP sunucu", "TCP SUNUCU", s, false, false, false, false),
                new Target("Örnek TCP istemci", "TCP İSTEMCİ", s, false, false, false, false),
                new Target("slave", "MODBUS SLAVE", null, false, true, false, false));
    }

    @Test void dslWarningsFlagUnknownNames() throws Exception {
        String text = String.join("\n",
                "start \"Örnek TCP sunucu\"",
                "start yok",
                "send \"Örnek TCP istemci\" KONUMX speed=1",
                "send \"Örnek TCP istemci\" HEDEF_LISTESI tracks=1 tracks[0].identity=HOSTILE hizz=3",
                "start ${degisken}");
        List<ScriptCheck.Problem> w = ScriptEditor.dslWarnings(text, targets());
        assertEquals(3, w.size(), w.toString());
        assertEquals(2, w.get(0).line);
        assertTrue(w.get(0).message.contains("yok"));
        assertEquals(7, w.get(0).column);
        assertTrue(w.get(1).message.contains("KONUMX"));
        assertTrue(w.get(2).message.contains("hizz"));
        assertTrue(w.get(2).warning);
    }

    @Test void groovyWarningsFlagUnknownNames() throws Exception {
        String text = String.join("\n",
                "start \"Örnek TCP sunucu\"",
                "send \"Örnek TCP istemci\", \"YOK_MESAJ\", speed: 1",
                "waitFor('bilinmeyen', 'KONUM')",
                "// start \"yorumda\"",
                "start \"x$ad\"");
        List<ScriptCheck.Problem> w = ScriptEditor.groovyWarnings(text, targets());
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).message.contains("YOK_MESAJ"));
        assertTrue(w.get(1).message.contains("bilinmeyen"));
    }

    @Test void checksReportLines() {
        List<ScriptCheck.Problem> p = ScriptCheck.groovy("log 'a'\nif (true {\n");
        assertTrue(!p.isEmpty());
        assertEquals(2, p.get(0).line);
        assertTrue(ScriptCheck.groovy("log 'a'\nfor (i in 1..3) { log i }\n").isEmpty());
        p = ScriptCheck.dsl("log a\nrepeat 2\nfoo\nend\n", null);
        assertEquals(1, p.size());
        assertEquals(3, p.get(0).line);
        assertTrue(ScriptCheck.dsl("log a\n", null).isEmpty());
    }

    @Test void fieldsIncludeHeaderListAndEnums() throws Exception {
        Schema s = SchemaParser.parse(Paths.get("schemas/ornek.def"));
        List<Target.Field> f = Target.fields(s, s.byName("HEDEF_LISTESI"));
        assertTrue(f.stream().anyMatch(x -> x.path.equals("başlık.seq")));
        assertTrue(f.stream().anyMatch(x -> x.path.equals("tracks")));
        Target.Field id = f.stream().filter(x -> x.path.equals("tracks[0].identity")).findFirst().orElse(null);
        assertTrue(id != null && id.enums.contains("HOSTILE"));
        assertNull(Target.message(s, "YOK"));
    }
}
