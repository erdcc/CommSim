package harita;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Jar'a gomulu harita verisini gecici klasore cikarir. OpenMap shapefile'i dosya yolu ister,
 * jar icinden dogrudan okuyamaz. Klasor surume gore adlandirilir ve sonraki acilislarda yeniden kullanilir.
 */
final class BundledData {
    private static final String[] FILES = {"bluemarble.jpg", "dcwpo-browse.shp", "dcwpo-browse.ssx"};
    private static Path cached;

    private BundledData() { }

    /** Verinin bulundugu klasor; cikarilamazsa null (harita verisiz acilir). */
    static synchronized Path dirOrNull() {
        if (cached == null) {
            try {
                cached = extract();
            } catch (IOException | RuntimeException e) {
                System.err.println("[harita] Gomulu harita verisi cikarilamadi: " + e);
            }
        }
        return cached;
    }

    private static Path extract() throws IOException {
        Package pkg = BundledData.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "harita-modulu-" + (version == null ? "dev" : version));
        Files.createDirectories(dir);
        for (String name : FILES) {
            URL url = BundledData.class.getResource("/harita/data/" + name);
            if (url == null) continue;
            Path target = dir.resolve(name);
            if (Files.exists(target) && Files.size(target) > 0) continue;
            Path part = Files.createTempFile(dir, name, ".part");
            try (InputStream in = url.openStream()) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                try {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // baska bir surec ayni dosyayi az once yazmis olabilir
                Files.deleteIfExists(part);
                if (!Files.exists(target) || Files.size(target) == 0) throw e;
            }
        }
        return dir;
    }
}
