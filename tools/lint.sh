#!/usr/bin/env bash
# 静态体检：javac -Xlint:all 必须 0 警告。
#
# 为什么单独一条门禁：这个仓库长期把警告当噪声，结果「废弃 API / 未检查泛型 / 丢精度」
# 一路累积到几百条，真正的新问题就埋在里面看不见了。收敛到 0 之后，任何一条新警告
# 都必然是这次改动引入的——比人工 review 可靠。
#
# 关键实现点（踩过的坑）：
#   * 必须把 aapt2 生成的 R.java 一起编进去，否则会冒出上百条「找不到符号 R」的假错；
#   * 不要加 -nowarn，它会把 -Xlint:all 整个抵消；
#   * X25519/Ed25519 的 `c[0] &= 248` 是 RFC 钳位，不是丢精度 bug——那部分已按类压制。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android/app"
OUT="$ROOT/build/lint"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
AJ="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac || true)"

for tool in "$BT/aapt2" "$AJ" "$JAVAC"; do
    if [ ! -e "$tool" ]; then
        echo "SKIP 静态体检：缺少 $tool（设置 ANDROID_SDK_ROOT / JAVA_HOME）"
        exit 0
    fi
done

rm -rf "$OUT"
mkdir -p "$OUT/gen"

# R.java：link 一次资源表（不需要签名与 dex，只为拿到 R）
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip" >/dev/null
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$AJ" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --auto-add-overlay \
    -A "$APP/assets" \
    "$OUT/res.zip" >/dev/null

find "$APP/java" "$OUT/gen" -name '*.java' | LC_ALL=C sort > "$OUT/sources.txt"

# 注意：-source/-target 8 与线上构建一致；不写 -nowarn。
set +e
"$JAVAC" -source 8 -target 8 -proc:none -Xlint:all \
    -bootclasspath "$AJ" -d "$OUT/classes" @"$OUT/sources.txt" 2>"$OUT/log.txt"
rc=$?
set -e

# 统计真正的「警告: [类别]」行（中英 locale 都覆盖）
n=$(grep -cE '(警告|warning): \[' "$OUT/log.txt" || true)
if [ "$rc" -ne 0 ] || [ "$n" -ne 0 ]; then
    echo "FAIL 静态体检：编译失败或存在警告（$n 条）"
    grep -E '(警告|warning): \[|错误|error:' "$OUT/log.txt" | head -40
    exit 1
fi
echo "PASS 静态体检：javac -Xlint:all 0 警告"
