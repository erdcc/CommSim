package comm.ui;

import comm.runtime.ParameterStore;

import comm.core.Hex;
import comm.schema.Codec;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.Values;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Semadaki bir mesaji formla doldurup gonderme. Degerler ParameterStore'da tutulur. */
final class MessagePanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final JComboBox<MessageDef> msgBox = new JComboBox<>();
    private final FieldTable table;
    private final JLabel preview = new JLabel(" ");
    private final JButton sendButton = UiKit.primary("Gönder", Icons.SEND);
    private final JButton copyRxButton = UiKit.button("Son alınanı kopyala", Icons.COPY);
    private final CardLayout cards = new CardLayout();
    private final Consumer<byte[]> sender;
    private ParameterStore store;
    private FieldTable.Generators generators;

    /** Ureteç sutunu yalnizca Gelismis gorunumde. */
    void modeChanged() {
        table.setGenerators(UiMode.advanced() ? generators : null);
        table.reload();
    }
    private long seenVersion = -1;
    private boolean canSend;
    private boolean updatingBox;

    /** @param sender kodlanmis cerceveyi gonderir */
    MessagePanel(Consumer<byte[]> sender) {
        super();
        this.sender = sender;
        setLayout(cards);
        table = new FieldTable(this::rows, this::edited, this::showError);
        generators = new FieldTable.Generators() {
            @Override public String get(Values.Row row) {
                return store == null ? "" : store.generator(selected(), row.path);
            }

            @Override public void set(Values.Row row, String text) {
                if (store != null) store.setGenerator(selected(), row.path, text);
            }
        };
        modeChanged();
        table.setToolTipText("Üreteç: her gönderimde yeniden hesaplanır — counter(0,1), random(10,20), sine(0,100,5000), list(A|B|C), now()");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        top.add(new JLabel("Mesaj:"));
        msgBox.setMaximumRowCount(25);
        top.add(msgBox);
        JButton defaults = UiKit.button("Varsayılana dön", Icons.RELOAD);
        top.add(defaults);
        top.add(copyRxButton);

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        preview.setFont(Theme.mono(12));
        bottom.add(preview, BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JPanel form = new JPanel(new BorderLayout());
        form.add(top, BorderLayout.NORTH);
        form.add(new JScrollPane(table), BorderLayout.CENTER);
        form.add(bottom, BorderLayout.SOUTH);

        JLabel none = new JLabel("<html><center>Bu bağlantıya mesaj şeması atanmadı.<br>"
                + "Bağlantıyı düzenleyip bir .def dosyası seçin (örnek: schemas/ornek.def).</center></html>",
                SwingConstants.CENTER);
        none.setForeground(Theme.muted());
        add(form, "form");
        add(none, "none");

        msgBox.addActionListener(e -> {
            if (!updatingBox) reloadTable();
        });
        defaults.addActionListener(e -> {
            if (store != null) {
                store.resetDefaults(selected());
                reloadTable();
            }
        });
        copyRxButton.addActionListener(e -> {
            if (store != null && store.copyLastRx(selected())) reloadTable();
            else showError("Bu mesaj henüz alınmadı.");
        });
        sendButton.addActionListener(e -> send());
        setStore(null);
    }

    void setStore(ParameterStore s) {
        store = s;
        Schema schema = s == null ? null : s.schema();
        MessageDef prev = selected();
        updatingBox = true;
        DefaultComboBoxModel<MessageDef> m = new DefaultComboBoxModel<>();
        if (schema != null) for (MessageDef d : schema.messages()) m.addElement(d);
        msgBox.setModel(m);
        if (prev != null && schema != null && schema.byName(prev.name) != null) msgBox.setSelectedItem(schema.byName(prev.name));
        updatingBox = false;
        cards.show(this, schema == null ? "none" : "form");
        reloadTable();
    }

    void setCanSend(boolean on) {
        canSend = on;
        updatePreview();
    }

    void select(MessageDef m) {
        msgBox.setSelectedItem(m);
    }

    /** Depo baska yerden degistiyse (Parametreler paneli, gelen mesaj) yeniler. */
    void refreshIfChanged() {
        if (store != null && store.version() != seenVersion) {
            table.reload();
            updatePreview();
        }
    }

    /**
     * Periyodik gonderim icin: secili mesaji her cagrida guncel degerlerle kodlar (EDT'de cagrilmali).
     * Sema yoksa null.
     */
    java.util.function.Supplier<byte[]> payloadSupplier() {
        MessageDef m = selected();
        ParameterStore st = store;
        if (m == null || st == null || st.schema() == null) return null;
        String name = m.name;
        return () -> {
            MessageDef cur = st.schema() == null ? null : st.schema().byName(name);
            if (cur == null) throw new IllegalStateException(name + " şemada yok");
            return st.encode(cur);
        };
    }

    String selectedName() {
        MessageDef m = selected();
        return m == null ? "" : m.name;
    }

    private MessageDef selected() { return (MessageDef) msgBox.getSelectedItem(); }

    private List<Values.Row> rows() {
        List<Values.Row> out = new ArrayList<>();
        MessageDef m = selected();
        if (store == null || store.schema() == null || m == null) return out;
        out.addAll(Values.flatten(store.schema().header, store.txHeader(), "başlık.", true));
        out.addAll(Values.flatten(m.body, store.state(m).txBody, "", true));
        return out;
    }

    private void reloadTable() {
        table.reload();
        updatePreview();
    }

    private void edited() {
        if (store != null) store.changed();
        updatePreview();
    }

    private byte[] encode() {
        MessageDef m = selected();
        return store.encode(m);
    }

    private void updatePreview() {
        if (store != null) seenVersion = store.version();
        MessageDef m = selected();
        boolean ready = store != null && store.schema() != null && m != null;
        ParameterStore.MsgState st = ready ? store.state(m) : null;
        copyRxButton.setEnabled(st != null && st.lastRx != null);
        if (!ready) {
            preview.setText(" ");
            sendButton.setEnabled(false);
            return;
        }
        try {
            byte[] b = store.preview(m);
            int gens = store.generatorCount(m);
            preview.setForeground(Theme.muted());
            preview.setText(b.length + " byte:  " + Hex.preview(b, 40) + (gens > 0 ? "   ·  " + gens + " üreteç her gönderimde çalışır" : ""));
            sendButton.setEnabled(canSend);
        } catch (RuntimeException e) {
            showError(e.getMessage());
            sendButton.setEnabled(false);
        }
    }

    private void showError(String msg) {
        preview.setForeground(Theme.err());
        preview.setText(msg);
    }

    private void send() {
        if (store == null || selected() == null) return;
        byte[] b;
        try {
            b = encode();
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return;
        }
        sender.accept(b);
    }
}
