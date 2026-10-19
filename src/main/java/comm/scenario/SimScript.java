package comm.scenario;

import comm.core.Endpoint;
import groovy.lang.Script;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Groovy senaryolarinin taban sinifi: buradaki yontemler betikte dogrudan cagrilir.
 * <pre>
 * start "Örnek TCP sunucu"
 * send "Örnek TCP istemci", "KONUM", speed: 120
 * def m = waitFor("Örnek TCP sunucu", "KONUM", 2000)
 * check m.speed == "120", "hız doğru"
 * </pre>
 */
public abstract class SimScript extends Script {
    static final String API = "sim";

    /** Ayni islemlere nesne uzerinden erisim: sim.send(...). */
    public ScenarioRunner.Api getSim() { return (ScenarioRunner.Api) getBinding().getVariable(API); }

    private ScenarioRunner.Api api() { return getSim(); }

    public void log(Object message) { api().log(message); }

    public void start(String endpoint) throws InterruptedException, IOException { api().start(endpoint); }

    public void stop(String endpoint) { api().stop(endpoint); }

    /** Groovy'nin kendi sleep'i yerine: Durdur dugmesine hemen yanit verir. */
    public void sleep(long ms) throws InterruptedException { api().sleep(ms); }

    public void send(String endpoint, String message) throws IOException, InterruptedException {
        api().send(endpoint, message, Collections.<String, Object>emptyMap());
    }

    public void send(String endpoint, String message, Map<?, ?> values) throws IOException, InterruptedException {
        api().send(endpoint, message, values);
    }

    /** Adlandirilmis argumanlarla: send "istemci", "KONUM", speed: 120 */
    public void send(Map<?, ?> values, String endpoint, String message) throws IOException, InterruptedException {
        api().send(endpoint, message, values);
    }

    public void set(String endpoint, String message, Map<?, ?> values) { api().set(endpoint, message, values); }

    public void set(Map<?, ?> values, String endpoint, String message) { api().set(endpoint, message, values); }

    public void sendHex(String endpoint, String hex) throws IOException { api().sendHex(endpoint, hex); }

    public void sendText(String endpoint, String text) throws IOException { api().sendText(endpoint, text); }

    public void sendBytes(String endpoint, byte[] data) throws IOException { api().sendBytes(endpoint, data); }

    public Map<String, String> waitFor(String endpoint, String message) throws InterruptedException, IOException {
        return api().waitFor(endpoint, message, 5000);
    }

    public Map<String, String> waitFor(String endpoint, String message, long timeoutMs) throws InterruptedException, IOException {
        return api().waitFor(endpoint, message, timeoutMs);
    }

    public Map<String, String> last(String endpoint, String message) { return api().last(endpoint, message); }

    public boolean expect(String endpoint, String message, Map<?, ?> want) { return api().expect(endpoint, message, want); }

    public boolean expect(Map<?, ?> want, String endpoint, String message) { return api().expect(endpoint, message, want); }

    public boolean check(boolean ok, String description) { return api().check(ok, description); }

    public boolean check(boolean ok) { return api().check(ok, "kontrol"); }

    public void fail(String message) { api().fail(message); }

    public void fail() { api().fail(""); }

    public void exit(String message) { api().exit(message); }

    public void exit() { api().exit(""); }

    public void modbusRead(String endpoint, int function, int address, int count) throws IOException {
        api().modbusRead(endpoint, function, address, count);
    }

    public void modbusWrite(String endpoint, int address, List<?> values) throws IOException { api().modbusWrite(endpoint, address, values); }

    public void modbusWrite(String endpoint, int address, int value) throws IOException {
        api().modbusWrite(endpoint, address, Collections.singletonList(value));
    }

    public void modbusCoil(String endpoint, int address, boolean on) throws IOException { api().modbusCoil(endpoint, address, on); }

    public void mbset(String endpoint, String table, int address, List<?> values) throws IOException, InterruptedException {
        api().mbset(endpoint, table, address, values);
    }

    public void mbset(String endpoint, String table, int address, int value) throws IOException, InterruptedException {
        api().mbset(endpoint, table, address, Collections.singletonList(value));
    }

    public void snmpGet(String endpoint, String... oids) { api().snmpGet(endpoint, java.util.Arrays.asList(oids)); }

    public void snmpWalk(String endpoint, String oid) { api().snmpWalk(endpoint, oid); }

    public void snmpSet(String endpoint, String oid, String type, Object value) { api().snmpSet(endpoint, oid, type, value); }

    public void trap(String endpoint, String trapOid, String... oids) throws IOException, InterruptedException {
        api().trap(endpoint, trapOid, java.util.Arrays.asList(oids));
    }

    public void dsl(String text) throws ScenarioException, InterruptedException { api().dsl(text); }

    public Map<String, String> getVars() { return api().getVars(); }

    public Endpoint endpoint(String name) { return api().endpoint(name); }
}
