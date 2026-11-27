#!/usr/bin/env bash
# AdbClient 协议测试：JVM 编译 App 内置 adb 客户端（Base64/Diag 用替身），
# 直连 tools/fake-adbd.py --stray（注入跨流迟到包），断言每条 shell 拿到自己的输出。
#
# 背景（为什么这个测试必须存在）：车机（Android 9 老 adbd）会把已关闭流的迟到
# WRTE/CLSE 混进下一条流，旧客户端收包不看流 id（arg1）→ getprop 读数整行错位，
# 「车机一键解锁」的 setprop 成败被误判。修复=按流 id 过滤，本测试钉死它。
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
    for cand in "$HOME/项目/资料/toolchain/jdk-17.0.13+11/Contents/Home" \
        "$HOME/项目/资料/toolchain/jdk-current/Contents/Home"; do
        if [ -x "$cand/bin/javac" ]; then JAVA_HOME="$cand"; break; fi
    done
fi
export JAVA_HOME
JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"

ROOT="$(cd ../.. && pwd)"
OUT=out
rm -rf "$OUT"; mkdir -p "$OUT"

echo "[1/3] 编译（App 的 AdbClient 原文件 + 替身 + Harness）"
"$JAVAC" -d "$OUT" -sourcepath src \
    src/com/carbridge/car/Harness.java \
    stubs/android/util/Base64.java \
    stubs/com/carbridge/carplay/util/Diag.java \
    "$ROOT/android/app/java/com/carbridge/car/AdbClient.java"

PORT=15575
FAKE_LOG="$OUT/fake-adbd.log"
echo "[2/3] 起 fake-adbd --stray（端口 ${PORT}，注入跨流迟到包）"
python3 "$ROOT/tools/fake-adbd.py" --stray "$PORT" >"$FAKE_LOG" 2>&1 &
FAKE_PID=$!
trap 'kill $FAKE_PID 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && { exec 3>&- 3<&-; break; }
    sleep 0.2
done

echo "[3/3] 跑 Harness"
"$JAVA" -cp "$OUT" com.carbridge.car.Harness "$PORT"
echo "adb 协议测试：全部通过 ✔"
