# Harita modülü (OpenMap tabanlı) — entegrasyon rehberi

Bu doküman, bu projedeki **harita panelini** başka bir Java Swing simülatörüne taşıyacak
bir insan ya da AI ajan için yazıldı. Önce "Hızlı özet"i, sonra "Taşınacak dosyalar"ı
okuyun; geri kalanı başvuru bölümüdür.

## Hızlı özet

- `MapPanel`, bir `JPanel`'dir. İçinde OpenMap `MapBean` (uydu görüntüsü + ülke sınırları +
  enlem/boylam ızgarası) ve üstünde kullanıcının tıklayarak koyduğu **node**'lar vardır.
- Node iki türdür: **PLATFORM** (daire) ve **RADAR** (baklava/elmas). İkisi de tek bir
  `Node` sınıfıdır, farkı `type` alanıdır.
- Tamamen **çevrimdışı** çalışır. Çalışma sırasında internet gerekmez.
- Haritadaki değişiklikler **olay (listener)** ile dışarı verilir; ayrıca `nodes()` ile
  anlık liste alınabilir. Haritayı dışarıdan değiştirmek için **henüz public API yok**
  (bkz. "Bilinen sınırlar").
- Java 21 ile derlenip çalıştırıldı (21.0.12). Daha düşük sürümde denenmedi.

## Taşınacak dosyalar

Aşağıdaki küme **tek başına derlenip çalıştı** (izole bir klasörde `MapDemo` ile denendi,
node ekleme ve dinleyici olayları doğrulandı).

| Dosya | Zorunlu | Not |
|---|---|---|
| `src/sim/MapPanel.java` | evet | Harita paneli (~450 satır). |
| `src/sim/Node.java` | evet | Haritadaki nesne (~50 satır). Başka bağımlılığı yok. |
| `src/sim/Model.java` | evet* | `MapPanel` sadece 3 şey için kullanır: `Model.fmt(double)`, `Model.Message`, `Model.MessageListener`. *Bağımlılığı koparmak için "Model bağımlılığını kaldırma"ya bakın. |
| `lib/openmap-5.1.15.jar` | evet | OpenMap kütüphanesi, 3,6 MB. Maven Central'da **yok**; classpath'e elle ekleyin. |
| `data/bluemarble.jpg` | hayır | Uydu görüntüsü, 2,2 MB (NASA Blue Marble NG, Temmuz 2004, 5400x2700, tüm dünya, enlem/boylam). Yoksa uydu katmanı atlanır. |
| `data/dcwpo-browse.shp` | hayır | Ülke sınırları, 548 KB. Yoksa sınır katmanı atlanır. |
| `data/dcwpo-browse.ssx` | hayır | Yukarıdakinin mekansal indeksi, 28 KB. Yoksa shapefile indekssiz okunur. |

Üç `data/` dosyası da yoksa harita **hata vermez**, düz mavi zemin + ızgara gösterir.

**Taşınmaması gerekenler** (bu projenin kendi simülatör mantığı; yalnızca referans):
`SimulatorFrame.java`, `Main.java`, `TcpTransport.java`, `Codec.java`,
`DefinitionLoader.java`, `TestServer.java`, `messages.def`, `run.sh`, `server.sh`.
Platform TCP gate'leri ve YAYIN_LISTESI mantığı `SimulatorFrame` içindedir, haritada
değildir. Hedef simülatörün kendi iletişim katmanı varsa onu kullanın.

Toplam: 3 Java dosyası (~600 satır) + 6,3 MB jar/veri.

## Gereksinimler

- **JDK 21** (en azından bu sürümde doğrulandı). Swing gerekir; JavaFX ise `SwingNode` ile gömün.
- `lib/openmap-5.1.15.jar` classpath'te olmalı. Maven/Gradle kullanıyorsanız ya yerel dosya
  bağımlılığı verin ya da JitPack: `com.github.openmap-java:openmap:5.1.15` (internet gerektirir).
- Paket adı: dosyalar `package sim;` ile başlar. Hedef projeye taşırken üç dosyadaki
  `package` satırını ve `import sim.Model...` satırlarını yeni pakete göre düzenleyin.
- `data/` klasörü, `MapPanel` oluşturulurken verilen `Path` altında aranır. Göreli yol
  uygulamanın **çalışma dizinine** göredir; emin olmak için mutlak yol verin.

## Hızlı başlangıç (çalıştığı doğrulanmış örnek)

```java
MapPanel map = new MapPanel(Path.of("data"));           // data/ klasörünün yolu

map.setNodeListener((node, change) ->                   // ekleme/taşıma/düzenleme/silme
        System.out.println(change + " " + node.type + " " + node.name + " [" + node.nodeId + "]"));
map.setSelectionListener(n ->                           // seçim değişti
        System.out.println("SECILDI " + n.attributes()));

JFrame f = new JFrame("Harita");
f.add(map);
f.setSize(900, 700);
f.setVisible(true);
```

Kullanım: araç çubuğunda **Platform ekle** veya **Radar ekle**'yi açıp haritaya tıklayın.
Çıktıda `SECILDI {...}` ve `ADDED PLATFORM PLT-1 [1]` görünür.

## Public API (`MapPanel`)

| Üye | Açıklama |
|---|---|
| `MapPanel(Path dataDir)` | Paneli kurar; `dataDir` altındaki yukarıdaki dosyaları arar. EDT'de oluşturun. |
| `setNodeListener(NodeListener)` | `onNode(Node n, Change c)`; `Change` = `ADDED`, `MOVED`, `EDITED`, `DELETED`. |
| `setSelectionListener(Consumer<Node>)` | Seçili node değişince, ayrıca seçili node taşınınca/düzenlenince çağrılır. Seçim temizlenince (`null`) **çağrılmaz**. |
| `List<Node> nodes()` | Haritadaki tüm node'ların **kopyası** (liste kopya, `Node` nesneleri aynı). |
| `Node getSelected()` | Seçili node veya `null`. |
| `void refresh()` | `Node` alanını (ör. `gate`) kodla değiştirdiyseniz çizimi ve seçili node başlığını yeniler. |
| `onMessage(Message, boolean incoming)` | **Boş kanca** (`MessageListener`). Bu projede çağıran `Main`/`SimulatorFrame`'dir; haritada işlenmiş mantık yok. |

Olay sırası bir ekleme için: önce `SelectionListener` (yeni node seçilir), sonra
`NodeListener` `ADDED`. Sürükleme sırasında `MOVED` her fare hareketinde gelir; ağır işi
doğrudan yapmayın, birleştirin (bu projede 0,4 sn'lik `javax.swing.Timer`).

**Thread:** tüm olaylar ve `MapPanel` metotları **EDT**'de çağrılır/çağrılmalıdır. Başka thread'den
`SwingUtilities.invokeLater` ile gelin.

## `Node` modeli

```java
Node.Type    type;       // PLATFORM veya RADAR (final)
int          nodeId;     // haritada otomatik: 1'den artar (MapPanel.nextId)
String       name;       // "PLT-<id>" / "RDR-<id>"
String       identity;   // PENDING | FRIEND | HOSTILE | NEUTRAL | UNKNOWN   (renk belirler)
double       lat, lon;   // derece; lat en fazla ±85, lon ±180 ile sınırlanır
double       altitude, speed, heading;   // heading: kuzeyden saat yönünde derece (platformda çizilir)
Node.GateState gate;     // OFF | CONNECTING | CONNECTED | FAILED — bu projenin TCP gate'i için
Map<String,Object> attributes();   // anahtarlar: nodeId, nodeType, name, identity, lat, lon,
                                   //            altitude, speed, heading
```

`attributes()`, alan adı eşleştirmeyle formları doldurmak için kullanılır (bu projede
`messages.def` alan adı ya da `@bind` ile eşleşir). Değer tipleri: `Integer`, `String`, `Double`.

`gate` alanı ve haritadaki **durum halkası** (turuncu/yeşil/kırmızı) ile seçili node başlığındaki
"gate: ..." yazısı bu projeye özgüdür. Hedef simülatörde gerekmiyorsa kaldırılabilir
(`MapPanel` içinde `paint` ve `refreshProps`).

## Harita davranışı

- Başlangıç: merkez (39.9334, 32.8597), ölçek 1:12.000.000; projeksiyon **Mercator** (OpenMap varsayılanı).
- Fare: sol tuşla boşluğu sürükle = kaydır; node üstünden sürükle = node'u taşı; tekerlek =
  imlecin altındaki noktayı sabit tutarak yakınlaştır/uzaklaştır (adım 1,25x). Ölçek sınırı 1:1.000 ile 1:150.000.000.
- Araç çubuğu: **Platform ekle** / **Radar ekle** (biri açıkken haritaya tıklamak node ekler;
  ikisi aynı anda açık kalmaz), **Seçiliyi sil**.
- Alt panel: seçili node'un alanları (`nodeId`, `name`, `identity`, `altitude`, `speed`,
  `heading`, `lat`, `lon`) ve **Uygula** düğmesi.
- Katman sırası (OpenMap'te **önce eklenen üstte çizilir**): node'lar → ızgara → ülke sınırları → uydu görüntüsü.
- Uydu görüntüsü (`bluemarble.jpg`) tüm dünyayı kaplayan enlem/boylam (equirectangular) bir
  görüntüdür; OpenMap `OMWarpingImage` ile Mercator'a yeniden projekte edilir. Başka bir
  görüntüyle değiştirirseniz aynı kapsamda (-180..180, -90..90) olmalıdır. Belleğe tamamen
  yüklenir. Ölçüm (Java 21, izole demo, GC sonrası): yaklaşık 69 MB yığın kullanımı. Açılış süresi
birkaç saniye sürebilir.

## Sık yapılacak değişiklikler

**Node alanlarını kendi varlık modelinize uydurma.** Üç yer birlikte değişir:
`Node` (alanlar + `attributes()`), `MapPanel` içindeki özellik paneli (`buildSouth`,
`refreshProps`, `applyProps`) ve çizim (`ObjectLayer.paint`).

**Model bağımlılığını kaldırma.** `Model.java`'yı taşımak istemiyorsanız `MapPanel` içinde:
`implements MessageListener` ve `onMessage` ile iki `import sim.Model...` satırını silin,
`Model.fmt(...)` çağrılarını (5 yer, `refreshProps` içinde) kendi biçimlendiricinizle değiştirin.

**Dışarıdan node ekleme/taşıma/silme (şu an yok).** `addObjectAt`, `moveObject`, `deleteSelected`
`private`'tır ve ekleme `nextId++` ile id üretir. Dışarıdan veri akıtmak için public metot
ekleyin. Taslak (**test edilmemiştir**):

```java
public void addNode(Node n) {               // EDT'de çağırın
    objects.add(n);
    nextId = Math.max(nextId, n.nodeId + 1); // id çakışmasını önle
    objectLayer.repaint();
    nodeListener.onNode(n, Change.ADDED);    // dış kaynaklı eklemede olay isteyip istemediğinize karar verin
}
```
Dış kaynaklı güncellemede olay gönderirseniz, olayı dinleyen kod aynı güncellemeyi geri
yazabilir (döngü). Gerekirse "kaynak" bilgisi taşıyan ayrı bir bayrak ekleyin.

**Projeksiyon.** Mercator dışında bir projeksiyon bu projede **denenmedi** (Geographic/LLXY
denendi ve istenmediği için geri alındı). Koordinat dönüşümü `map.getProjection().forward/inverse`
ile yapılır; `inverse` sonucunda `Point2D.getX()` boylam, `getY()` enlemdir.

## Bilinen sınırlar ve tuzaklar

- **Akış tek yönlü:** harita → simülatör. Simülatör → harita için public API yok (yukarı bakın).
- **`nextId` dışarıdan ayarlanamaz;** başka bir simülatörün id'leriyle çakışabilir.
- Aynı anda tek seçim vardır; çoklu seçim yoktur.
- `lat` ±85, `lon` ±180 ile sınırlanır; tarih değiştirme çizgisini aşan şekiller işlenmez.
- Uydu görüntüsü düşük çözünürlüklüdür (piksel başına ~7 km); ülke/bölge ölçeğinde iyi,
  şehir ölçeğinde bulanıktır. Yüksek çözünürlük için karo (tile) mimarisi gerekir; yapılmadı.
- Radar için menzil/tarama alanı gibi özel alan **yoktur**; `speed`/`heading` radarda çizime etki etmez.
- macOS'ta 5000 numaralı TCP portunu AirPlay Alıcısı tutar; test sunucusu için 5000 kullanmayın
  (bu projede varsayılan 5555).
- OpenMap lisansı (BBN): `https://raw.githubusercontent.com/openmap-java/openmap/v5.1.15/LICENSE`.
  Başka bir ürüne gömmeden önce koşulları gözden geçirin.
- Veri kaynakları: uydu görüntüsü NASA Visible Earth (Blue Marble Next Generation); ülke sınırları
  OpenMap deposundaki `share/data/shape/dcwpo-browse.shp`.

## Bu projenin geri kalanı (referans)

Taşınmayacak ama nasıl çalıştığını görmek isteyen ajan için:

- `SimulatorFrame`: haritadaki her PLATFORM için bir TCP istemci gate'i açar (`Gate`); radarlar
  tüm platformlarca tespit edilmiş sayılır ve değişiklikte `YAYIN_LISTESI` mesajıyla gönderilir.
  Haritayı nasıl dinlediğini görmek için `onNodeChanged`, `fillFromNode`, `listValue`, `valueFor`.
- `Codec` + `DefinitionLoader` + `messages.def`: metin tabanlı mesaj tanımı ve ikili kodlama.
- `TcpTransport`: bağımsız, yeniden kullanılabilir TCP istemci/sunucu (başlık: int32 uzunluk + yük).
- Çalıştırma: `./server.sh` (konsol test sunucusu, port 5555), sonra `./run.sh` (simülatör).
