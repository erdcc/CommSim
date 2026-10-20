#!/bin/sh
# Java 8 uyumluluk denetimi: tum testleri ve ornek senaryolari gercek bir Java 8 ile calistirir.
# Java 8 yolu: JAVA8_HOME, yoksa macOS'ta /usr/libexec/java_home -v 1.8
# Derleme JDK 21 ile (release=8) yapilir; JAVA_HOME derleyici icindir.
cd "$(dirname "$0")" || exit 1
J8_HOME="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}"
if [ -z "$J8_HOME" ] || [ ! -x "$J8_HOME/bin/java" ]; then
  echo "Java 8 bulunamadı. JAVA8_HOME ile gösterin." >&2
  exit 2
fi
J8="$J8_HOME/bin/java"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
echo "== Java 8: $("$J8" -version 2>&1 | head -1)"
echo "== Testler Java 8 üzerinde"
mvn -q package -Djvm="$J8" || { echo "TESTLER BAŞARISIZ"; exit 1; }
ver=$(grep -h 'name="java.version"' target/surefire-reports/*.xml | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
total=0; for f in target/surefire-reports/TEST-*.xml; do n=$(sed -n 's/.*<testsuite[^>]*tests="\([0-9]*\)".*/\1/p' "$f" | head -1); total=$((total + n)); done
echo "   $total test geçti (java.version=$ver)"
status=0
for s in scenarios/ornek.scn scenarios/akis.scn scenarios/ornek.groovy; do
  out=$("$J8" -jar target/commsim.jar --run projects/ornek-proje.json "$s" 2>&1 | tail -1)
  code=$?
  echo "== $s: $out"
  case "$out" in *BAŞARILI*) ;; *) status=1 ;; esac
done
[ $status -eq 0 ] && echo "JAVA 8 DENETİMİ BAŞARILI" || echo "JAVA 8 DENETİMİ BAŞARISIZ"
exit $status
