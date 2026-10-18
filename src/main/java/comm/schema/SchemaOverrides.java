package comm.schema;

/**
 * Bir baglantinin, paylasilan sema dosyasini degistirmeden uyguladigi ayarlar.
 * Bos metin / null "semadaki gibi" demektir.
 */
public final class SchemaOverrides {
    /** "", "BIG" ya da "LITTLE": sonek verilmemis alanlarin byte sirasi. */
    public String byteOrder = "";
    /** true ise byte sirasi alan soneklerini (uint16le gibi) de gecersiz kilar. */
    public boolean forceAllFields;
    /** "" ya da SUM8, XOR8, CRC16, CRC16_CCITT, CRC32: checksum alanlarinin algoritmasi. */
    public String checksum = "";
    /** null ya da checksum'a katilmayan bastaki byte sayisi. */
    public Integer checksumFrom;
    /** "" ya da LENGTH_BODY, LENGTH_ALL, LENGTH_AFTER: uzunluk alanlarinin neyi saydigi. */
    public String length = "";
    /** null ya da uzunluga eklenen duzeltme. */
    public Integer lengthAdjust;

    public boolean isEmpty() {
        return byteOrder.isEmpty() && checksum.isEmpty() && checksumFrom == null && length.isEmpty() && lengthAdjust == null;
    }

    public SchemaOverrides copy() {
        SchemaOverrides o = new SchemaOverrides();
        o.byteOrder = byteOrder;
        o.forceAllFields = forceAllFields;
        o.checksum = checksum;
        o.checksumFrom = checksumFrom;
        o.length = length;
        o.lengthAdjust = lengthAdjust;
        return o;
    }

    /** Liste/ozet metni; bossa "". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!byteOrder.isEmpty()) sb.append(byteOrder.equals("LITTLE") ? "LE" : "BE").append(forceAllFields ? "!" : "");
        if (!checksum.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(checksum.toLowerCase(java.util.Locale.ROOT));
        if (checksumFrom != null) sb.append(sb.length() > 0 ? " " : "").append("cs@").append(checksumFrom);
        if (!length.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append("len:").append(length.substring(7).toLowerCase(java.util.Locale.ROOT));
        if (lengthAdjust != null) sb.append(sb.length() > 0 ? " " : "").append("len").append(lengthAdjust >= 0 ? "+" : "").append(lengthAdjust);
        return sb.toString();
    }
}
