#!/usr/bin/env bash
# 聚合验证器 —— 输出 PASS / SKIP / FAIL 三态。
#
# 规则（沿用 CarVeX）：SKIP 只表示「缺依赖、这次没验」，**不算通过**；
# 汇总里单独计数，任何 FAIL 都让退出码非 0。
#
# 用法：
#   tools/verify.sh              # 架构守卫 + 纯逻辑单测（默认，最快）
#   BUILD=1 tools/verify.sh      # 追加 carbridge + 假车机 APK 构建
#   SMOKE=1 tools/verify.sh      # 追加装机 + 假车机全流程（需要手机 + 平板）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

# ---- JDK：优先 JAVA_HOME，其次本机常用工具链 ---------------------------------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
    for cand in \
        "$HOME/项目/资料/toolchain/jdk-current/Contents/Home" \
        "/opt/homebrew/opt/openjdk@17" \
        "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"; do
        if [ -x "$cand/bin/javac" ]; then JAVA_HOME="$cand"; break; fi
    done
fi
export JAVA_HOME

PASS=0; SKIP=0; FAIL=0
declare -a SUMMARY
record() {  # record <状态> <名字> <备注>
    local st="$1" name="$2" note="${3:-}"
    case "$st" in
        PASS) PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %-28s %s\n' "$name" "$note" ;;
        SKIP) SKIP=$((SKIP+1)); printf '  \033[33mSKIP\033[0m  %-28s %s\n' "$name" "$note" ;;
        FAIL) FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %-28s %s\n' "$name" "$note" ;;
    esac
    SUMMARY+=("$st|$name|$note")
}

run_step() {  # run_step <名字> <命令...>；日志落 /tmp/verify-<名字>.log
    local name="$1"; shift
    local log="/tmp/verify-${name}.log"
    if "$@" >"$log" 2>&1; then
        record PASS "$name"
        return 0
    fi
    record FAIL "$name" "见 $log"
    tail -n 15 "$log" | sed 's/^/        /'
    return 1
}

printf '\n=== carbridge 验证 ===\n'
printf 'JDK: %s\n\n' "${JAVA_HOME:-（未找到，尝试系统 PATH 的 javac）}"

# ---- 1. 架构守卫（必跑） -----------------------------------------------------
printf '[1/6] 协议 docset 结构\n'
if [ ! -x docs/protocol/verify.py ]; then
    record FAIL "协议 docset" "docs/protocol/verify.py 缺失或不可执行"
else
    run_step "协议 docset" docs/protocol/verify.py
fi

printf '\n[2/6] 架构守卫\n'
if [ ! -x tools/check-arch.sh ]; then
    record FAIL "架构守卫" "tools/check-arch.sh 缺失或不可执行"
else
    run_step "架构守卫" tools/check-arch.sh
fi

# ---- 2. 纯逻辑单测（必跑） ---------------------------------------------------
printf '\n[3/6] 静态体检（javac -Xlint:all）\n'
if [ ! -x tools/lint.sh ]; then
    record FAIL "静态体检" "tools/lint.sh 缺失或不可执行"
else
    run_step "静态体检" tools/lint.sh
fi

printf '\n[4/6] 纯逻辑单测\n'
if [ ! -x tools/carbridge-tests/run.sh ]; then
    record FAIL "纯逻辑单测" "tools/carbridge-tests/run.sh 缺失或不可执行"
else
    log="/tmp/verify-unit.log"
    if tools/carbridge-tests/run.sh >"$log" 2>&1; then
        # 单测自己会打印 "通过 N / 失败 0"，把 N 带进汇总
        n=$(grep -o '通过 [0-9]\+' "$log" | tail -1 | awk '{print $2}')
        record PASS "纯逻辑单测" "${n:+$n 项}"
    else
        record FAIL "纯逻辑单测" "见 $log"
        tail -n 15 "$log" | sed 's/^/        /'
    fi
fi

# ---- 3. 构建（可选：BUILD=1） -------------------------------------------------
printf '\n[5/6] 构建\n'
if [ "${BUILD:-0}" != "1" ]; then
    record SKIP "构建" "未开启（BUILD=1 开启）"
else
    run_step "carbridge APK" "$ROOT/android/build.sh"
    run_step "假车机 APK" bash -c "cd '$ROOT/tools/fakecar' && ./build.sh"
fi

# ---- 4. 冒烟（可选：SMOKE=1，需要手机 + 平板） -------------------------------
printf '\n[6/6] 冒烟（真机全流程）\n'
if [ "${SMOKE:-0}" != "1" ]; then
    record SKIP "冒烟" "未开启（SMOKE=1 开启，需手机+平板）"
else
    ADB="${ADB:-$HOME/android-sdk/platform-tools/adb}"
    [ -x "$ADB" ] || ADB="$(command -v adb || true)"
    if [ -z "$ADB" ]; then
        record FAIL "冒烟" "SMOKE=1 但找不到 adb"
    else
        n=$("$ADB" devices 2>/dev/null | awk '/\tdevice$/{n++} END{print n+0}')
        if [ "${n:-0}" -lt 2 ]; then
            record FAIL "冒烟" "SMOKE=1 但只检测到 ${n:-0} 台设备（需手机+平板）"
        else
            run_step "冒烟" tools/smoke.sh
        fi
    fi
fi

# ---- 汇总 --------------------------------------------------------------------
printf '\n=== 汇总：PASS %d / SKIP %d / FAIL %d ===\n' "$PASS" "$SKIP" "$FAIL"
if [ "$SKIP" -gt 0 ]; then
    printf '注意：SKIP 表示「本次未验证」，不算通过。\n'
fi
[ "$FAIL" -eq 0 ] || exit 1
