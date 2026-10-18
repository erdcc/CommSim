#!/bin/sh
# Haberlesme simulatorunu Maven ile derler ve calistirir: ./commsim.sh [proje.json]
# Testleri atlamak icin: SKIP_TESTS=1 ./commsim.sh
cd "$(dirname "$0")" || exit 1
JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
[ -x "$JAVA_HOME_DIR/bin/java" ] || JAVA_HOME_DIR=""
JAVA="${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/}java"
MVN_FLAGS="-q"
[ -n "$SKIP_TESTS" ] && MVN_FLAGS="$MVN_FLAGS -DskipTests"
mvn $MVN_FLAGS package && exec "$JAVA" -jar target/commsim.jar "$@"
