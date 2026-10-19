package comm.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Son acilan / kaydedilen proje dosyalari (kullanici tercihlerinde, en yenisi basta). */
final class RecentProjects {
    private static final Preferences PREFS = Preferences.userNodeForPackage(RecentProjects.class);
    private static final String KEY = "recentProjects";
    private static final int MAX = 8;

    private RecentProjects() { }

    /** Hala var olan dosyalar. */
    static List<Path> list() {
        List<Path> out = new ArrayList<>();
        for (String s : PREFS.get(KEY, "").split("\n")) {
            if (s.isEmpty()) continue;
            try {
                Path p = Paths.get(s);
                if (Files.isRegularFile(p) && !out.contains(p)) out.add(p);
            } catch (RuntimeException ignored) {
                // gecersiz yol: atla
            }
        }
        return out;
    }

    static void add(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        List<Path> l = list();
        l.remove(abs);
        l.add(0, abs);
        while (l.size() > MAX) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Path p : l) sb.append(p).append('\n');
        PREFS.put(KEY, sb.toString());
    }

    static void clear() { PREFS.remove(KEY); }
}
