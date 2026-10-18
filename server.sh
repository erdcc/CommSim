#!/bin/sh
# Konsol test sunucusunu derler ve calistirir: ./server.sh [port]
cd "$(dirname "$0")" || exit 1
JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
[ -x "$JAVA_HOME_DIR/bin/javac" ] || JAVA_HOME_DIR=""
JAVAC="${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/}javac"
JAVA="${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/}java"
mkdir -p out && "$JAVAC" -cp "lib/*" -d out src/sim/*.java && exec "$JAVA" -cp "out:lib/*" sim.TestServer "$@"
