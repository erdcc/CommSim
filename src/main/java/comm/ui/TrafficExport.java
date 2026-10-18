package comm.ui;

import comm.core.Hex;
import comm.core.LogEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Trafik kayitlarini CSV (Excel uyumlu, ';' ayracli, BOM'lu) ya da ayrintili metin olarak yazar. */
final class TrafficExport {
    private TrafficExport() { }

    static String csv(List<LogEntry> rows) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Sıra;Zaman;Bağlantı;Yön;Eş;Boy;Mesaj;Hex;Metin / uyarı\r\n");
        int i = 0;
        for (LogEntry e : rows) {
            String note = e.text;
            if (e.decoded != null) {
                StringBuilder n = new StringBuilder();
                if (e.decoded.error != null) n.append(e.decoded.error);
                for (String w : e.decoded.warnings) n.append(n.length() > 0 ? " | " : "").append(w);
                note = n.toString();
            }
            sb.append(++i).append(';')
                    .append(fmt.format(new Date(e.time))).append(';')
                    .append(cell(e.endpoint.name())).append(';')
                    .append(e.dir.name()).append(';')
                    .append(cell(e.peer)).append(';')
                    .append(e.isFrame() ? String.valueOf(e.data.length) : "").append(';')
                    .append(cell(e.decoded == null ? "" : e.decoded.label())).append(';')
                    .append(e.isFrame() ? Hex.toHex(e.data) : "").append(';')
                    .append(cell(note == null ? "" : note)).append("\r\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        if (s.indexOf(';') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    static String text(List<LogEntry> rows) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : rows) {
            sb.append(TrafficPanel.describe(e)).append("\n").append("------------------------------------------------------------\n");
        }
        return sb.toString();
    }
}
