#!/usr/bin/env bash
# 纯逻辑单测：编译 products 里不依赖 Android 的类 + 测试，跑 JVM
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/android/app/java"
OUT="$HERE/out"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
[ -x "$JAVA" ] || JAVA="$(command -v java)"

rm -rf "$OUT"; mkdir -p "$OUT"
{
  find "$SRC/com/carbridge/carplay/iap2" -name '*.java'
  find "$SRC/com/carbridge/carplay/pairing" -name '*.java'
  echo "$SRC/com/carbridge/carplay/core/DisplayGeometry.java"
  echo "$SRC/com/carbridge/carplay/core/AdaptiveResolution.java"
  echo "$SRC/com/carbridge/carplay/core/AirPlaySetup.java"
  echo "$SRC/com/carbridge/carplay/core/AirPlayInfo.java"
  echo "$SRC/com/carbridge/carplay/core/HidDescriptor.java"
  echo "$SRC/com/carbridge/carplay/core/HidInput.java"
  echo "$SRC/com/carbridge/carplay/core/RtspMessage.java"
  echo "$SRC/com/carbridge/carplay/core/Bplist.java"
  echo "$SRC/com/carbridge/carplay/core/HidKeyMap.java"
  echo "$SRC/com/carbridge/carplay/core/AirPlayStreamCrypto.java"
  echo "$SRC/com/carbridge/app/QualityPrefs.java"
  echo "$SRC/com/carbridge/app/StepChain.java"
  echo "$SRC/com/carbridge/app/StepDiagnosis.java"
  echo "$SRC/com/carbridge/app/StatusView.java"
  echo "$SRC/com/carbridge/app/MetricsText.java"
  echo "$SRC/com/carbridge/carplay/util/MacText.java"
  find "$HERE/src" -name '*.java'
} > "$OUT/sources.txt"

"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" @"$OUT/sources.txt"
exec "$JAVA" -cp "$OUT" carbridgetests.SelfTests
