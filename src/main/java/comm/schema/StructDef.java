package comm.schema;

import java.util.ArrayList;
import java.util.List;

/** Alan listesi: struct, baslik ve son ek ortak olarak bunu kullanir. */
public final class StructDef {
    public final String name;
    public final List<FieldDef> fields = new ArrayList<>();

    public StructDef(String name) { this.name = name; }

    public FieldDef field(String n) {
        for (FieldDef f : fields) if (f.name.equals(n)) return f;
        return null;
    }

    /** Tum alanlar sabit boyluysa toplam boy, degilse -1. */
    public int fixedSize() {
        int sum = 0;
        for (FieldDef f : fields) {
            int s = f.fixedSize();
            if (s < 0) return -1;
            sum += s;
        }
        return sum;
    }
}
