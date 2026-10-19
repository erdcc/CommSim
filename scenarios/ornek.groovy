// Groovy ile örnek senaryo: projects/ornek-proje.json bağlantılarını kullanır.
// Senaryo sekmesinde Dil: Groovy seçip "Aç…" ile yükleyin ya da arayüzsüz çalıştırın:
//   java -jar target/commsim.jar --run projects/ornek-proje.json scenarios/ornek.groovy
// Kullanılabilir yöntemler: Senaryo sekmesi > Komutlar (Dil: Groovy iken).

def sunucu = "Örnek TCP sunucu"
def istemci = "Örnek TCP istemci"

[sunucu, istemci, "Örnek Modbus slave", "Örnek Modbus master"].each { start it }
sleep 300

// --- Hız taraması: her adımda gönder, sunucuda bekle ve doğrula ---
for (hiz in (100..140).step(20)) {
    send istemci, "KONUM", speed: hiz, heading: 45
    def m = waitFor(sunucu, "KONUM", 2000)
    check m.speed == "$hiz", "KONUM hızı $hiz"
}

// --- Koşullu akış: son alınan değere göre karar ---
send istemci, "KOMUT", command: "GOTO", lat: 41.01, lon: 28.97, urgent: true
waitFor sunucu, "KOMUT"
def komut = last(sunucu, "KOMUT")
if (komut.urgent == "true") {
    log "Acil komut alındı: ${komut.command} → (${komut.lat}, ${komut.lon})"
}
expect sunucu, "KOMUT", command: "GOTO", lat: 41.01

// --- Listeler: hedef listesini koddan üret ---
def hedefler = ["FRIEND", "HOSTILE", "NEUTRAL"]
def atama = [tracks: hedefler.size()]
hedefler.eachWithIndex { kimlik, i ->
    atama["tracks[$i].identity"] = kimlik
    atama["tracks[$i].trackId"] = 1000 + i
}
send istemci, "HEDEF_LISTESI", atama
def liste = waitFor(sunucu, "HEDEF_LISTESI", 2000)
check liste["tracks[2].identity"] == "NEUTRAL", "3. hedef NEUTRAL"

// --- Modbus: slave tablosuna yaz, master ile oku ---
mbset "Örnek Modbus slave", "hr", 20, [10, 20, 30]
modbusRead "Örnek Modbus master", 3, 20, 3
waitFor "Örnek Modbus master", "FC03"

// --- Senaryo dili satırları da çalıştırılabilir (değişkenler ortak) ---
vars.sayi = "3"
dsl '''
repeat ${sayi}
  send "Örnek TCP istemci" HEARTBEAT uptime=42
  waitfor "Örnek TCP sunucu" HEARTBEAT
end
'''

log "Bitti: ${sim.checks} kontrol, ${sim.failures} başarısız"
