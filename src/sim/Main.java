package sim;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        Path defFile = Path.of(args.length > 0 ? args[0] : "messages.def");
        SwingUtilities.invokeLater(() -> {
            try {
                MapPanel map = new MapPanel(Path.of("data"));
                SimulatorFrame frame = new SimulatorFrame(DefinitionLoader.load(defFile), map);
                frame.addMessageListener(map);
                frame.setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Mesaj tanımları okunamadı:\n" + e.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
