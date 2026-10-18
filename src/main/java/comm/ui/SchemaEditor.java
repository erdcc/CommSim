package comm.ui;

import comm.schema.SchemaDraft;
import comm.schema.SchemaDraft.Block;
import comm.schema.SchemaDraft.BlockKind;
import comm.schema.SchemaDraft.Field;
import comm.schema.Schema;
import comm.schema.SchemaException;
import comm.schema.SchemaParser;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gorsel sema duzenleyici: baslik, son ek, struct ve mesajlarin alanlarini tabloda duzenler,
 * uretilen .def metnini canli dogrular ve kaydeder. Dosyadaki yorumlar korunmaz.
 */
final class SchemaEditor extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final Pattern ERROR_LINE = Pattern.compile("^[^:]*:(\\d+):");

    private static final String[] TYPES = {"uint8", "int8", "uint16", "int16", "uint32", "int32", "uint64", "int64",
            "float32", "float64", "bool", "uint16le", "uint32le", "int16le", "int32le", "float32le", "float64le",
            "string", "string[16]", "string[uint8]", "string[*]", "cstring", "bytes[8]", "bytes[uint16]", "bytes[*]",
            "enum", "enum:uint16", "uint16[8]", "uint16[*]", "bits8", "bits16", "bits16le", "bits32", "bits8(msb)",
            "bit", "uint2", "uint3", "uint4", "int4", "enum2", "pad1", "pad4"};
    private static final String[] ROLES = {"", "id", "length", "length(all)", "length(after)", "length(body,adjust=0)",
            "sum8", "xor8", "crc16", "crc16(from=1)", "crc16ccitt", "crc32"};

    private SchemaDraft draft;
    private File file;
    private boolean dirty;
    private final Consumer<String> onSaved;

    private final DefaultListModel<Block> blockModel = new DefaultListModel<>();
    private final JList<Block> blocks = new JList<>(blockModel);
    private final JTextField blockName = new JTextField(14);
    private final JTextField blockId = new JTextField(6);
    private final JLabel idLabel = new JLabel("Id:");
    private final JComboBox<String> endian = new JComboBox<>(new String[]{"big", "little"});
    private final FieldModel fields = new FieldModel();
    private final JTable fieldTable = new JTable(fields);
    private final JComboBox<String> typeEditor = new JComboBox<>();
    private final JTextArea preview = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JLabel fileLabel = new JLabel(" ");
    private boolean updating;

    private SchemaEditor(Window owner, Consumer<String> onSaved) {
        super(owner, "Şema düzenleyici", ModalityType.MODELESS);
        this.onSaved = onSaved;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (confirmDiscard()) dispose();
            }
        });

        // ---- ust cubuk ----
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton newBtn = UiKit.button("Yeni", Icons.ADD);
        JButton openBtn = UiKit.button("Aç…", Icons.OPEN);
        JButton saveBtn = UiKit.primary("Kaydet", Icons.SAVE);
        JButton saveAsBtn = UiKit.button("Farklı kaydet…", Icons.SAVE);
        top.add(newBtn);
        top.add(openBtn);
        top.add(saveBtn);
        top.add(saveAsBtn);
        top.add(new JLabel("   Varsayılan byte sırası:"));
        top.add(endian);
        top.add(new JLabel("   Dosya:"));
        top.add(fileLabel);

        // ---- sol: bloklar ----
        blocks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.setBorder(BorderFactory.createTitledBorder("Bloklar"));
        left.add(new JScrollPane(blocks), BorderLayout.CENTER);
        JPanel blockBtns = new JPanel(new java.awt.GridLayout(0, 3, 2, 2));
        JButton addMsg = UiKit.button("Mesaj", Icons.ADD);
        JButton addStruct = UiKit.button("Struct", Icons.ADD);
        JButton delBlock = UiKit.button("Sil", Icons.DELETE);
        JButton upBlock = new JButton("↑");
        JButton downBlock = new JButton("↓");
        JButton dupBlock = UiKit.button("Kopyala", Icons.COPY);
        blockBtns.add(addMsg);
        blockBtns.add(addStruct);
        blockBtns.add(delBlock);
        blockBtns.add(upBlock);
        blockBtns.add(downBlock);
        blockBtns.add(dupBlock);
        JPanel props = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.LINE_START;
        props.add(new JLabel("Ad:"), c);
        c.gridx = 1;
        props.add(blockName, c);
        c.gridx = 0;
        c.gridy = 1;
        props.add(idLabel, c);
        c.gridx = 1;
        props.add(blockId, c);
        JPanel leftBottom = new JPanel(new BorderLayout());
        leftBottom.add(blockBtns, BorderLayout.NORTH);
        leftBottom.add(props, BorderLayout.SOUTH);
        left.add(leftBottom, BorderLayout.SOUTH);

        // ---- orta: alanlar ----
        fieldTable.setRowHeight(Math.max(fieldTable.getRowHeight(), 22));
        fieldTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        fieldTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        typeEditor.setEditable(true);
        fieldTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeEditor));
        JComboBox<String> roleEditor = new JComboBox<>(ROLES);
        roleEditor.setEditable(true);
        fieldTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(roleEditor));
        fieldTable.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc,
                                                                            int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                Entry e = fields.entry(r);
                boolean child = e.parent != null;
                setBorder(BorderFactory.createEmptyBorder(0, child ? 22 : 6, 0, 4));
                if (e.field.isPad()) {
                    setText("(boşluk)");
                    if (!sel) setForeground(Theme.muted());
                } else {
                    setText((child ? "↳ " : "") + e.field.name);
                    if (!sel) setForeground(t.getForeground());
                }
                return this;
            }
        });
        int[] w = {150, 160, 170, 120, 220};
        for (int i = 0; i < w.length; i++) fieldTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setBorder(BorderFactory.createTitledBorder("Alanlar"));
        center.add(new JScrollPane(fieldTable), BorderLayout.CENTER);
        JPanel fieldBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addField = UiKit.button("Alan ekle", Icons.ADD);
        JButton dupField = UiKit.button("Kopyala", Icons.COPY);
        JButton delField = UiKit.button("Sil", Icons.DELETE);
        JButton upField = new JButton("↑");
        JButton downField = new JButton("↓");
        fieldBtns.add(addField);
        JButton addBit = UiKit.button("Bit alanı", Icons.ADD);
        addBit.setToolTipText("Seçili bit grubuna (bits8/16/32) alt alan ekler");
        fieldBtns.add(addBit);
        addBit.addActionListener(e -> addBitField());
        fieldBtns.add(dupField);
        fieldBtns.add(delField);
        fieldBtns.add(upField);
        fieldBtns.add(downField);
        JLabel hint = new JLabel("Tip ve rol hücrelerine listede olmayan ifadeler de yazılabilir (örn. HEDEF[count], crc16(from=2)).");
        hint.setForeground(Theme.muted());
        fieldBtns.add(hint);
        center.add(fieldBtns, BorderLayout.SOUTH);

        // ---- alt: onizleme ----
        preview.setEditable(false);
        preview.setFont(Theme.mono(12));
        JPanel bottom = new JPanel(new BorderLayout(0, 2));
        bottom.setBorder(BorderFactory.createTitledBorder("Üretilen .def ve doğrulama"));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(new JScrollPane(preview), BorderLayout.CENTER);

        JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, center);
        upper.setResizeWeight(0.25);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, bottom);
        main.setResizeWeight(0.62);
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);

        // ---- olaylar ----
        newBtn.addActionListener(e -> {
            if (confirmDiscard()) load(SchemaDraft.starter(), null, null);
        });
        openBtn.addActionListener(e -> chooseAndOpen());
        saveBtn.addActionListener(e -> save(false));
        saveAsBtn.addActionListener(e -> save(true));
        endian.addActionListener(e -> {
            if (updating) return;
            draft.endian = (String) endian.getSelectedItem();
            changed();
        });
        blocks.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showBlock();
        });
        DocumentListener props2 = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { blockPropsChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { blockPropsChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { blockPropsChanged(); }
        };
        blockName.getDocument().addDocumentListener(props2);
        blockId.getDocument().addDocumentListener(props2);
        addMsg.addActionListener(e -> addMessage());
        addStruct.addActionListener(e -> addStruct());
        delBlock.addActionListener(e -> deleteBlock());
        upBlock.addActionListener(e -> moveBlock(-1));
        downBlock.addActionListener(e -> moveBlock(1));
        dupBlock.addActionListener(e -> duplicateBlock());
        addField.addActionListener(e -> addField());
        dupField.addActionListener(e -> duplicateField());
        delField.addActionListener(e -> deleteField());
        upField.addActionListener(e -> moveField(-1));
        downField.addActionListener(e -> moveField(1));

        setSize(1180, 820);
        setLocationRelativeTo(owner);
    }

    /**
     * Duzenleyiciyi acar.
     * @param path   duzenlenecek .def (null ise yeni sema)
     * @param onSaved kaydedildikten sonra dosya yoluyla cagrilir
     */
    static void open(Window owner, String path, Consumer<String> onSaved) {
        SchemaEditor ed = new SchemaEditor(owner, onSaved);
        if (path == null || path.trim().isEmpty()) ed.load(SchemaDraft.starter(), null, null);
        else if (!ed.openFile(new File(path.trim()))) ed.load(SchemaDraft.starter(), null, null);
        ed.setVisible(true);
    }

    // ---------------- dosya ----------------

    private void chooseAndOpen() {
        if (!confirmDiscard()) return;
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : new File("schemas"));
        fc.setFileFilter(new FileNameExtensionFilter("Mesaj şeması (*.def)", "def"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openFile(fc.getSelectedFile());
    }

    private boolean openFile(File f) {
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Açılamadı", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Schema s;
        try {
            s = SchemaParser.parse(text, f.getName());
        } catch (SchemaException e) {
            JOptionPane.showMessageDialog(this, "Dosya şu an geçersiz olduğu için görsel düzenleyicide açılamıyor:\n"
                    + e.getMessage() + "\n\nÖnce dosyayı metin düzenleyicide düzeltin.", "Şema hatası", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        boolean comments = false;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("#") || t.matches(".*\\s#.*")) {
                comments = true;
                break;
            }
        }
        load(SchemaDraft.from(s), f, comments ? "Not: kaydedince dosyadaki yorumlar korunmaz (\"Farklı kaydet\" ile yeni dosyaya yazabilirsiniz)." : null);
        return true;
    }

    private void load(SchemaDraft d, File f, String note) {
        draft = d;
        file = f;
        dirty = false;
        updating = true;
        endian.setSelectedItem(d.endian);
        updating = false;
        reloadBlocks(0);
        validateDraft();
        if (note != null) {
            status.setForeground(Theme.warn());
            status.setText(status.getText() + "   " + note);
        }
        updateTitle();
    }

    private void save(boolean ask) {
        String text = draft.toText();
        try {
            SchemaParser.parse(text, "şema");
        } catch (SchemaException e) {
            JOptionPane.showMessageDialog(this, "Şema geçersiz, kaydedilmedi:\n" + e.getMessage(), "Kaydedilemedi",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        File f = file;
        if (ask || f == null) {
            JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : new File("schemas"));
            fc.setFileFilter(new FileNameExtensionFilter("Mesaj şeması (*.def)", "def"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            f = fc.getSelectedFile();
            if (!f.getName().contains(".")) f = new File(f.getPath() + ".def");
            if (f.exists() && !f.equals(file) && JOptionPane.showConfirmDialog(this, f.getName() + " üzerine yazılsın mı?",
                    "Dosya var", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        }
        try {
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Kaydedilemedi", JOptionPane.ERROR_MESSAGE);
            return;
        }
        file = f;
        dirty = false;
        updateTitle();
        status.setForeground(Theme.ok());
        status.setText("Kaydedildi: " + f.getName() + "  ·  bu şemayı kullanan bağlantılar yeniden yüklendi.");
        if (onSaved != null) onSaved.accept(EndpointDialog.relative(f));
    }

    private boolean confirmDiscard() {
        if (!dirty) return true;
        int a = JOptionPane.showConfirmDialog(this, "Kaydedilmemiş değişiklikler var. Kaydetmeden devam edilsin mi?",
                "Şema düzenleyici", JOptionPane.OK_CANCEL_OPTION);
        return a == JOptionPane.OK_OPTION;
    }

    private void updateTitle() {
        fileLabel.setText(file == null ? "(yeni, kaydedilmedi)" : EndpointDialog.relative(file));
        setTitle("Şema düzenleyici — " + (file == null ? "yeni" : file.getName()) + (dirty ? " *" : ""));
    }

    // ---------------- bloklar ----------------

    private void reloadBlocks(int select) {
        updating = true;
        blockModel.clear();
        for (Block b : draft.allBlocks()) blockModel.addElement(b);
        updating = false;
        blocks.setSelectedIndex(Math.max(0, Math.min(select, blockModel.size() - 1)));
        showBlock();
        refreshTypeSuggestions();
    }

    private void showBlock() {
        if (updating) return;
        Block b = blocks.getSelectedValue();
        updating = true;
        boolean named = b != null && (b.kind == BlockKind.STRUCT || b.kind == BlockKind.MESSAGE);
        blockName.setEnabled(named);
        blockName.setText(named ? b.name : "");
        boolean msg = b != null && b.kind == BlockKind.MESSAGE;
        blockId.setEnabled(msg);
        idLabel.setEnabled(msg);
        blockId.setText(msg ? b.id : "");
        updating = false;
        fields.fireTableDataChanged();
    }

    private void blockPropsChanged() {
        if (updating) return;
        Block b = blocks.getSelectedValue();
        if (b == null) return;
        if (b.kind == BlockKind.STRUCT || b.kind == BlockKind.MESSAGE) b.name = blockName.getText().trim();
        if (b.kind == BlockKind.MESSAGE) b.id = blockId.getText().trim();
        blocks.repaint();
        refreshTypeSuggestions();
        changed();
    }

    private void addMessage() {
        long max = 0;
        for (Block m : draft.messages) {
            try {
                max = Math.max(max, Long.decode(m.id));
            } catch (NumberFormatException ignored) {
                // gecersiz id; dogrulama gosterir
            }
        }
        Block b = new Block(BlockKind.MESSAGE, uniqueBlockName("MESAJ"));
        b.id = Long.toString(max + 1);
        b.fields.add(new Field("uint16", "deger"));
        draft.messages.add(b);
        reloadBlocks(draft.allBlocks().indexOf(b));
        changed();
        blockName.requestFocusInWindow();
    }

    private void addStruct() {
        Block b = new Block(BlockKind.STRUCT, uniqueBlockName("YAPI"));
        b.fields.add(new Field("uint16", "deger"));
        draft.structs.add(b);
        reloadBlocks(draft.allBlocks().indexOf(b));
        changed();
        blockName.requestFocusInWindow();
    }

    private void duplicateBlock() {
        Block b = blocks.getSelectedValue();
        if (b == null || b.kind == BlockKind.HEADER || b.kind == BlockKind.FOOTER) return;
        Block c = new Block(b.kind, uniqueBlockName(b.name));
        for (Field f : b.fields) c.fields.add(f.copy());
        List<Block> list = b.kind == BlockKind.STRUCT ? draft.structs : draft.messages;
        if (b.kind == BlockKind.MESSAGE) {
            long max = 0;
            for (Block m : draft.messages) {
                try {
                    max = Math.max(max, Long.decode(m.id));
                } catch (NumberFormatException ignored) {
                    // dogrulama gosterir
                }
            }
            c.id = Long.toString(max + 1);
        }
        list.add(list.indexOf(b) + 1, c);
        reloadBlocks(draft.allBlocks().indexOf(c));
        changed();
    }

    private String uniqueBlockName(String base) {
        List<String> names = new ArrayList<>();
        for (Block b : draft.allBlocks()) names.add(b.name);
        if (!names.contains(base)) return base;
        int i = 2;
        while (names.contains(base + i)) i++;
        return base + i;
    }

    private void deleteBlock() {
        Block b = blocks.getSelectedValue();
        if (b == null) return;
        if (b.kind == BlockKind.HEADER || b.kind == BlockKind.FOOTER) {
            JOptionPane.showMessageDialog(this, "Başlık ve son ek silinemez; alanlarını silerek boşaltabilirsiniz.");
            return;
        }
        int idx = blocks.getSelectedIndex();
        (b.kind == BlockKind.STRUCT ? draft.structs : draft.messages).remove(b);
        reloadBlocks(idx);
        changed();
    }

    /** Struct'lar kendi aralarinda, mesajlar kendi aralarinda tasinir (struct kullanildigi yerden once olmali). */
    private void moveBlock(int dir) {
        Block b = blocks.getSelectedValue();
        if (b == null || b.kind == BlockKind.HEADER || b.kind == BlockKind.FOOTER) return;
        List<Block> list = b.kind == BlockKind.STRUCT ? draft.structs : draft.messages;
        int i = list.indexOf(b);
        int j = i + dir;
        if (j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        reloadBlocks(draft.allBlocks().indexOf(b));
        changed();
    }

    private void refreshTypeSuggestions() {
        Object current = typeEditor.getEditor().getItem();
        typeEditor.removeAllItems();
        for (String t : TYPES) typeEditor.addItem(t);
        for (Block s : draft.structs) {
            typeEditor.addItem(s.name);
            typeEditor.addItem(s.name + "[uint8]");
            typeEditor.addItem("list " + s.name);
        }
        typeEditor.getEditor().setItem(current);
    }

    // ---------------- alanlar ----------------

    /** Tablo satiri: ust duzey alan ya da bir bit grubunun alt alani. */
    private static final class Entry {
        final Field field;
        /** Alt alan ise kapsayici bit grubu; ust duzeyde null. */
        final Field parent;

        Entry(Field field, Field parent) {
            this.field = field;
            this.parent = parent;
        }
    }

    private Block block() { return blocks.getSelectedValue(); }

    private List<Field> owner(Entry e) { return e.parent == null ? block().fields : e.parent.bits; }

    private Entry selectedEntry() {
        int r = fieldTable.getSelectedRow();
        return r < 0 || r >= fields.getRowCount() ? null : fields.entry(r);
    }

    private void addField() {
        Block b = block();
        if (b == null) return;
        Entry sel = selectedEntry();
        int at = b.fields.size();
        if (sel != null) at = b.fields.indexOf(sel.parent != null ? sel.parent : sel.field) + 1;
        Field f = new Field("uint16", uniqueName(b.fields, "alan"));
        b.fields.add(at, f);
        fieldsChanged(f);
        startEditingName(f);
    }

    /** Secili bit grubuna (ya da secili alt alanin grubuna) yeni bir bit ekler. */
    private void addBitField() {
        Entry sel = selectedEntry();
        Field group = sel == null ? null : sel.parent != null ? sel.parent : sel.field.isBits() ? sel.field : null;
        if (group == null) {
            JOptionPane.showMessageDialog(this, "Önce bir bit grubu seçin ya da tipi bits8 / bits16 / bits32 olan bir alan ekleyin.",
                    "Bit alanı", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Field f = new Field("bit", uniqueName(group.bits, "bayrak"));
        int at = sel.parent != null ? group.bits.indexOf(sel.field) + 1 : group.bits.size();
        group.bits.add(at, f);
        fieldsChanged(f);
        startEditingName(f);
    }

    private void duplicateField() {
        Entry e = selectedEntry();
        if (e == null) return;
        List<Field> list = owner(e);
        Field f = e.field.copy();
        if (!f.isPad()) f.name = uniqueName(list, f.name);
        list.add(list.indexOf(e.field) + 1, f);
        fieldsChanged(f);
    }

    private void deleteField() {
        Entry e = selectedEntry();
        if (e == null) return;
        int row = fieldTable.getSelectedRow();
        owner(e).remove(e.field);
        fields.fireTableDataChanged();
        if (fields.getRowCount() > 0) {
            int r = Math.min(row, fields.getRowCount() - 1);
            fieldTable.setRowSelectionInterval(r, r);
        }
        changed();
    }

    private void startEditingName(Field f) {
        int r = fields.rowOf(f);
        if (r < 0) return;
        fieldTable.editCellAt(r, f.isPad() ? 1 : 0);
        if (fieldTable.getEditorComponent() != null) fieldTable.getEditorComponent().requestFocusInWindow();
    }

    private static String uniqueName(List<Field> list, String base) {
        List<String> names = new ArrayList<>();
        for (Field f : list) names.add(f.name);
        if (!names.contains(base)) return base;
        int i = 2;
        while (names.contains(base + i)) i++;
        return base + i;
    }

    /** Alan kendi listesi icinde (ust duzey ya da bit grubu) tasinir. */
    private void moveField(int dir) {
        Entry e = selectedEntry();
        if (e == null) return;
        List<Field> list = owner(e);
        int i = list.indexOf(e.field);
        int j = i + dir;
        if (j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        fieldsChanged(e.field);
    }

    private void fieldsChanged(Field select) {
        fields.fireTableDataChanged();
        int r = fields.rowOf(select);
        if (r >= 0) fieldTable.setRowSelectionInterval(r, r);
        changed();
    }

    // ---------------- dogrulama ----------------

    private void changed() {
        dirty = true;
        updateTitle();
        validateDraft();
    }

    private void validateDraft() {
        String text = draft.toText();
        preview.setText(text);
        preview.setCaretPosition(0);
        preview.getHighlighter().removeAllHighlights();
        try {
            Schema s = SchemaParser.parse(text, "şema");
            status.setForeground(Theme.ok());
            status.setText("Geçerli: " + s.messages().size() + " mesaj, " + draft.structs.size() + " struct"
                    + (s.idField() == null ? "  ·  başlıkta @id yok: gelen mesaj boyu tam oturan ilk tanıma eşlenir" : ""));
        } catch (SchemaException e) {
            status.setForeground(Theme.err());
            status.setText("Hata: " + e.getMessage().replaceFirst("^şema:", "satır "));
            Matcher m = ERROR_LINE.matcher(e.getMessage());
            if (m.find()) highlightLine(Integer.parseInt(m.group(1)));
        }
    }

    private void highlightLine(int line) {
        try {
            int start = preview.getLineStartOffset(line - 1);
            int end = preview.getLineEndOffset(line - 1);
            preview.getHighlighter().addHighlight(start, end, new DefaultHighlighter.DefaultHighlightPainter(Theme.tint(Theme.err(), 70)));
            preview.setCaretPosition(start);
        } catch (BadLocationException ignored) {
            // satir yok
        }
    }

    private final class FieldModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Ad", "Tip", "Rol (@)", "Varsayılan", "Enum değerleri (A|B=5)"};

        /** Ust duzey alanlar ve bit gruplarinin altinda alt alanlari, gorunum sirasiyla. */
        private List<Entry> entries() {
            Block b = block();
            if (b == null) return Collections.emptyList();
            List<Entry> out = new ArrayList<>();
            for (Field f : b.fields) {
                out.add(new Entry(f, null));
                if (f.isBits()) for (Field c : f.bits) out.add(new Entry(c, f));
            }
            return out;
        }

        Entry entry(int r) { return entries().get(r); }

        int rowOf(Field f) {
            List<Entry> es = entries();
            for (int i = 0; i < es.size(); i++) if (es.get(i).field == f) return i;
            return -1;
        }

        @Override public int getRowCount() { return entries().size(); }

        @Override public int getColumnCount() { return cols.length; }

        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public boolean isCellEditable(int r, int c) {
            Entry e = entry(r);
            if (e.field.isPad()) return c == 1;
            if (e.parent != null && c == 2) return false; // bit alanlarinda rol yok
            return c != 4 || e.field.isEnum();
        }

        @Override public Object getValueAt(int r, int c) {
            Field f = entry(r).field;
            switch (c) {
                case 0: return f.name;
                case 1: return f.type;
                case 2: return f.role;
                case 3: return f.def;
                default: return f.isEnum() ? f.enums : "";
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Entry e = entry(r);
            Field f = e.field;
            String s = v == null ? "" : v.toString();
            switch (c) {
                case 0: f.name = s.trim(); break;
                case 1:
                    f.type = s.trim();
                    if (f.isEnum() && f.enums.isEmpty()) f.enums = "A|B|C";
                    if (f.isBits() && f.bits.isEmpty()) f.bits.add(new Field("bit", "bayrak"));
                    if (f.isPad()) f.name = "";
                    else if (f.name.isEmpty()) f.name = uniqueName(owner(e), "alan");
                    break;
                case 2: f.role = s.trim().replaceFirst("^@", ""); break;
                case 3: f.def = s; break;
                default: f.enums = s.trim();
            }
            fireTableDataChanged();
            int row = rowOf(f);
            if (row >= 0) fieldTable.setRowSelectionInterval(row, row);
            changed();
        }
    }
}
