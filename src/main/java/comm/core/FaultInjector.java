package comm.core;

import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.FieldDef;
import comm.schema.Schema;
import comm.schema.StructDef;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** FaultConfig'e gore cerceveyi bozar, cogaltir, geciktirir ya da dusurur; yapilani loga yazar. */
final class FaultInjector {
    private static final Random RANDOM = new Random();
    private static final ScheduledExecutorService DELAYER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fault-delay");
        t.setDaemon(true);
        return t;
    });

    private FaultInjector() { }

    private static boolean roll(int pct) { return pct > 0 && RANDOM.nextInt(100) < pct; }

    static void send(Endpoint ep, FaultConfig f, byte[] payload, String peer) throws IOException {
        if (roll(f.dropPct)) {
            ep.report(peer, "⚡ Hata enjeksiyonu: çerçeve düşürüldü (" + payload.length + " byte)", false);
            return;
        }
        List<String> notes = new ArrayList<>();
        byte[] data = payload.clone();
        Schema s = ep.schema();
        if (s != null && roll(f.checksumPct)) {
            if (corruptRole(s, data, true)) notes.add("checksum bozuldu");
        }
        if (s != null && roll(f.lengthPct)) {
            if (corruptRole(s, data, false)) notes.add("uzunluk alanı kaydırıldı");
        }
        if (data.length > 0 && roll(f.flipPct)) {
            int i = RANDOM.nextInt(data.length);
            byte old = data[i];
            data[i] = (byte) (old ^ (1 + RANDOM.nextInt(255)));
            notes.add(String.format("byte %d değişti %02X→%02X", i, old & 0xFF, data[i] & 0xFF));
        }
        if (data.length > 1 && roll(f.truncatePct)) {
            int cut = 1 + RANDOM.nextInt(Math.max(1, data.length / 2));
            data = Arrays.copyOf(data, data.length - cut);
            notes.add(cut + " byte kesildi");
        }
        int copies = roll(f.duplicatePct) ? 2 : 1;
        if (copies == 2) notes.add("iki kez gönderilecek");
        long delay = Math.max(0, f.delayMs) + (f.jitterMs > 0 ? RANDOM.nextInt(f.jitterMs + 1) : 0);
        if (delay > 0) notes.add(delay + " ms gecikme");
        if (!notes.isEmpty()) ep.report(peer, "⚡ Hata enjeksiyonu: " + String.join(", ", notes), false);
        byte[] out = data;
        if (delay <= 0) {
            for (int i = 0; i < copies; i++) ep.sendDirect(out, peer);
            return;
        }
        DELAYER.schedule(() -> {
            try {
                for (int i = 0; i < copies; i++) ep.sendDirect(out, peer);
            } catch (IOException | RuntimeException e) {
                ep.report(peer, "Gecikmeli gönderim başarısız: " + (e.getMessage() == null ? e : e.getMessage()), true);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Sema baslik/son ekindeki ilk checksum (ya da uzunluk) alaninin byte'larini bozar. */
    private static boolean corruptRole(Schema s, byte[] data, boolean checksum) {
        Decoded d = Codec.decode(s, data);
        for (Object[] scope : new Object[][]{{s.header, "başlık."}, {s.footer, "son ek."}}) {
            StructDef st = (StructDef) scope[0];
            for (FieldDef f : st.fields) {
                boolean hit = checksum ? f.role.isChecksum() : f.role.isLength();
                if (!hit) continue;
                String path = scope[1] + f.name;
                for (Decoded.Span sp : d.spans) {
                    if (!sp.path.equals(path) || sp.offset + sp.length > data.length) continue;
                    if (checksum) {
                        data[sp.offset] ^= 0xFF;
                    } else {
                        ByteOrder o = f.order != null ? f.order : s.order;
                        int lsb = o == ByteOrder.BIG_ENDIAN ? sp.offset + sp.length - 1 : sp.offset;
                        data[lsb] ^= 0x01;
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
