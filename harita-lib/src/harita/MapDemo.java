package harita;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Minimal ornek. Calistirma: {@code java -jar harita-modulu-<surum>.jar} */
public final class MapDemo {
    private MapDemo() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MapPanel map = new MapPanel();
            map.setNodeListener((node, change) ->
                    System.out.println(change + " " + node.type + " " + node.name + " [" + node.nodeId + "]"));
            map.setSelectionListener(n -> System.out.println("SECILDI " + n.attributes()));

            JFrame f = new JFrame("Harita");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(map);
            f.setSize(900, 700);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
