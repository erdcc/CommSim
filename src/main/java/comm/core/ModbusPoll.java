package comm.core;

/** Modbus master'in periyodik okumasi. Alanlar arayuzden degisebilir; okuyucu thread'i anlik degeri kullanir. */
public final class ModbusPoll {
    /** 1: coil, 2: discrete input, 3: holding, 4: input register. */
    public volatile int function = 3;
    public volatile int address = 0;
    public volatile int count = 10;
    public volatile int intervalMs = 1000;
    public volatile boolean enabled = true;

    public ModbusPoll copy() {
        ModbusPoll p = new ModbusPoll();
        p.function = function;
        p.address = address;
        p.count = count;
        p.intervalMs = intervalMs;
        p.enabled = enabled;
        return p;
    }
}
