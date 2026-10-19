package comm.scenario;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Editor icin calistirmadan denetim: sozdizimi / derleme hatalarini satir ve sutunuyla dondurur. */
public final class ScriptCheck {
    /** Bir sorun; satir ve sutun 1 tabanli, sutun bilinmiyorsa 0. */
    public static final class Problem {
        public final int line;
        public final int column;
        public final int endColumn;
        public final String message;
        public final boolean warning;

        public Problem(int line, int column, int endColumn, String message, boolean warning) {
            this.line = line;
            this.column = column;
            this.endColumn = endColumn;
            this.message = message;
            this.warning = warning;
        }
    }

    private ScriptCheck() { }

    /** Groovy betigini derler; hatalar varsa listeler. */
    public static List<Problem> groovy(String code) {
        List<Problem> out = new ArrayList<>();
        try {
            GroovySupport.compileForCheck(code, out);
        } catch (RuntimeException | LinkageError e) {
            out.add(new Problem(1, 0, 0, "derleyici hatası: " + e, false));
        }
        return out;
    }

    private static final Pattern MAIN = Pattern.compile("^satır (\\d+): (.*)$", Pattern.DOTALL);
    private static final Pattern INCLUDED = Pattern.compile("^(\\S+) satır (\\d+): (.*)$", Pattern.DOTALL);

    /** Senaryo dilini ayristirir (include dosyalari dahil); ilk hatayi dondurur. */
    public static List<Problem> dsl(String text, Path baseDir) {
        List<Problem> out = new ArrayList<>();
        try {
            Scenario.parseProgram(text, baseDir);
        } catch (ScenarioException e) {
            String m = e.getMessage();
            Matcher a = MAIN.matcher(m);
            Matcher b = INCLUDED.matcher(m);
            if (a.matches()) {
                out.add(new Problem(Integer.parseInt(a.group(1)), 0, 0, a.group(2), false));
            } else if (b.matches()) {
                out.add(new Problem(includeLine(text, b.group(1)), 0, 0, m, false));
            } else {
                out.add(new Problem(1, 0, 0, m, false));
            }
        }
        return out;
    }

    /** Hatali dosyayi ekleyen include satiri. */
    private static int includeLine(String text, String file) {
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("include") && l.contains(file)) return i + 1;
        }
        return 1;
    }
}
