package comm.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arayuzun ekran gerektirmeyen mantigi: komut paleti aramasi. */
class UiLogicTest {
    private static CommandPalette.Command cmd(String group, String title) {
        return new CommandPalette.Command(group, title, null, null, () -> { });
    }

    @Test void paletteMatchesWordsIgnoringTurkishLetters() {
        List<CommandPalette.Command> all = Arrays.asList(
                cmd("Bağlantı", "Başlat"),
                cmd("Mesaj", "Gönder: KONUM"),
                cmd("Mesaj", "Forma aç: KONUM"),
                cmd("Mesaj", "Gönder: KOMUT"),
                cmd("Tema", "Koyu"));
        List<CommandPalette.Command> r = CommandPalette.filter(all, "gonder kon");
        assertEquals(1, r.size());
        assertEquals("Gönder: KONUM", r.get(0).title);
        // baslik basinda gecen eslesme once gelir
        assertEquals(2, CommandPalette.filter(all, "kon").size());
        assertTrue(CommandPalette.filter(all, "BAŞLAT").get(0).title.equals("Başlat"));
        assertEquals("Koyu", CommandPalette.filter(all, "koyu").get(0).title);
        assertEquals(5, CommandPalette.filter(all, "  ").size());
        assertEquals(0, CommandPalette.filter(all, "yok-boyle").size());
        // grup adi da aranir
        assertEquals(3, CommandPalette.filter(all, "mesaj").size());
    }

    @Test void foldHandlesDottedAndDotlessI() {
        assertEquals("istatistik", CommandPalette.fold("İstatistik"));
        assertEquals("isik", CommandPalette.fold("IŞIK"));
        assertEquals("gonder", CommandPalette.fold("GÖNDER"));
    }
}
