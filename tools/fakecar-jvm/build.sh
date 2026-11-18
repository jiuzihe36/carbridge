#!/usr/bin/env bash
# 假车机测试台：把产品代码里的 iAP2 发送端编译到 JVM 上跑（不需要 Android 设备、不需要蓝牙）
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/android/app/java"
OUT="$HERE/out"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac || true)"
[ -n "$JAVAC" ] || { echo "缺少 javac：请设置 JAVA_HOME" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

find "$SRC/com/carbridge/carplay/iap2" -name '*.java' > "$OUT/sources.txt"
echo "$SRC/com/carbridge/carplay/core/AirPlaySetup.java" >> "$OUT/sources.txt"
find "$HERE/src" -name '*.java' >> "$OUT/sources.txt"

"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" @"$OUT/sources.txt"
echo "编译完成 → $OUT"
