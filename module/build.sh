#!/usr/bin/env bash
# 编 arm64 静态二进制 → 打 SukiSU / KernelSU 模块 zip。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK:-$(ls -d "$HOME"/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -n "$NDK" ] || { echo "找不到 NDK：设置 ANDROID_NDK 或装到 ~/android-sdk/ndk/"; exit 1; }

VER="$(grep '^version=' "$ROOT/module.prop" | cut -d= -f2)"
CC="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang 2>/dev/null | head -1)"
[ -x "$CC" ] || { echo "找不到 aarch64 clang：$NDK"; exit 1; }

STAGE="$ROOT/.stage"
OUT="$ROOT/../build/sukisu-iphone-bt-$VER.zip"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin"

echo "== 编译 iphone_bt (arm64 static) =="
"$CC" -O2 -Wall -Wextra -fPIE -pie -s -o "$STAGE/bin/iphone_bt" "$ROOT/iphone_bt.c"
file "$STAGE/bin/iphone_bt" | sed 's/^/   /'

echo "== 组装模块 =="
cp "$ROOT/module.prop" "$ROOT/service.sh" "$ROOT/uninstall.sh" \
   "$ROOT/action.sh" "$ROOT/customize.sh" "$STAGE/"
chmod 755 "$STAGE"/*.sh "$STAGE/bin/iphone_bt"

# 模块里刻意**没有** system/ 目录：不替换任何系统文件，卸载即恢复。
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && zip -qr "$OUT" . )
echo "== 产物 =="
echo "  $OUT"
unzip -l "$OUT" | sed 's/^/   /'
