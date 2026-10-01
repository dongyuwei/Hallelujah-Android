#!/bin/sh
# Regenerates app/libs/rime-machine.jar: rime.wasm compiled to Java bytecode
# with endive's build-time compiler (the interpreter needs 1.5-7s per
# keypress on Android; the compiled machine runs at native JVM speed).
#
# Run this whenever the rime.wasm asset changes:
#   ./scripts/build-rime-machine.sh
set -e

JARS=/tmp/endive-jars
mkdir -p "$JARS"
fetch() {
    [ -f "$JARS/$2" ] || curl -sL "https://repo1.maven.org/maven2/$1" -o "$JARS/$2"
}
fetch run/endive/runtime/1.1.0/runtime-1.1.0.jar runtime-1.1.0.jar
fetch run/endive/wasm/1.1.0/wasm-1.1.0.jar wasm-1.1.0.jar
fetch run/endive/compiler/1.1.0/compiler-1.1.0.jar compiler-1.1.0.jar
fetch org/ow2/asm/asm/9.10.1/asm-9.10.1.jar asm-9.10.1.jar
fetch org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar asm-commons-9.10.1.jar
fetch org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar asm-util-9.10.1.jar

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/app/libs"
java -cp "$JARS/runtime-1.1.0.jar:$JARS/wasm-1.1.0.jar:$JARS/compiler-1.1.0.jar:$JARS/asm-9.10.1.jar:$JARS/asm-commons-9.10.1.jar:$JARS/asm-util-9.10.1.jar" \
    "$ROOT/tools/GenerateRimeMachine.java" \
    "$ROOT/app/src/main/assets/rime/rime.wasm" \
    "$ROOT/app/libs/rime-machine.jar" \
    rkr.tinykeyboard.inputmethod.rime.RimeCompiled
echo "regenerated $ROOT/app/libs/rime-machine.jar"
