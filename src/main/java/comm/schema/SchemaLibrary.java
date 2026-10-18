package comm.schema;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Sema dosyalarini yol bazinda onbellekler; ayni dosyayi kullanan baglantilar ayni Schema'yi paylasir. */
public final class SchemaLibrary {
    private final Map<Path, Schema> cache = new HashMap<>();

    /** Bos yol icin null doner. Goreli yollar calisma dizinine gore cozulur. */
    public synchronized Schema get(String file) throws IOException, SchemaException {
        if (file == null || file.trim().isEmpty()) return null;
        Path p = Paths.get(file.trim()).toAbsolutePath().normalize();
        Schema s = cache.get(p);
        if (s == null) {
            s = SchemaParser.parse(p);
            cache.put(p, s);
        }
        return s;
    }

    /** Sonraki get cagrilari dosyalari yeniden okur. */
    public synchronized void clear() { cache.clear(); }
}
