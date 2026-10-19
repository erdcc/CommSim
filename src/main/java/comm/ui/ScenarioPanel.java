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
            "  while <ifade> <op> <ifade> ... end     Koşul doğru oldukça tekrarlar",
            "  break                                 En içteki repeat/while döngüsünden çıkar",
            "  function <ad> [param ...] ... end      Komut grubu tanımlar (en dış seviyede)",
            "  call <ad> [arg ...]                   Fonksiyonu çağırır; argümanlar ${param} olur",
            "  return                                Fonksiyondan çıkar",
            "  include <dosya.scn>                   Başka bir senaryo dosyasını buraya ekler",
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
            "Arayüzsüz çalıştırma (CI):  java -jar commsim.jar --run proje.json [senaryo.scn|senaryo.groovy] [--start-all] [--timeout sn] [--verbose]",
            "  çıkış kodu 0 başarılı, 1 kontrol başarısız, 2 hata/zaman aşımı",
            "",
            "Bir komut hata verirse senaryo durur; expect/assert hataları sayılır ve sonda özetlenir.");

    static final String GROOVY_HELP = String.join("\n",
            "Groovy senaryoları: tam bir programlama dili (döngüler, fonksiyonlar, listeler, dosya okuma…).",
            "Aşağıdaki yöntemler doğrudan çağrılır; aynıları sim.xxx(...) olarak da kullanılabilir.",
            "",
            "  start(\"bağlantı\") / stop(\"bağlantı\")",
            "  sleep(ms)                                        Durdur düğmesine hemen yanıt verir",
            "  log(\"metin\")",
            "  send(\"bağlantı\", \"MESAJ\", alan: değer, ...)      Değerleri atar ve gönderir",
            "  set(\"bağlantı\", \"MESAJ\", alan: değer, ...)       Yalnızca gönderilecek değerleri değiştirir",
            "  sendHex(\"bağlantı\", \"A5 01 02\")  sendText(\"bağlantı\", \"OK\\r\\n\")  sendBytes(\"bağlantı\", byte[])",
            "  def m = waitFor(\"bağlantı\", \"MESAJ\", ms)         Mesajı bekler, alanlarını döndürür: m.speed, m['başlık.seq']",
            "  def m = last(\"bağlantı\", \"MESAJ\")               Son alınan değerler (yoksa null)",
            "  expect(\"bağlantı\", \"MESAJ\", alan: değer)          Son alınanı kontrol eder (sayılır)",
            "  check(koşul, \"açıklama\")                         Genel kontrol (sayılır)",
            "  fail(\"neden\")  exit(\"mesaj\")",
            "  modbusRead(\"master\", fc, adres, adet)  modbusWrite(\"master\", adres, [1, 2])  modbusCoil(\"master\", adres, true)",
            "  mbset(\"slave\", \"hr\", adres, [değerler])",
            "  snmpGet(\"manager\", \"1.3.6...\")  snmpWalk(...)  snmpSet(\"manager\", oid, \"INTEGER\", 5)  trap(\"agent\", trapOid, oid...)",
            "  dsl('''send \"bağlantı\" KONUM speed=5''')           Senaryo dilinde satır çalıştırır",
            "  vars.ad = \"5\"                                    Senaryo diliyle ortak değişkenler (${ad})",
            "  endpoint(\"bağlantı\")                             Alt düzey bağlantı nesnesi",
            "",
            "Durdur düğmesi döngüleri de keser. Hata mesajlarında betik satır numarası yazar.");

    private static final String SAMPLE_GROOVY = String.join("\n",
            "// Örnek Groovy senaryosu: hızı 100'den 150'ye artırarak gönderir ve sunucuda doğrular.",
            "def sunucu = \"Örnek TCP sunucu\"",
            "def istemci = \"Örnek TCP istemci\"",
            "start sunucu",
            "start istemci",
            "sleep 300",
            "",
            "for (hiz in (100..150).step(10)) {",
            "    send istemci, \"KONUM\", speed: hiz, heading: 45",
            "    def m = waitFor(sunucu, \"KONUM\", 2000)",
            "    check m.speed == \"$hiz\", \"hız $hiz alındı\"",
            "}",
            "",
            "def hedefler = [[identity: \"HOSTILE\"], [identity: \"FRIEND\"]]",
            "def atama = [tracks: hedefler.size()]",
            "hedefler.eachWithIndex { h, i -> atama[\"tracks[$i].identity\"] = h.identity }",
            "send istemci, \"HEDEF_LISTESI\", atama",
            "log \"Bitti\"",
            "");

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

    /** Editorun tamamlama ve uyarilar icin bildigi baglantilar (ana pencere saglar). */
    private java.util.function.Supplier<java.util.List<comm.ui.editor.Target>> targets = java.util.Collections::emptyList;
    private final comm.ui.editor.ScriptEditor editor = new comm.ui.editor.ScriptEditor(() -> targets.get(),
            () -> this.file != null ? this.file.getAbsoluteFile().getParentFile().toPath() : null);
    private final JTextPane output = new JTextPane();
    private final JButton runBtn = UiKit.primary("Çalıştır", Icons.PLAY);
    private final JButton stopBtn = UiKit.button("Durdur", Icons.STOP);
    private final JLabel fileLabel = new JLabel(" ");
    private final javax.swing.JComboBox<String> language = new javax.swing.JComboBox<>(new String[]{"Senaryo dili", "Groovy"});
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
        bar.add(UiKit.muted(" Dil"));
        bar.add(language);
        language.setToolTipText("Senaryo dili: basit komut satırları.  Groovy: tam programlama dili (.groovy)");
        language.addActionListener(e -> languageChanged());
        bar.add(open);
        bar.add(save);
        bar.add(help);
        bar.add(clear);
        bar.add(fileLabel);
        add(bar, BorderLayout.NORTH);

        Font mono = Theme.mono(12);
        editor.setText(SAMPLE);
        editor.setOnRun(this::run);
        output.setEditable(false);
        output.setFont(mono);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, new JScrollPane(output));
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        stopBtn.setEnabled(false);
        runBtn.addActionListener(e -> run());
        stopBtn.addActionListener(e -> runner.stop());
        open.addActionListener(e -> open());
        save.addActionListener(e -> save());
        help.addActionListener(e -> {
            JTextArea t = new JTextArea(isGroovy() ? GROOVY_HELP : HELP);
            t.setEditable(false);
            t.setFont(mono);
            JOptionPane.showMessageDialog(this, new JScrollPane(t), isGroovy() ? "Groovy senaryoları" : "Senaryo komutları",
                    JOptionPane.PLAIN_MESSAGE);
        });
        clear.addActionListener(e -> output.setText(""));
    }

    String text() { return editor.getText(); }

    void setTargets(java.util.function.Supplier<java.util.List<comm.ui.editor.Target>> t) {
        targets = t;
        editor.targetsChanged();
    }

    boolean isGroovy() { return language.getSelectedIndex() == 1; }

    /** Proje dosyasindan gelen senaryo ve dili. */
    void setScript(String text, boolean groovy) {
        language.setSelectedIndex(groovy ? 1 : 0);
        setText(text);
    }

    /** Dil degisince editor ornek metin iceriyorsa digerinin ornegine gecer. */
    private void languageChanged() {
        editor.setGroovy(isGroovy());
        String t = editor.getText().trim();
        if (t.isEmpty() || t.equals(SAMPLE.trim()) || t.equals(SAMPLE_GROOVY.trim())) setText(isGroovy() ? SAMPLE_GROOVY : SAMPLE);
    }

    void setText(String s) {
        editor.setText(s);
    }

    void stop() { runner.stop(); }

    private void run() {
        try {
            ScenarioRunner.Output out = (line, error) -> SwingUtilities.invokeLater(() -> append(line, error));
            Runnable done = () -> SwingUtilities.invokeLater(() -> {
                runBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            });
            java.nio.file.Path base = file != null ? file.getAbsoluteFile().getParentFile().toPath() : null;
            if (isGroovy()) {
                runner.startGroovy(editor.getText(), file != null ? file.getName() : "senaryo.groovy", base, context, out, done);
            } else {
                runner.start(editor.getText(), base, context, out, done);
            }
            runBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } catch (ScenarioException e) {
            append((isGroovy() ? "Derleme hatası: " : "Sözdizimi hatası: ") + e.getMessage(), true);
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
        fc.setFileFilter(new FileNameExtensionFilter("Senaryo (*.scn, *.groovy, *.txt)", "scn", "groovy", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String body = new String(Files.readAllBytes(fc.getSelectedFile().toPath()), StandardCharsets.UTF_8);
            boolean groovy = fc.getSelectedFile().getName().toLowerCase(java.util.Locale.ROOT).endsWith(".groovy");
            file = fc.getSelectedFile();
            setScript(body, groovy);
            fileLabel.setText(file.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Açılamadı", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : new File("scenarios"));
        fc.setFileFilter(isGroovy() ? new FileNameExtensionFilter("Groovy senaryosu (*.groovy)", "groovy")
                : new FileNameExtensionFilter("Senaryo (*.scn)", "scn"));
        if (file != null) fc.setSelectedFile(file);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getPath() + (isGroovy() ? ".groovy" : ".scn"));
        try {
            Files.write(f.toPath(), editor.getText().getBytes(StandardCharsets.UTF_8));
            file = f;
            fileLabel.setText(f.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Kaydedilemedi", JOptionPane.ERROR_MESSAGE);
        }
    }
}
