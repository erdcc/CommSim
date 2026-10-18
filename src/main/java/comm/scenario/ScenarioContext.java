package comm.scenario;

import comm.core.Endpoint;
import comm.core.TrafficBus;

import java.util.Map;

/** Senaryonun simulatorle konustugu arayuz (uygulamada ana pencere saglar). */
public interface ScenarioContext {
    /** @throws IllegalArgumentException bu adla baglanti yoksa */
    Endpoint endpoint(String name);

    /** Gonderilecek degerlere atamalari yazar ve mesaji kodlar. @throws IllegalArgumentException */
    byte[] message(Endpoint ep, String message, Map<String, String> assignments);

    /** Yalnizca gonderilecek degerleri degistirir. @throws IllegalArgumentException */
    void assign(Endpoint ep, String message, Map<String, String> assignments);

    /** Mesajin son alinan degerleri (yol -> metin); hic alinmadiysa null. */
    Map<String, String> lastReceived(Endpoint ep, String message);

    TrafficBus bus();
}
