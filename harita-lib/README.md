# harita-modulu — tek jar'lık harita bileşeni (Java 8, OpenMap tabanlı)

Bir Java Swing projesine **jar olarak eklenip doğrudan kullanılan** harita paneli. İnsan ya da AI ajan
için yazıldı. Çıktı: `dist/harita-modulu-1.0.jar` (tek dosya, ~6,5 MB).

## Hızlı özet

- **Tek jar yeterli.** İçinde: harita kodu, OpenMap kütüphanesi, uydu görüntüsü ve ülke sınırları.
  Ayrı `lib/`, `data/` klasörü ya da internet gerekmez.
- Paket: `harita`. Kullanacağınız sınıflar: `harita.MapPanel`, `harita.Node`.
- Bytecode **Java 8** (sınıf sürümü 52). Swing gerekir.
- Haritaya tıklayarak **PLATFORM** ve **RADAR** node'ları eklenir; değişiklikler dinleyiciyle dışarı verilir.
- `java -jar harita-modulu-1.0.jar` ile bağımsız bir demo penceresi açılır (jar'ın doğru çalıştığını görmek için).

## Projeye ekleme

Jar'ı projenizin classpath'ine ekleyin.

- **IDE (IntelliJ / Eclipse / NetBeans):** jar'ı projeye "library / external JAR" olarak ekleyin.
- **Düz javac:** `javac -cp harita-modulu-1.0.jar ...` ve `java -cp "harita-modulu-1.0.jar:..." ...`
  (Windows'ta ayraç `;`).
- **Gradle** (*denenmedi*): `implementation files('libs/harita-modulu-1.0.jar')`
- **Maven** (*denenmedi*): önce yerel depoya kurun:
  ```sh
  mvn install:install-file -Dfile=harita-modulu-1.0.jar -DgroupId=harita \
      -DartifactId=harita-modulu -Dversion=1.0 -Dpackaging=jar
  ```
  sonra `pom.xml`'e `<dependency>` olarak `harita:harita-modulu:1.0` ekleyin.

**Çakışma uyarısı:** Bu jar OpenMap **5.1.15**'i içinde taşır. Hedef projede zaten başka bir OpenMap
kopyası varsa iki kopya classpath'te çakışır; classpath sırasında ilk gelen sınıflar kullanılır. Sürümler
farklıysa beklenmedik davranış olabilir. Çözüm: ya hedef projedeki ayrı OpenMap jar'ını kaldırıp bu jar'daki
kopyaya güvenin, ya da aynı sürümü (5.1.15) kullanın. (Denenmedi.)

## Kullanım (doğrulanmış kod)

Aşağıdaki kod, yalnızca bu jar'ın classpath'te olduğu ayrı bir klasörde derlenip çalıştırıldı
(`javac --release 8`):

```java
import harita.MapPanel;
import harita.Node;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class Kullanim {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {                       // Swing: EDT'de oluşturun
            MapPanel map = new MapPanel();                       // ek dosya yok, veri jar'dan gelir

            map.setNodeListener((Node n, MapPanel.Change c) ->   // ekleme/taşıma/düzenleme/silme
                    System.out.println(c + " " + n.type + " " + n.name + " id=" + n.nodeId));
            map.setSelectionListener(n ->                        // seçim değişti
                    System.out.println("SECILDI " + n.attributes()));

            JFrame f = new JFrame("Harita");
            f.add(map);
            f.setSize(900, 700);
            f.setVisible(true);
        });
    }
}
```

Çalışınca araç çubuğunda **Platform ekle** / **Radar ekle**'yi açıp haritaya tıklayın; konsola
`ADDED PLATFORM PLT-1 id=1` gibi satırlar düşer. Haritayı kendi arayüzünüzün istediği yere
(`JSplitPane`, `BorderLayout`, sekme vb.) normal bir `JPanel` gibi koyabilirsiniz.

> **Not:** `Node` adı yaygındır (`org.w3c.dom.Node`, JavaFX `Node`). Bu sınıfları da kullanıyorsanız
> `harita.Node`'u tam adıyla yazın.

## Public API

| Üye | Açıklama |
|---|---|
| `new MapPanel()` | Jar'a gömülü veriyle kurar (uydu görüntüsü + ülke sınırları). **Önerilen.** |
| `new MapPanel(Path dataDir)` | Verileri bir klasörden okur: `bluemarble.jpg`, `dcwpo-browse.shp`, `dcwpo-browse.ssx`. Dosya yoksa o katman atlanır; `null` verilirse düz zeminle açılır. Kendi görüntünüzü kullanmak için. |
| `setNodeListener(NodeListener)` | `onNode(Node n, Change c)`; `Change` = `ADDED`, `MOVED`, `EDITED`, `DELETED`. |
| `setSelectionListener(Consumer<Node>)` | Seçili node değişince, ayrıca seçili node taşınınca/düzenlenince çağrılır. Seçim temizlenince (`null`) **çağrılmaz**. |
| `List<Node> nodes()` | Haritadaki tüm node'ların **kopya listesi** (liste kopya; `Node` nesneleri aynı). |
| `Node getSelected()` | Seçili node veya `null`. |
| `void refresh()` | `Node` alanını (ör. `gate`) kodla değiştirdiyseniz çizimi ve seçili node başlığını yeniler. |

Olay sırası bir ekleme için: önce `SelectionListener` (yeni node seçilir), sonra `NodeListener` `ADDED`.
Sürükleme sırasında `MOVED` her fare hareketinde gelir; ağır işi doğrudan yapmayın, birleştirin
(örneğin 0,4 sn'lik bir `javax.swing.Timer` ile).

**Thread:** `MapPanel` oluşturma, olaylar ve tüm metotlar **EDT**'de olmalıdır. Başka thread'den
`SwingUtilities.invokeLater` ile gelin.

## `Node` modeli

```java
Node.Type    type;       // PLATFORM veya RADAR (final)
int          nodeId;     // haritada otomatik: 1'den artar
String       name;       // "PLT-<id>" / "RDR-<id>"
String       identity;   // PENDING | FRIEND | HOSTILE | NEUTRAL | UNKNOWN   (renk belirler)
double       lat, lon;   // derece; lat en fazla ±85, lon ±180 ile sınırlanır
double       altitude, speed, heading;   // heading: kuzeyden saat yönünde derece (platformda çizilir)
Node.GateState gate;     // OFF | CONNECTING | CONNECTED | FAILED — dış bir TCP gate durumunu göstermek için
Map<String,Object> attributes();   // anahtarlar: nodeId, nodeType, name, identity, lat, lon,
                                   //            altitude, speed, heading   (Integer / String / Double)
```

`gate` alanı kodla değiştirilip `map.refresh()` çağrılırsa platformun etrafında durum halkası çizilir
(turuncu = bağlanıyor, yeşil = bağlı, kırmızı = hata) ve seçili node başlığında "gate: ..." görünür.
Kullanmıyorsanız hiçbir etkisi yoktur (`OFF` varsayılan).

## Harita davranışı

- Başlangıç: merkez (39.9334, 32.8597), ölçek 1:12.000.000; projeksiyon Mercator.
- Fare: sol tuşla boşluğu sürükle = kaydır; node üstünden sürükle = node'u taşı; tekerlek / dokunmatik
  kaydırma = imlecin altındaki noktayı sabit tutarak yakınlaştır/uzaklaştır. Ölçek 1:1.000 ile 1:150.000.000.
- Araç çubuğu: **Platform ekle** / **Radar ekle** (biri açıkken haritaya tıklamak node ekler; ikisi aynı
  anda açık kalmaz), **Seçiliyi sil**. Alt panel: seçili node'un alanları ve **Uygula** düğmesi.
- Platform = daire (yönü gösteren çizgiyle), Radar = baklava/elmas.

## Gömülü veri nasıl çalışır

OpenMap shapefile'ı dosya yolu ister, jar içinden okuyamaz. Bu yüzden `new MapPanel()` ilk açılışta
gömülü verileri şu klasöre çıkarır ve sonraki açılışlarda yeniden kullanır:

```
<java.io.tmpdir>/harita-modulu-<sürüm>/     (örn. macOS: /var/folders/.../T/harita-modulu-1.0)
```

- Klasör jar sürümüne göre adlandırılır; yeni sürüm kendi klasörünü kullanır. Eski klasörleri silebilirsiniz.
- Toplam yaklaşık 2,9 MB disk. Çıkarma atomik yazılır (eşzamanlı iki süreç sorun çıkarmamalı).
- Klasör yazılamazsa (salt-okunur temp vb.) kod bunu `System.err`'e yazar ve harita **verisiz** (düz zemin
  + ızgara) açılır, uygulama çökmez. *Bu yol denenmedi.*
- Uydu görüntüsü açılışta bellekte decode edilir: ~69 MB yığın (ölçüldü) ve açılışta birkaç saniye;
  `new MapPanel()` bu işi çağıran thread'de (EDT) yapar.

## Yeniden derleme

```sh
./build.sh          # → dist/harita-modulu-1.0.jar
./build.sh 1.1      # sürüm numarasını verir (jar adı ve temp klasörü buna göre değişir)
```

Gerekli: JDK 8+ (JDK 9+ ise `--release 8` ile Java 8 uyumlu bytecode üretilir). `build.sh`, JDK'yı
`JAVA_HOME`'dan, yoksa `/opt/homebrew/opt/openjdk@21`'den, yoksa PATH'ten alır.

Yapı:
```
src/harita/      MapPanel, Node, BundledData (veri çıkarma), MapDemo (Main-Class)
resources/harita/data/   bluemarble.jpg, dcwpo-browse.shp, dcwpo-browse.ssx   (jar'a gömülür)
lib/             openmap-5.1.15.jar, OPENMAP-LICENSE.txt   (yalnızca derleme/paketleme için)
NOTICE.txt       üçüncü taraf bileşen bildirimi (jar'ın META-INF/licenses/ altına girer)
```

Paket adını değiştirmek isterseniz (`harita` → kendi paketiniz): `src/harita/` klasörünü ve
dosyalardaki `package` satırlarını, `BundledData` içindeki `/harita/data/` yolunu, `resources/harita/`
klasörünü ve `build.sh` içindeki `harita/` yollarını birlikte değiştirin.

## Doğrulama durumu (dürüst not)

Denendi:
- `build.sh` ile jar üretildi; sınıf sürümü **52 (Java 8)**, 1907 sınıf.
- Yalnızca jar'ın bulunduğu, `data/` ve `lib/` olmayan ayrı bir klasörde bir tüketici program `javac --release 8`
  ile derlendi ve çalıştırıldı: harita uydu görüntüsüyle açıldı, `ADDED` olayı geldi.
- `java -jar harita-modulu-1.0.jar` doğrudan açıldı.
- Çalıştırma **JDK 21** üzerinde yapıldı.

**Denenmedi:**
- **Gerçek bir Java 8 çalışma ortamı** (üretildiği makinede Java 8 JDK'sı yok; `--release 8` yalnızca
  sözdizimini ve API imzalarını denetler). Swing/AWT çizimi ve OpenMap'in Java 8 üzerindeki çalışması
  doğrulanmış değil; OpenMap sınıfları Java 5 bytecode'u olduğu için sorun çıkması beklenmiyor.
  Entegrasyondan önce hedef Java 8'de `java -jar harita-modulu-1.0.jar` ile bir kez çalıştırıp görün.
- Windows, Linux; macOS dışı temp klasörü davranışı.
- Maven/Gradle ile bağımlılık olarak ekleme.
- Hedef projede başka bir OpenMap sürümü bulunduğunda çakışma.

## Bilinen sınırlar

- **Akış tek yönlü:** harita → uygulamanız. Uygulamanızdan haritaya node eklemek/taşımak/silmek için
  public API yok (`addObjectAt`, `moveObject`, `deleteSelected` private; `nextId` dışarıdan
  ayarlanamaz, kendi id'lerinizle çakışabilir). İhtiyaç varsa `MapPanel`'e `addNode/updateNode/removeNode`
  eklenmeli (dış kaynaklı güncellemede olay göndermeyi bilinçli seçin: dinleyici aynı güncellemeyi geri
  yazarsa döngü olur).
- Aynı anda tek seçim; çoklu seçim yok.
- Uydu görüntüsü düşük çözünürlüklü (piksel başına ~7 km): ülke/bölge ölçeğinde iyi, şehir ölçeğinde
  bulanık. Yüksek çözünürlük için karo (tile) mimarisi gerekir; yapılmadı.
- Radar için menzil/tarama alanı yok; `speed`/`heading` radarda çizime etki etmez.
- Mercator dışında projeksiyon denenmedi.

## Lisans ve kaynaklar

- **OpenMap 5.1.15** (BBN Technologies), değiştirilmeden jar'a dahildir. Lisans metni jar'da
  `META-INF/licenses/OPENMAP-LICENSE.txt`, bu klasörde `lib/OPENMAP-LICENSE.txt`. Kısaca (kendi okumam,
  hukuki tavsiye değildir): nesne kodu olarak dağıtım serbesttir; telif/uyarı notları korunmalı ve
  OpenMap'in nereden edinileceği belirtilmelidir (madde 5a: <https://github.com/openmap-java/openmap>);
  OpenMap'in kendisi için ücret alınamaz ve "kendi ürününüz" olarak sunulamaz (madde 6). Ticari bir ürüne
  gömmeden önce koşulları kendiniz doğrulayın.
- **Uydu görüntüsü:** NASA Visible Earth, Blue Marble Next Generation (Temmuz 2004).
- **Ülke sınırları:** OpenMap deposundaki `share/data/shape/dcwpo-browse.shp`.
