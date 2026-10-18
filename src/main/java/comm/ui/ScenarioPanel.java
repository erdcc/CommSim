package comm.ui;

import comm.scenario.ScenarioContext;
import comm.scenario.ScenarioException;
import comm.scenario.ScenarioRunner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Senaryo duzenleyici ve calistirici. */
final class ScenarioPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int MAX_OUTPUT = 200_000;

    static final String HELP = String.join("\n",
            "Senaryo komutları (her satır bir komut; # ile başlayan satırlar yorum).",
            "Boşluk içeren bağlantı adları ve değerler çift tırnakla yazılır.",
            "",
            "  start <bağlantı>                      Bağlantıyı başlatır, çalışana dek bekler",
            "  stop <bağlantı>",
            "  wait <ms>",
            "  log <metin>",
            "  send <bağlantı> <MESAJ> [alan=değer ...]   Değerleri atar ve şema mesajını gönderir",
            "  set <bağlantı> <MESAJ> alan=değer ...      Yalnızca gönderilecek değeri değiştirir",
            "        Liste/başlık yolları: tracks[1].identity=HOSTILE  başlık.seq=5",
            "  hex <bağlantı> <hex ...>               Ham byte gönderir",
            "  text <bağlantı> <metin>                Metin gönderir (\\r \\n \\xHH kaçışları)",
            "  waitfor <bağlantı> <MESAJ> [ms]         Mesaj gelene dek bekler (varsayılan 5000 ms)",
            "  expect <bağlantı> <MESAJ> alan=değer ...   Son alınan değeri kontrol eder",
            "  modbus <master> read <fc> <adres> <adet>",
            "  modbus <master> write <adres> <değer[,değer...]>",
            "  modbus <master> coil <adres> <0|1>",
            "  mbset <slave> coil|di|hr|ir <adres> <değer[,değer...]>   Slave tablosuna yazar",
            "  snmp <manager> get|getnext|walk <oid ...>",
            "  snmp <manager> set <oid> <TİP> <değer>",
            "  trap <agent> <trapOID> [oid ...]",
            "  repeat <N>  ...  end                  Tekrar bloğu (iç içe olabilir)",
            "",
            "Değişkenler ve akış",
            "  var <ad> = <ifade>                    ör. var n = counter(1,1)   var t = last(\"Sunucu\", KONUM, speed)",
            "  ${ad}                                 sonraki komutlarda değişkenin değeri:  send X KONUM speed=${n}",
            "  if <ifade> <op> <ifade> ... [else ...] end      op: == != < > <= >=",
            "  assert <ifade> <op> <ifade>           kontrol (sayılır, sonda özetlenir)",
            "  fail [mesaj]                          başarısız say ve bitir;  exit [mesaj]  başarıyla bitir",
            "  continue_on_error on|off              açıkken hatalı komut atlanır, başarısız sayılır",
            "",
            "İfadeler: sayı, metin, + - * / %, counter() random() sine() ramp() list() pick() toggle() now(),",
            "          ${değişken}, last(bağlantı, MESAJ, alan)",
            "",
            "Arayüzsüz çalıştırma (CI):  java -jar commsim.jar --run proje.json [senaryo.scn] [--start-all] [--timeout sn] [--verbose]",
            "  çıkış kodu 0 başarılı, 1 kontrol başarısız, 2 hata/zaman aşımı",
            "",
            "Bir komut hata verirse senaryo durur; expect/assert hataları sayılır ve sonda özetlenir.");

    private static final String SAMPLE = String.join("\n",
            "# Örnek senaryo: TCP sunucu ve istemciyi başlatır, komut gönderip yanıt bekler.",
            "start \"Örnek TCP sunucu\"",
            "start \"Örnek TCP istemci\"",
            "wait 300",
            "repeat 3",
            "  send \"Örnek TCP istemci\" KONUM speed=120 heading=45",
            "  waitfor \"Örnek TCP sunucu\" KONUM 2000",
            "  expect \"Örnek TCP sunucu\" KONUM speed=120",
            "  wait 500",
            "end",
            "send \"Örnek TCP istemci\" HEDEF_LISTESI tracks=2 tracks[1].identity=HOSTILE",
            "log Bitti",
            "");

    private final JTextArea editor = new JTextArea(SAMPLE);
    private final JTextPane output = new JTextPane();
    private final JButton runBtn = UiKit.primary("Çalıştır", Icons.PLAY);
    private final JButton stopBtn = UiKit.button("Durdur", Icons.STOP);
    private final JLabel fileLabel = new JLabel(" ");
    private final ScenarioRunner runner = new ScenarioRunner();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private final ScenarioContext context;
    private File file;

    ScenarioPanel(ScenarioContext context) {
        super(new BorderLayout(0, 4));
        this.context = context;
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton open = UiKit.button("Aç…", Icons.OPEN);
        JButton save = UiKit.button("Kaydet…", Icons.SAVE);
        JButton help = UiKit.button("Komutlar", Icons.HELP);
        JButton clear = UiKit.button("Çıktıyı temizle", Icons.CLEAR);
        bar.add(runBtn);
        bar.add(stopBtn);
        bar.add(open);
        bar.add(save);
        bar.add(help);
        bar.add(clear);
        bar.add(fileLabel);
        add(bar, BorderLayout.NORTH);

        Font mono = Theme.mono(12);
        editor.setFont(mono);
        editor.setTabSize(2);
        output.setEditable(false);
        output.setFont(mono);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(editor), new JScrollPane(output));
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        stopBtn.setEnabled(false);
        runBtn.addActionListener(e -> run());
        stopBtn.addActionListener(e -> runner.stop());
        open.addActionListener(e -> open());
        save.addActionListener(e -> save());
        help.addActionListener(e -> {
            JTextArea t = new JTextArea(HELP);
            t.setEditable(false);
            t.setFont(mono);
            JOptionPane.showMessageDialog(this, new JScrollPane(t), "Senaryo komutları", JOptionPane.PLAIN_MESSAGE);
        });
        clear.addActionListener(e -> output.setText(""));
    }

    String text() { return editor.getText(); }

    void setText(String s) {
        editor.setText(s);
        editor.setCaretPosition(0);
    }

    void stop() { runner.stop(); }

    private void run() {
        try {
            runner.start(editor.getText(), context, (line, error) -> SwingUtilities.invokeLater(() -> append(line, error)),
                    () -> SwingUtilities.invokeLater(() -> {
                        runBtn.setEnabled(true);
                        stopBtn.setEnabled(false);
                    }));
            runBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } catch (ScenarioException e) {
            append("Sözdizimi hatası: " + e.getMessage(), true);
        }
    }

    private void append(String line, boolean error) {
        StyledDocument doc = output.getStyledDocument();
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, error ? Theme.err() : output.getForeground());
        try {
            if (doc.getLength() > MAX_OUTPUT) doc.remove(0, doc.getLength() - MAX_OUTPUT / 2);
            doc.insertString(doc.getLength(), fmt.format(new Date()) + "  " + line + "\n", a);
        } catch (BadLocationException ignored) {
            // olmamali
        }
        output.setCaretPosition(doc.getLength());
    }

    private void open() {
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : new File("scenarios"));
        fc.setFileFilter(new FileNameExtensionFilter("Senaryo (*.scn, *.txt)", "scn", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            setText(new String(Files.readAllBytes(fc.getSelectedFile().toPath()), StandardCharsets.UTF_8));
            file = fc.getSelectedFile();
            fileLabel.setText(file.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Açılamadı", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : new File("scenarios"));
        fc.setFileFilter(new FileNameExtensionFilter("Senaryo (*.scn)", "scn"));
        if (file != null) fc.setSelectedFile(file);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getPath() + ".scn");
        try {
            Files.write(f.toPath(), editor.getText().getBytes(StandardCharsets.UTF_8));
            file = f;
            fileLabel.setText(f.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Kaydedilemedi", JOptionPane.ERROR_MESSAGE);
        }
    }
}
