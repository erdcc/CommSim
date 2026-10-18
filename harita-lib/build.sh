#!/bin/sh
# Harita modulunu tek bir jar olarak derler: dist/harita-modulu-<surum>.jar
# Kullanim: ./build.sh [surum]     (varsayilan surum: 1.0)
# Gerekli: JDK 8 veya ustu. JDK 9+ ise Java 8 uyumlu bytecode uretilir (--release 8).
set -e
cd "$(dirname "$0")"
VERSION="${1:-1.0}"
OPENMAP="lib/openmap-5.1.15.jar"

JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
[ -x "$JAVA_HOME_DIR/bin/javac" ] || JAVA_HOME_DIR=""
JAVAC="${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/}javac"
JAR="${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/}jar"

REL=""
if "$JAVAC" -help 2>&1 | grep -q -- '--release'; then REL="--release 8"; fi

rm -rf build dist
mkdir -p build/classes build/jar dist

"$JAVAC" $REL -encoding UTF-8 -Xlint:-options -cp "$OPENMAP" -d build/classes src/harita/*.java

# OpenMap siniflarini jar'a ac (imza dosyasi yok; manifest'i degistiriyoruz)
(cd build/jar && "$JAR" xf "../../$OPENMAP")
rm -f build/jar/META-INF/MANIFEST.MF build/jar/META-INF/*.SF build/jar/META-INF/*.RSA build/jar/META-INF/*.DSA

cp -R build/classes/. build/jar/
mkdir -p build/jar/harita/data build/jar/META-INF/licenses
cp resources/harita/data/* build/jar/harita/data/
cp lib/OPENMAP-LICENSE.txt build/jar/META-INF/licenses/OPENMAP-LICENSE.txt
cp NOTICE.txt build/jar/META-INF/licenses/harita-NOTICE.txt

printf 'Manifest-Version: 1.0\nMain-Class: harita.MapDemo\nImplementation-Title: harita-modulu\nImplementation-Version: %s\n' "$VERSION" > build/MANIFEST.MF
"$JAR" cfm "dist/harita-modulu-$VERSION.jar" build/MANIFEST.MF -C build/jar .

echo "Hazir: dist/harita-modulu-$VERSION.jar"
