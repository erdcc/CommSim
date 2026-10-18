package comm.ui;

import comm.core.Hex;
import comm.core.LogEntry;

import javax.swing.table.AbstractTableModel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;

/** Trafik kayitlari; tumu bellekte tutulur, filtreye uyanlar gosterilir. Yalnizca EDT'den kullanilir. */
final class TrafficTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    static final int MAX_ROWS = 200_000;
    private static final int PREVIEW_BYTES = 32;
    private static final String[] COLUMNS = {"#", "Zaman", "Bağlantı", "Yön", "Eş", "Boy", "Yanıt (ms)", "Mesaj", "Veri (hex)", "ASCII"};

    static final class Row {
        final long seq;
        final LogEntry entry;
        /** RX cerceve: ayni baglantinin son gonderiminden bu yana gecen ms; yoksa -1. */
        long latency = -1;

        Row(long seq, LogEntry entry) {
            this.seq = seq;
            this.entry = entry;
        }
    }

    private final List<Row> all = new ArrayList<>();
    private final List<Row> view = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private Predicate<LogEntry> filter = e -> true;
    private long nextSeq = 1;
    private final java.util.Map<comm.core.Endpoint, Long> lastTx = new java.util.IdentityHashMap<>();
    private static final long LATENCY_WINDOW_MS = 60_000;

    void append(List<LogEntry> batch) {
        if (batch.isEmpty()) return;
        int first = view.size();
        for (LogEntry e : batch) {
            Row r = new Row(nextSeq++, e);
            if (e.isFrame()) {
                if (e.dir == comm.core.Direction.TX) {
                    lastTx.put(e.endpoint, e.time);
                } else if (e.dir == comm.core.Direction.RX) {
                    Long t = lastTx.get(e.endpoint);
                    if (t != null && e.time - t <= LATENCY_WINDOW_MS) r.latency = e.time - t;
                }
            }
            all.add(r);
            if (filter.test(e)) view.add(r);
        }
        if (all.size() > MAX_ROWS) {
            all.subList(0, all.size() - MAX_ROWS + MAX_ROWS / 10).clear();
            rebuild();
        } else if (view.size() > first) {
            fireTableRowsInserted(first, view.size() - 1);
        }
    }

    void setFilter(Predicate<LogEntry> f) {
        filter = f;
        rebuild();
    }

    void clear() {
        lastTx.clear();
        all.clear();
        view.clear();
        fireTableDataChanged();
    }

    private void rebuild() {
        view.clear();
        for (Row r : all) {
            if (filter.test(r.entry)) view.add(r);
        }
        fireTableDataChanged();
    }

    Row row(int viewRow) { return view.get(viewRow); }

    List<Row> visibleRows() { return new ArrayList<>(view); }

    int totalCount() { return all.size(); }

    String formatTime(long t) { return timeFmt.format(new Date(t)); }

    @Override public int getRowCount() { return view.size(); }

    @Override public int getColumnCount() { return COLUMNS.length; }

    @Override public String getColumnName(int c) { return COLUMNS[c]; }

    @Override public Class<?> getColumnClass(int c) {
        return c == 0 || c == 5 || c == 6 ? Long.class : String.class;
    }

    @Override public Object getValueAt(int rowIndex, int c) {
        Row r = view.get(rowIndex);
        LogEntry e = r.entry;
        switch (c) {
            case 0: return r.seq;
            case 1: return formatTime(e.time);
            case 2: return e.endpoint.name();
            case 3: return e.dir.symbol;
            case 4: return e.peer;
            case 5: return e.isFrame() ? Long.valueOf(e.data.length) : null;
            case 6: return r.latency >= 0 ? Long.valueOf(r.latency) : null;
            case 7: return e.decoded == null ? "" : e.decoded.label();
            case 8: return e.isFrame() ? Hex.preview(e.data, PREVIEW_BYTES) : e.text;
            case 9: return e.isFrame() ? Hex.ascii(e.data, 0, Math.min(e.data.length, PREVIEW_BYTES)) : "";
            default: return "";
        }
    }
}
