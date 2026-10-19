package comm;

import comm.ui.MainFrame;

import javax.swing.SwingUtilities;
import comm.ui.Theme;

/** Haberlesme simulatoru giris noktasi. Arguman olarak bir proje dosyasi (.json) verilebilir. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--run")) {
            // arayuzsuz calistirma (CI): sonuc cikis koduyla bildirilir
            System.setProperty("java.awt.headless", "true");
            System.exit(comm.runtime.HeadlessRunner.main(args, System.out));
        }
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Kullanım:");
            System.out.println("  java -jar commsim.jar [proje.json]                    arayüzü açar");
            System.out.println("  java -jar commsim.jar --run proje.json [senaryo.scn|senaryo.groovy] [--start-all] [--timeout sn] [--verbose]");
            System.out.println("      arayüzsüz çalıştırır; çıkış kodu 0 başarılı, 1 kontrol başarısız, 2 hata");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            java.nio.file.Path project = args.length > 0 ? java.nio.file.Paths.get(args[0]) : null;
            new MainFrame(project).setVisible(true);
        });
    }
}
