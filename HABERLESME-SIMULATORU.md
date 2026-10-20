# Haberleşme Simülatörü

TCP, UDP, Modbus TCP ve SNMP için genel amaçlı bir test aracı. Her bağlantı sunucu ya da
istemci olarak çalışır. Mesajları elle gönderir, trafiği bayt düzeyinde izler. Periyodik
gönderim ve senaryolarla tekrarlanabilir testler kurmanızı sağlar. Java 8 uyumlu bir
Swing uygulamasıdır.

Kod `src/main/java/comm` altındadır. Eski harita/sim kodu (`src/sim`, `run.sh`, `server.sh`)
bu derlemeye dahil değildir ve değiştirilmedi.

## Derleme ve çalıştırma

```bash
mvn -q package                 # testlerle birlikte; atlamak için -DskipTests
./commsim.sh                   # derler ve çalıştırır
java -jar target/commsim.jar projects/ornek-proje.json   # bir projeyle açar
```

- `target/commsim.jar` tüm bağımlılıkları içerir: SNMP4J 2.8.18, Jackson 2.17, FlatLaf 3.5
  (görünüm), JetBrains Mono (hex görünümü) Groovy 4.0 (Groovy senaryoları) ve RSyntaxTextArea/AutoComplete (senaryo editörü). Jar yaklaşık 14 MB'tır.
- Bytecode Java 8 içindir (`release=8`, sınıf sürümü 52); tüm bağımlılıklar da Java 8 uyumludur.
  Derleme JDK 21 ile yapılır. **Java 8 üzerinde doğrulandı** (Azul Zulu 8u504): 80 testin hepsi ve
  örnek senaryolar Java 8 ile geçer, arayüz Java 8'de açılıp kontrol edildi. Denetimi tekrarlamak için:

  ```bash
  ./check-java8.sh               # testleri ve örnek senaryoları Java 8 ile çalıştırır
  ```

  Java 8 yolu `JAVA8_HOME` ile verilir; verilmezse macOS'ta `/usr/libexec/java_home -v 1.8` kullanılır.
- **Java 8'de Retina (macOS):** Java 8, Apple Silicon Mac'lerde ekran ölçeğini yanlış (1) okuduğu
  için uygulamayı yarım çözünürlükte çizer ve yazılar bulanık görünür. Uygulama Java 8'de açılışta
  her ekranın gerçek ölçeğini macOS'tan alıp Java 8'e verir; Retina ekranda yazılar nettir, Retina
  olmayan monitörde ölçek 1 kalır. `-Dcommsim.retinaScale=2` elle ölçek verir, `0` kapatır. Java 9 ve
  üstünde bu düzeltmeye gerek yoktur.
- **Java 8'de yazı tipi:** Java 8, sistem yazı tipinde olmayan işaretleri (↓ ⚠ ✓ …) çizerken
  macOS'ta aynı satırdaki sonraki harfleri kaybediyor, Windows'ta kutucuk gösterebiliyor. Bu yüzden
  Java 8'de arayüz yazı tipi olarak jar'a gömülü **DejaVu Sans** kullanılır (tüm işaretleri içerir);
  Java 9 ve üstünde sistem yazı tipi kalır. `-Dcommsim.bundledFont=true|false` ile değiştirilebilir.
- macOS'ta `/usr/bin/java` bir yer tutucudur. `commsim.sh`, Homebrew'daki `openjdk@21`'i kullanır.

## Ekran düzeni

| Alan | İçerik |
|---|---|
| Sol | Bağlantı listesi. Renk durumu gösterir: yeşil çalışıyor, sarı başlıyor, kırmızı hata. Çift tıklama başlatır ya da durdurur. |
| Sağ üst | Seçili bağlantının sekmeleri: **Mesaj / Ham / Parametreler** (TCP, UDP), **Modbus / Ham**, **SNMP / Ham**. Hedef eş seçimi ve periyodik gönderim de buradadır. |
| Sağ alt | **Trafik** (log, filtre, alan tablosu ile renkli hex görünümü), **Senaryo**, **Grafik**, **İstatistik**. |
| Menü | Dosya: yeni proje, aç, son projeler, kaydet, trafiği CSV ya da metin olarak dışa aktar. Yardım: senaryo komutları. |
| Sağ alt köşe | Bildirimler: bağlantı hataları, gönderim hataları, kaydetme/açma/dışa aktarma sonuçları. Birkaç saniye sonra kaybolur, tıklayınca kapanır. Aynı bağlantıdan art arda gelen hatalar tek bildirimde toplanır. |

### Basit ve Gelişmiş görünüm

Araç çubuğunun sağındaki **Basit | Gelişmiş** anahtarı (ya da Görünüm menüsü) ile seçilir;
seçim hatırlanır, varsayılan Basit'tir. Basit görünüm bağlantı kuran, mesaj gönderen ve izleyen
kullanıcı içindir; ileri özellikler gizlenir ama **ayarları silinmez, çalışmaya devam eder**.

| Basit'te gizlenen | Gelişmiş'te |
|---|---|
| Kurallar sekmesi | otomatik yanıt kuralları |
| Mesaj/Parametreler'deki "Üreteç" sütunu | değer üreteçleri |
| Modbus slave / SNMP agent "Canlı değerler" | periyodik değişen değerler |
| Senaryo ve Tekrar oynat alt sekmeleri, Yardım > Senaryo komutları | senaryolar, kayıt oynatma |
| Trafik logunda "Yanıt (ms)" ve ASCII sütunları, "Karşılaştır" | ayrıntılı inceleme |
| Dosya > "Trafiği kayıt olarak dışa aktar (.rec)" | kayıt alma |
| Bağlantı formunda şema geçersiz kılma, hata enjeksiyonu, WebSocket alt protokol/ping, Modbus yanıt gecikmesi | ince ayarlar |

Bağlantı formundaki **"Gelişmiş ayarları göster"** kutusu bu bölümleri tek tek açar; bağlantı
bunlardan birini kullanıyorsa kutu kendiliğinden açık gelir. Gizli ama etkin özellikler bağlantı
kartında görünür kalır: `↩ 1 kural`, `⚡ hata enjeksiyonu`.

### Görünüm ve kısayollar

- **Tema:** Açık, koyu ya da sistem (macOS ayarını izler). Görünüm menüsünden ya da araç
  çubuğunun sağındaki düğmeden değiştirilir; seçim hatırlanır.
- **Bağlantı kartları:** durum noktası, ad, protokol rozeti, adres ve canlı ↓RX/↑TX sayaçları.
  Trafik geldiği ya da gittiği anda ilgili sayaç kısa süre renklenir. Sağ tık menüsünden
  başlat/durdur, düzenle, kopyala, şemayı düzenle ve sil seçilebilir.
- **Komut paleti (⌘K):** araç çubuğundaki **Komutlar** düğmesi de açar. Yazarak her eyleme,
  bağlantıya, mesaja ve hazır mesaja ulaşılır: `gönder konum` KONUM mesajını gönderir,
  `başlat` seçili bağlantıyı başlatır, `koyu` temayı değiştirir. Türkçe harfler olmadan da
  yazılabilir (`gonder` = `gönder`).
- **Proje açma:** Dosya > Son projeler listesi son 8 projeyi tutar. Bir `.json` proje dosyasını
  pencereye sürükleyip bırakmak da açar.
- **Kısayollar:**

| Tuş | İşlem |
|---|---|
| ⌘T | Yeni bağlantı |
| ⌘E / ⏎ | Seçili bağlantıyı düzenle |
| ⌘D | Kopyala |
| ⌫ | Sil |
| ⌘R | Başlat |
| ⌘. | Durdur |
| Boşluk | Başlat/durdur |
| ⌘⏎ | Açık sekmedeki mesajı gönder (Mesaj, Parametreler ya da Ham) |
| ⌘⇧R | Tüm bağlantıları başlat |
| ⌘1 … ⌘9 | Listedeki 1.–9. bağlantıya geç |
| ⌘K | Komut paleti |
| ⌘F | Trafikte ara |
| ⌘L | Trafiği temizle |
| ⌘N, ⌘O, ⌘S | Yeni proje, aç, kaydet |

İlk açılışta sekiz örnek bağlantı gelir: TCP sunucu/istemci (`schemas/ornek.def` şemalı),
Modbus slave/master ve SNMP agent/manager (`oids/ornek.oid`). Örnek portlar 5050, 5020,
1161 ve 1162'dir.

> macOS'ta 5000 numaralı port AirPlay Alıcısı'na aittir. 7000 de aynıdır. Bu portları kullanmayın.

## Bağlantı türleri

| Tür | Not |
|---|---|
| TCP Sunucu / İstemci | Çerçeveleme seçenekleri: ham, uzunluk önekli (1/2/4 byte, BE/LE, başlık dahil/hariç), ayraçlı ya da sabit uzunluk. İstemci kopunca otomatik yeniden bağlanabilir. Gelişmiş ayarlarda **no delay** (varsayılan açık: küçük paketler beklemeden gider) ve **keep-alive** seçilebilir; bu ikisi Modbus ve WebSocket bağlantılarında da vardır. |
| UDP | Unicast, broadcast ve multicast (grup, ağ arayüzü, TTL, loopback). Her datagram bir çerçevedir. Gruba göndermek için hedef adrese grup adresi yazılır. Loopback açıkken (varsayılan) gönderilen multicast paketi aynı makinedeki dinleyicilere de ulaşır. |
| Modbus TCP Slave | FC 1, 2, 3, 4, 5, 6, 15, 16 ve 23 desteklenir. Standart istisnalar (01, 02, 03) döner. Unit id filtresi ve yanıt gecikmesi ayarlanabilir. |
| Modbus TCP Master | İstek oluşturucu, periyodik okumalar, zaman aşımı ve yanıt süresi. Okunan değerler tabloya yansır; tablodaki bir değeri değiştirmek yazma isteği gönderir. |
| SNMP Agent | v1, v2c ve v3 (USM) isteklerine yanıt verir: GET, GETNEXT, GETBULK, SET. Community denetimi yapar, trap gönderir. |
| WebSocket Sunucu / İstemci | RFC 6455: el sıkışma, istemci maskeleme, parçalı mesajlar, ping/pong, kapanış. Yol, alt protokol, metin/binary ve ping aralığı ayarlanabilir; istemci yeniden bağlanabilir. Her WebSocket mesajı bir çerçevedir ve şema ile çözülür. TLS (`wss://`) yoktur. |
| SNMP Manager | GET, GETNEXT, GETBULK, WALK ve SET. Trap ve inform alır, inform'lara yanıt verir. v3 hata raporlarını okunur hale getirir (yanlış parola, bilinmeyen kullanıcı vb.). |

## Mesaj şeması (TCP / UDP)

Söz dizimi açıklamalı olarak [schemas/ornek.def](schemas/ornek.def) dosyasındadır. Eski
`messages.def` dosyası değişmeden okunur ve aynı byte'ları üretir.

- **Başlık ve son ek:** `header` ve `footer` blokları. `@id` alanı mesaj tipini seçer.
- **Otomatik alanlar:** uzunluk (`@length`, `@length(all)`, `@length(after)`), adet ve
  checksum (`@sum8`, `@xor8`, `@crc16` Modbus, `@crc16ccitt`, `@crc32`). Kodlarken
  otomatik doldurulur, gelen mesajda doğrulanır.
- **Tipler:** 8–64 bit işaretli/işaretsiz tam sayılar, float32/64 ve bool. Alan bazında
  byte sırası `uint16le` biçiminde verilir.
- **Uzunluk biçimleri:** string, bytes, dizi ve listeler `[16]`, `[uint8]`, `[sayac]` ya da
  `[*]` (mesajın sonuna kadar) alabilir. Ayrıca `cstring` ve iç içe struct'lar desteklenir.
- **Enum:** açık değerler ve taban tipi verilebilir: `enum:uint16 komut GOTO=1|HOLD=2`.
- **Bit grupları:** `bits8`, `bits16`, `bits32` ve `bits64`. Alt alanlar grup satırından daha
  içeriden yazılır: `bit`, `uintN`/`intN`, `enumN` ve boşluk için `padN`. İlk alt alan bit 0'dan
  (LSB) başlar; `bits8(msb)` en yüksek bitten başlatır. `bits16le` byte sırasını belirler.
  Bayraklar Mesaj ve Parametreler tablolarında onay kutusudur. Senaryoda `bayraklar.alarm=1`
  biçiminde atanır, trafik detayında her alt alan bit konumuyla görünür (`[bit 2-4]`).
- **Değer düzenleme:** Mesaj ve Parametreler tablolarında enum alanları açılır listeden seçilir
  (listede sayısal karşılıkları da görünür). Tam sayılar her zaman ondalık gösterilir; giriş
  olarak `0x10` gibi hex de yazılabilir. Hücrenin ipucunda geçerli aralık yazar. Geçersiz değer
  kırmızı çerçeveyle işaretlenir ve kabul edilmez. Yalnızca checksum alanları hex gösterilir.
- **Rol parametreleri:** `@crc16(from=1)` checksum'a baştaki byte'ları katmaz;
  `@length(all,adjust=-2)` hesaplanan uzunluğa düzeltme ekler.
- **Yeniden yükleme:** dosyayı değiştirip araç çubuğundaki **Şemaları yeniden yükle**
  düğmesine basmanız yeterli.

### Bağlantı bazında şema ayarları

TCP/UDP bağlantı formundaki "Bu bağlantı için şema ayarları" bölümü, `.def` dosyasına
dokunmadan yalnızca o bağlantıda şunları değiştirir:

| Ayar | Seçenekler |
|---|---|
| Byte sırası | Şemadaki / big / little. İsteğe bağlı olarak `uint16le` gibi alan soneklerini de ezer. |
| Checksum algoritması | Şemadaki / SUM8 / XOR8 / CRC16 / CRC16_CCITT / CRC32 |
| Checksum başlangıcı | Hesaba katılmayan baştaki byte sayısı |
| Uzunluk alanı | Neyi saydığı (gövde / tüm çerçeve / alandan sonrası) ve ± düzeltme |

Bu ayarlar şemadaki checksum ve uzunluk alanlarının yalnızca davranışını değiştirir;
alan ekleyip silemez. Ayarlar listede ve proje dosyasında görünür. Aynı şemayı kullanan iki
bağlantıdan birini farklı ayarlarla çalıştırırsan, karşı taraf uyuşmazlığı trafik logunda
uyarı olarak gösterir.

### Görsel şema düzenleyici

Araç çubuğu ya da Dosya > Şema düzenleyici ile açılır; seçili bağlantının şemasını, yoksa
yeni bir şemayı getirir.

- Solda bloklar: başlık, son ek, struct'lar ve mesajlar. Ekleme, silme, kopyalama ve sıralama yapılabilir.
- Ortada alan tablosu: ad, tip, rol, varsayılan ve enum değerleri. Tip ve rol hücreleri öneri
  listelidir; `HEDEF[count]` ya da `crc16(from=2)` gibi serbest ifadeler de yazılabilir.
- Altta üretilen `.def` metni her değişiklikte doğrulanır, hatalı satır işaretlenir.
  Geçersiz şema kaydedilmez.
- Kaydedince bu şemayı kullanan bağlantılar otomatik yeniden yüklenir.
- **Dosyadaki yorumlar korunmaz.** Açıklamalı dosyalar için "Farklı kaydet" kullanın.
- Ayrıştırılamayan bir dosya düzenleyicide açılmaz; önce metin olarak düzeltilmelidir.

**Mesaj** sekmesi seçili mesajı tabloda düzenletir ve kodlanmış byte'ları canlı gösterir.
**Parametreler** sekmesi tüm mesajlar için gönderilecek, son alınan ve son gönderilen
değerleri yan yana gösterir. Başlık değerleri tüm mesajlarda ortaktır.

## Otomatik yanıt kuralları

TCP, UDP ve WebSocket bağlantılarında **Kurallar** sekmesinden tanımlanır. Gelen her çerçeve
için kurallar sırayla denenir; ilk eşleşen kural yanıtı (isteğe bağlı gecikmeyle) gönderen
eşe yollar. Sekmedeki "Tetiklendi" sütunu kuralın kaç kez çalıştığını gösterir.

| Alan | Örnek |
|---|---|
| Gelen | `KOMUT` (şema mesajı), `*` (her çerçeve), `hex:50 49 4E 47` (bu byte'ları içeren) |
| Koşul | `command=GOTO; lat>40` (`= != > < >= <=`) |
| Yanıt | `HEARTBEAT`, `hex:A5 01`, `text:OK\r\n`; boş bırakılırsa yalnızca sayılır |
| Atamalar | `başlık.seq=$rx.başlık.seq + 1; status=OK; uptime=counter(0,1)` |

Yanıt mesajı şema varsayılanlarıyla başlar, atamalar üzerine yazılır. `$rx.alan` gelen
mesajdaki değeri verir.

## Üreteçler ve canlı değerler

**Ifade dili** (üreteçler, kural atamaları ve senaryo değişkenleri ortak kullanır):
`counter(başlangıç, adım[, en büyük])`, `random(a, b)`, `randomf(a, b)`, `sine(alt, üst, periyot_ms)`,
`ramp(...)`, `square(...)`, `list(A|B|C)`, `pick(A|B|C)`, `toggle()`, `now()`, `+ - * / %` ve
parantez. İfade olarak çözülemeyen metin düz değer sayılır; işlem içeren düz metin için
tırnak kullanın (`"2024-01-01"`).

- **Mesaj üreteçleri:** Mesaj ve Parametreler tablolarındaki "Üreteç" sütunu. Her gönderimde
  hesaplanır ve değer tabloda da güncellenir. Önizleme üreteci ilerletmez. Başlık üreteçleri
  (örneğin `başlık.seq = counter(1,1)`) tüm mesajlarda ortaktır. Periyodik gönderimle birlikte
  kullanıldığında alanlar canlı değişir.
- **Canlı değerler:** Modbus slave register'ları (`hr:100`, `ir:5`, `coil:3`, `di:2`) ve SNMP agent
  OID'leri belirlenen aralıkla bir ifadeyle güncellenir. Modbus ve SNMP sekmelerindeki "Canlı
  değerler" tablosundan yönetilir. Hatalı bir tanım otomatik kapatılır ve loga yazılır.

## Hata enjeksiyonu

Bağlantı formundaki "Hata enjeksiyonu" bölümünden açılır; etkinken kartta ve başlıkta ⚡
görünür. Yüzdeler her gönderimde bağımsız uygulanır:

- düşür,
- çift gönder,
- checksum boz (şemadaki checksum alanı),
- uzunluk kaydır (şemadaki uzunluk alanı ±1),
- rastgele byte değiştir,
- sondan kes,
- sabit gecikme ve ± titreşim.

Elle, periyodik, kural ve senaryo gönderimlerinin yanı sıra Modbus slave yanıtlarına da
uygulanır. SNMP gönderimleri SNMP4J üzerinden gittiği için kapsam dışıdır. Yapılan her bozulma
trafik loguna "⚡ Hata enjeksiyonu: …" olarak yazılır.

## Trafik logu, grafik ve karşılaştırma

- **Kayıt incelemesi:** seçilen kaydın altında yön rozeti, mesaj adı, zaman, eş ve boyut
  görünür; çözüm hatası ya da uyarı varsa aynı satırda yazar. Solda alan tablosu (ofset, boy,
  alan, değer), sağda hex + ASCII dökümü vardır. Her alanın byte'ları dökümde sırayla iki
  tonla boyanır. Tabloda bir alanı seçmek byte'larını vurgular, dökümde bir byte'a tıklamak
  ait olduğu alanı seçer, üzerine gelmek alan adını ve değerini gösterir. Sağ üstteki düğme
  kaydın tam metin dökümünü panoya kopyalar.

- **Sağ tık menüsü:** yeniden gönder, mesaj formuna kopyala, Ham sekmesine al, hex'i panoya
  kopyala, karşılaştır (iki satır seçiliyken) ve değeri grafiğe ekle. Çoklu seçim desteklenir.
- **Yanıt (ms) sütunu:** alınan çerçevede, aynı bağlantıdan yapılan son gönderimden bu yana
  geçen süreyi gösterir. Yaklaşık bir istek/yanıt süresidir.
- **Karşılaştır:** iki çerçeveyi alan alan ve byte byte yan yana gösterir; farklar vurgulanır.
- **Grafik sekmesi:** şemalı mesaj alanları (her alınan mesajda) ve Modbus register'ları (yarım
  saniyede bir) zamana göre çizilir. Pencere 30 sn ile 15 dk arasında seçilir, duraklatılabilir.
  Seri eklemek için "Seri ekle…", Parametreler'deki "Grafiğe ekle" ya da trafik logundaki sağ
  tık kullanılabilir.

## Hazır mesajlar

Mesaj sekmesindeki **Hazır mesajlar** menüsü, formdaki değerleri bir adla kaydeder
("Eve dön", "Acil durum" gibi). Kayıtlı bir hazır mesaj tek tıkla forma yüklenir ya da
doğrudan gönderilir; mevcut değerlerle güncellenebilir ya da silinebilir. Komut paletinde
`gönder <ad>` yazarak da gönderilir. Hazır mesajlar bağlantıya aittir ve proje dosyasına
kaydedilir. Şema değiştikten sonra artık bulunmayan alanlar yüklenirken uyarı olarak gösterilir.

## Kayıt ve tekrar oynatma

Dosya > "Trafiği kayıt olarak dışa aktar (.rec)" görünen çerçeveleri zaman damgalarıyla JSON
satırları olarak yazar. **Tekrar oynat** sekmesinde kayıt açılır:

- kayıttaki her bağlantı/yön grubu güncel bir bağlantıya eşlenir,
- hız 0.25× ile 10× arasında ya da "Beklemeden" seçilir,
- isteğe bağlı döngü ve "kayıttaki eşe gönder" seçenekleri vardır.

## Yerleşim

Pencere konumu ve boyutu, bölme oranları, trafik sütun genişlikleri ve seçili alt sekme
kapanışta hatırlanır. Proje kaydedilirken projeye de yazılır; proje açılınca geri yüklenir.

## SNMP OID tablosu

Biçim için [oids/ornek.oid](oids/ornek.oid) dosyasına bakın. Satır biçimi:

```
<oid> <TİP> <ro|rw> <değer>
```

- **Tipler:** INTEGER, OCTET_STRING, HEX_STRING, OID, IPADDRESS, COUNTER32, GAUGE32,
  TIMETICKS, COUNTER64, NULL.
- `TIMETICKS @uptime`, agent başladığından beri geçen süreyi verir.
- Tablo SNMP sekmesinden düzenlenip **Kaydet** ile dosyaya yazılabilir.
- MIB dosyası içe aktarma yoktur.
- v3 parolaları en az 8 karakter olmalıdır.

## Senaryolar

İki dil vardır; Senaryo sekmesindeki **Dil** kutusundan seçilir:

| Dil | Ne zaman | Dosya |
|---|---|---|
| **Senaryo dili** | "gönder, bekle, kontrol et" testleri. Programlama bilgisi gerektirmez. | `.scn` |
| **Groovy** | Karmaşık mantık, listeler, hesaplar, dosya okuma: tam bir programlama dili. | `.groovy` |

İkisi de aynı işlemleri yapar, kontrolleri aynı şekilde sayar ve arayüzsüz çalıştırılabilir.
Proje dosyası senaryonun dilini de saklar. **Durdur** düğmesi Groovy döngülerini de keser.

### Editör

Senaryo editörü iki dili de tanır:

- **Renklendirme:** Komutlar, blok sözcükleri, metinler, `${değişken}`, sayılar ve `alan=değer`
  ayrı renklerdedir. Satır numaraları, blok katlama, parantez eşleme vardır. Tırnak ve parantezler
  kendiliğinden kapanır.
- **Tamamlama:** Yazarken kendiliğinden açılır; ⌃Boşluk ya da ⌥Boşluk ile de açılır. Öneriler
  imlecin yerine göre değişir:
  - satır başında komutlar ve kalıplar (yer tutucular arasında Tab ile geçilir),
  - bağlantı argümanında bağlantı adları (komuta uygun olanlar: `modbus` için master gibi),
  - mesaj argümanında o bağlantının şemasındaki mesajlar (yanda alan listesiyle),
  - mesajdan sonra alanlar, `alan=` / `alan: "` sonrasında enum değerleri,
  - `${` sonrasında değişkenler, Groovy'de `m.` sonrasında `waitFor` sonucunun alanları.
- **Canlı denetim:** Sözdizimi ve derleme hataları kırmızı, bilinmeyen bağlantı, mesaj ya da alan
  adları sarı dalgalı çizgiyle işaretlenir. Sağ kenardaki şerit tüm sorunları gösterir. Alt
  çubuktaki sayaca tıklamak ilk soruna götürür.
- **Belgeler:** Bir komutun ya da mesaj adının üzerine gelince açıklaması görünür.
- **Kısayollar:** ⌘F ara (⌘G sonraki), ⌘/ yorum satırı, ⌘⏎ senaryoyu çalıştır.

### Senaryo dili

Satır tabanlıdır. Örnekler: [scenarios/ornek.scn](scenarios/ornek.scn) (tüm protokoller),
[scenarios/akis.scn](scenarios/akis.scn) (döngü, fonksiyon, include).

```
include ortak.scn                      # başka dosyadaki fonksiyonları ekler

function konum_gonder hiz
  send "Örnek TCP istemci" KONUM speed=${hiz}
  waitfor "Örnek TCP sunucu" KONUM 2000
  expect "Örnek TCP sunucu" KONUM speed=${hiz}
end

var hiz = 100
while ${hiz} <= 300
  call konum_gonder ${hiz}
  if ${hiz} >= 180
    break
  end
  var hiz = ${hiz} + 20
end

mbset "Örnek Modbus slave" hr 20 1,2,3
modbus "Örnek Modbus master" read 3 20 3
snmp "Örnek SNMP manager" walk 1.3.6.1.2.1.1
```

- Tüm komutlar Senaryo sekmesindeki **Komutlar** düğmesinde listelenir.
- **Değişkenler:** `var n = counter(1,1)`, `var hiz = last("Örnek TCP sunucu", KONUM, speed)`.
  Sonraki komutlarda `${n}` olarak kullanılır.
- **Akış:**
  - `if … else … end` koşul, `assert ${n} == 5` kontrol.
  - `repeat N … end` tekrar. N bir değişken de olabilir: `repeat ${adet}`.
  - `while ${n} < 10 … end` koşul doğru oldukça tekrarlar. Sonsuz döngüye karşı 1.000.000
    turda durur.
  - `break` en içteki `repeat` ya da `while` döngüsünden çıkar.
- **Fonksiyonlar:**
  - `function ad [param …] … end` en dış seviyede tanımlanır, dosyanın herhangi bir yerinde olabilir.
  - `call ad [arg …]` fonksiyonu çağırır. Argümanlar parametre değişkenlerine yazılır
    (`${param}`), çağrı bitince eski değerleri geri gelir.
  - `return` fonksiyondan çıkar. Özyineleme 100 derinlikle sınırlıdır.
- **include:** `include dosya.scn` başka bir senaryo dosyasını o noktaya ekler. Yol, senaryo
  dosyasının klasörüne göredir; kaydedilmemiş senaryoda çalışma klasörüne göredir. Hata
  mesajlarında dosya adı da yazar: `ortak.scn satır 4`.
- **Bitirme ve hatalar:** `fail [mesaj]` başarısız sayıp bitirir, `exit` başarıyla bitirir.
  `continue_on_error on` açıkken hatalı komut atlanır ve başarısız sayılır.
- `waitfor`, senaryo başladıktan sonra gelen mesajları sırayla tüketir. Bu yüzden yanıt
  `send` komutundan hemen sonra gelse bile kaçmaz.
- Bir komut hata verirse senaryo durur. `expect` hataları ise sayılır ve sonda özetlenir.

### Groovy

Örnek: [scenarios/ornek.groovy](scenarios/ornek.groovy). Groovy 4 kullanılır; Java 8 ile
çalışır ve jar'a dahildir.

```groovy
def sunucu = "Örnek TCP sunucu"
start sunucu
start "Örnek TCP istemci"

for (hiz in (100..140).step(20)) {
    send "Örnek TCP istemci", "KONUM", speed: hiz, heading: 45
    def m = waitFor(sunucu, "KONUM", 2000)        // gelen mesajın alanları
    check m.speed == "$hiz", "KONUM hızı $hiz"
}

def hedefler = ["FRIEND", "HOSTILE"]
def atama = [tracks: hedefler.size()]
hedefler.eachWithIndex { k, i -> atama["tracks[$i].identity"] = k }
send "Örnek TCP istemci", "HEDEF_LISTESI", atama

dsl '''
repeat 3
  send "Örnek TCP istemci" HEARTBEAT uptime=42
end
'''
```

| Yöntem | Ne yapar |
|---|---|
| `start(b)`, `stop(b)` | Bağlantıyı başlatır (çalışana dek bekler) / durdurur |
| `sleep(ms)`, `log(metin)` | Bekleme ve çıktı |
| `send(b, MESAJ, alan: değer, …)` | Değerleri atar ve gönderir; `set(...)` yalnızca atar |
| `sendHex(b, "A5 01")`, `sendText(b, "OK\r\n")`, `sendBytes(b, byte[])` | Ham gönderim |
| `waitFor(b, MESAJ[, ms])` | Mesajı bekler, alanlarını döndürür: `m.speed`, `m["başlık.seq"]` |
| `last(b, MESAJ)` | Son alınan değerler (yoksa `null`) |
| `expect(b, MESAJ, alan: değer)`, `check(koşul, "açıklama")` | Kontroller (sayılır) |
| `fail(mesaj)`, `exit(mesaj)` | Bitirme |
| `modbusRead`, `modbusWrite`, `modbusCoil`, `mbset` | Modbus |
| `snmpGet`, `snmpWalk`, `snmpSet`, `trap` | SNMP |
| `dsl('''…''')` | Senaryo dilinde satırlar çalıştırır |
| `vars.ad = "5"` | Senaryo diliyle ortak değişkenler (`${ad}`) |
| `endpoint(b)` | Alt düzey bağlantı nesnesi |

Değerler metin olarak döner. Sayısal karşılaştırma için `m.speed as int` kullanın. Derleme
hataları çalıştırmadan önce `dosya.groovy satır 3:5` biçiminde gösterilir. Çalışma hataları
da betikteki satır numarasıyla yazılır.

## Arayüzsüz çalıştırma (CI)

```bash
java -jar target/commsim.jar --run projects/ornek-proje.json scenarios/ornek.scn --start-all --timeout 120
java -jar target/commsim.jar --run projects/ornek-proje.json scenarios/ornek.groovy
```

Projeyi arayüz açmadan yükler. Bağlantılar, şemalar, kurallar, üreteçler ve canlı değerler aynı
şekilde çalışır. Dil dosya uzantısından anlaşılır (`.groovy` ya da diğerleri). Senaryo dosyası
verilmezse projedeki senaryo kendi diliyle kullanılır. `--start-all` önce sunucuları sonra
istemcileri başlatır; `--verbose` her çerçeveyi yazar.

Çıkış kodu: **0** başarılı, **1** kontrol (expect/assert/fail) başarısız, **2** hata, zaman aşımı
ya da kullanım hatası.

## Proje dosyası

Proje JSON olarak saklanır. Örnek: [projects/ornek-proje.json](projects/ornek-proje.json).
İçinde şunlar bulunur:

- bağlantı ayarları,
- gönderilecek mesaj değerleri,
- Modbus master periyodik okumaları,
- Modbus slave tabloları (yalnız sıfır olmayan değerler),
- SNMP agent OID tablosu,
- otomatik yanıt kuralları, üreteçler, canlı değerler ve hata enjeksiyonu ayarları,
- hazır mesajlar,
- senaryo metni ve pencere yerleşimi.

**Dikkat:** SNMP community'leri ve v3 parolaları dosyada **düz metin** olarak durur.

## Bilinen sınırlar

- Modbus yalnız TCP'dir; RTU ve seri port yoktur.
- SNMP için MIB içe aktarma yoktur. Agent kimlik doğrulama hatasında authenticationFailure
  trap'i göndermez.
- Periyodik gönderim Mesaj ve Ham sekmelerinde vardır. Modbus için periyodik okuma, SNMP için
  senaryo kullanılır.
- Trafik logu bellekte en fazla 200.000 kayıt tutar; daha eskileri silinir.

## Testler

`mvn test` 80 test çalıştırır:

- hex işlemleri ve çerçeveleme,
- gerçek soketlerle TCP ve UDP,
- şema kodlayıcısı (eski biçim uyumu, Modbus CRC, hatalı veri, rol parametreleri,
  bağlantı bazında geçersiz kılma, düzenleyici modelinin gidiş-dönüşü, bit grupları),
- Modbus protokolü ve master–slave,
- SNMP v1, v2c ve v3 authPriv (trap'ler dahil),
- senaryo ayrıştırıcısı ve gerçek uçlarla çalıştırma,
- Türkçe yerel ayarda OID tiplerinin tanınması,
- proje dosyası gidiş-dönüşü,
- ifade dili, üreteçler ve canlı değerler,
- otomatik yanıt kuralları (TCP ve UDP), hata enjeksiyonu,
- WebSocket (RFC örneği, parçalı mesaj, ping, ret durumları, yeniden bağlanma),
- kayıt/tekrar oynatma, senaryo değişkenleri/koşulları ve arayüzsüz çalıştırıcının çıkış kodları,
- hazır mesajların kaydedilip uygulanması, soket seçeneklerinin proje dosyasında saklanması,
- komut paletinin araması (Türkçe harf eşleme dahil),
- senaryo dilinde while/break/function/include, özyineleme sınırı,
- Groovy senaryoları: gerçek uçlarla gönderme/bekleme/kontrol, satır numaralı hatalar, durdurma,
- editör: imleç bağlamının çözümlenmesi, bilinmeyen ad uyarıları ve hata satırları.
