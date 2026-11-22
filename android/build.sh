#!/usr/bin/env bash
# carbridge —— 无 Gradle 构建链：aapt2 → javac → d8 → zipalign → apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
# 版本号只认 AndroidManifest，别在这里再手写一份（以前两处会漂）
VER="$(grep -o 'android:versionName="[^"]*"' "$ROOT/app/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"$//')"
[ -n "$VER" ] || { echo "读不到 versionName" >&2; exit 1; }

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
ANDROID_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac || true)"
[ -n "$JAVAC" ] || { echo "缺少 javac：请设置 JAVA_HOME" >&2; exit 1; }
JDK_BIN="$(dirname "$JAVAC")"

for tool in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JDK_BIN/javac" "$ANDROID_JAR"; do
  [ -e "$tool" ] || { echo "缺少工具: $tool" >&2; exit 1; }
done

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 本地调试签名；build/ 不进版本库
KEYSTORE="$OUT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  "$JDK_BIN/keytool" -genkeypair -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Android Debug,O=Android,C=US' -keystore "$KEYSTORE" >/dev/null 2>&1
fi

"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --auto-add-overlay \
  "$OUT/res.zip"

find "$APP/java" "$OUT/gen" -name '*.java' | LC_ALL=C sort > "$OUT/sources.txt"
"$JDK_BIN/javac" -source 8 -target 8 -nowarn -proc:none \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

( cd "$OUT/classes" && "$JDK_BIN/jar" cf "$OUT/classes.jar" . )
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --release --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" ./*.dex )

"$BT/zipalign" -P 16 -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/carbridge-$VER.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/carbridge-$VER.apk" >/dev/null && echo "签名 OK"

echo "产物: $OUT/carbridge-$VER.apk  ($(wc -c < "$OUT/carbridge-$VER.apk") bytes)"
